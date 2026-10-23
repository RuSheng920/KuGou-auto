package com.moekoe.xposed.kugoulite

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/**
 * 模块配置界面（纯原生 UI，不依赖 AndroidX / Material）。
 *
 * v1.5.0：
 *  - 开关变更后立即广播 ACTION_CONFIG_CHANGED，酷狗进程秒级刷新配置缓存
 *    （不再依赖 60 秒轮询，也不需要重启酷狗）；
 *  - 「自动升级概念版VIP」默认开启；
 *  - 手动触发后 1.5s / 5s 两次拉取状态，升级链路稍长也能看到结果。
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "module_state"
        private const val TARGET_PACKAGE = "com.kugou.android.lite"

        /** 桌面入口 alias，隐藏图标即禁用该组件 */
        private const val LAUNCHER_ALIAS = "com.moekoe.xposed.kugoulite.Launcher"
        private const val KEY_HIDE_ICON = "hide_launcher_icon"

        private val STATE_URI: Uri = Uri.parse("content://com.moekoe.xposed.kugoulite.state")
    }

    private lateinit var sp: SharedPreferences

    /** 代码里回填控件状态时置位，避免触发监听器 */
    private var binding = false

    private lateinit var switchEnabled: Switch
    private lateinit var switchUpgrade: Switch
    private lateinit var switchHideIcon: Switch
    private lateinit var textStatus: TextView
    private lateinit var textState: TextView
    private lateinit var textVersion: TextView
    private lateinit var textTarget: TextView
    private lateinit var chipStatus: TextView
    private lateinit var dotStatus: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        switchEnabled = findViewById(R.id.switch_enabled)
        switchUpgrade = findViewById(R.id.switch_upgrade)
        switchHideIcon = findViewById(R.id.switch_hide_icon)
        textStatus = findViewById(R.id.text_status)
        textState = findViewById(R.id.text_state)
        textVersion = findViewById(R.id.text_version)
        textTarget = findViewById(R.id.text_target)
        chipStatus = findViewById(R.id.chip_status)
        dotStatus = findViewById(R.id.dot_status)

        textVersion.text = getString(R.string.version_format, versionName())

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            sp.edit().putBoolean(Settings.KEY_ENABLED, checked).apply()
            notifyConfigChanged()
            updateChip()
        }

        switchUpgrade.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            sp.edit().putBoolean(Settings.KEY_AUTO_UPGRADE, checked).apply()
            notifyConfigChanged()
        }

        switchHideIcon.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked) {
                confirmHideIcon()
            } else {
                setLauncherHidden(false)
            }
        }

        findViewById<Button>(R.id.btn_trigger).setOnClickListener { triggerClaim() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { updateStatus() }

        updateTargetState()
    }

    override fun onResume() {
        super.onResume()
        bindSwitches()
        updateStatus()
        updateTargetState()
    }

    // ------------------------------------------------------------ 界面状态

    private fun bindSwitches() {
        binding = true
        switchEnabled.isChecked = sp.getBoolean(Settings.KEY_ENABLED, true)
        // v1.5.0：自动升级默认开启
        switchUpgrade.isChecked = sp.getBoolean(Settings.KEY_AUTO_UPGRADE, true)
        switchHideIcon.isChecked = isLauncherHidden()
        binding = false
        updateChip()
    }

    private fun updateChip() {
        val enabled = switchEnabled.isChecked
        chipStatus.setText(if (enabled) R.string.chip_enabled else R.string.chip_disabled)
    }

    private fun updateTargetState() {
        val installed = isKuGouInstalled()
        textTarget.setText(if (installed) R.string.target_ok else R.string.target_missing)
        textTarget.setTextColor(getColor(if (installed) R.color.ok else R.color.err))
    }

    private fun updateStatus() {
        val message = sp.getString(Settings.KEY_STATUS_MSG, getString(R.string.status_default))
        val code = sp.getInt(Settings.KEY_STATUS_CODE, -1)
        val time = sp.getLong(Settings.KEY_STATUS_TIME, 0)

        val colorRes: Int
        val stateRes: Int
        when (code) {
            KugouApi.CODE_SUCCESS -> {
                stateRes = R.string.state_success
                colorRes = R.color.ok
            }
            KugouApi.CODE_ALREADY -> {
                stateRes = R.string.state_already
                colorRes = R.color.brand
            }
            KugouApi.CODE_RISK -> {
                stateRes = R.string.state_risk
                colorRes = R.color.warn
            }
            KugouApi.CODE_ERROR -> {
                stateRes = R.string.state_error
                colorRes = R.color.err
            }
            else -> {
                stateRes = R.string.state_idle
                colorRes = R.color.text_secondary
            }
        }

        val sb = StringBuilder(message ?: "")
        if (time > 0) {
            val timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(time)).toString()
            sb.append(getString(R.string.status_time_format, timeStr))
        }

        textStatus.text = sb.toString()
        textState.setText(stateRes)
        textState.setTextColor(getColor(colorRes))

        val dot = dotStatus.background
        if (dot != null) {
            dot.mutate().setTint(getColor(colorRes))
        }
    }

    // -------------------------------------------------------- 领取 / 触发

    private fun triggerClaim() {
        if (!isKuGouInstalled()) {
            Toast.makeText(this, R.string.trigger_no_app, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.call(STATE_URI, "reset_claim", null, null)
        } catch (_: Exception) {
        }
        try {
            val intent = Intent(ClaimWorker.ACTION_TRIGGER)
            intent.setPackage(TARGET_PACKAGE)
            sendBroadcast(intent)
        } catch (_: Exception) {
        }
        Toast.makeText(this, R.string.trigger_sent, Toast.LENGTH_LONG).show()
        // 领取约 1~2 秒回状态；升级链路稍长，5 秒再拉一次
        textStatus.postDelayed({ updateStatus() }, 1500L)
        textStatus.postDelayed({ updateStatus() }, 5000L)
    }

    /** 开关变更 → 广播给酷狗进程，秒级生效（不用重启酷狗） */
    private fun notifyConfigChanged() {
        try {
            val intent = Intent(ClaimWorker.ACTION_CONFIG_CHANGED)
            intent.setPackage(TARGET_PACKAGE)
            sendBroadcast(intent)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 隐藏图标

    private fun confirmHideIcon() {
        AlertDialog.Builder(this)
            .setTitle(R.string.hide_icon_title)
            .setMessage(R.string.hide_icon_message)
            .setPositiveButton(R.string.action_confirm) { _, _ -> setLauncherHidden(true) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> bindSwitches() }
            .setOnCancelListener { bindSwitches() }
            .show()
    }

    private fun setLauncherHidden(hide: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, LAUNCHER_ALIAS),
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            sp.edit().putBoolean(KEY_HIDE_ICON, hide).apply()
            Toast.makeText(
                this,
                if (hide) R.string.hide_icon_on else R.string.hide_icon_off,
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.hide_icon_fail, Toast.LENGTH_SHORT).show()
        }
        bindSwitches()
    }

    private fun isLauncherHidden(): Boolean {
        return try {
            val state = packageManager
                .getComponentEnabledSetting(ComponentName(this, LAUNCHER_ALIAS))
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        } catch (_: Exception) {
            sp.getBoolean(KEY_HIDE_ICON, false)
        }
    }

    // ---------------------------------------------------------------- 杂项

    private fun isKuGouInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun versionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.5.0"
        } catch (_: Exception) {
            "1.5.0"
        }
    }
}
