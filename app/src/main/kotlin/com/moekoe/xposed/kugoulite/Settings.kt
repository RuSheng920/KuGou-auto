package com.moekoe.xposed.kugoulite

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.moekoe.xposed.kugoulite.compat.XposedBridge

/**
 * 模块配置读写（v1.5.0 重构）
 *
 * 1.4.0 的问题：每次领取判断都同步走 ContentResolver.call() 跨进程读配置，
 * 模块 APP 进程没起来时会被拉起进程，秒级延迟直接压在领取关键路径上；
 * 且读失败时默认 false，"自动升级"开关形同虚设 —— 这就是每次升级都要进模块
 * 手动点触发的根因之一。
 *
 * v1.5.0 策略（读缓存优先 + 异步刷新 + 变更广播）：
 *  1. 进程内内存缓存秒读，领取链路零跨进程开销；
 *  2. 每个进程首次使用时同步刷新一次（后台线程，不卡 UI）；
 *  3. 之后由看门狗每 60 秒、模块 APP 开关变更广播触发异步刷新；
 *  4. 刷新结果同时写入 PrefStore 配置镜像，Provider 不可达时用镜像兜底。
 */
object Settings {

    private const val TAG = "[KuGouLiteVip]"
    private const val AUTHORITY = "com.moekoe.xposed.kugoulite.state"
    private val CONTENT_URI: Uri = Uri.parse("content://" + AUTHORITY)

    // 模块APP包名
    private const val MODULE_PACKAGE = "com.moekoe.xposed.kugoulite"
    private const val PREFS_NAME = "module_state"

    // 配置键
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_UPGRADE = "auto_upgrade"

    // 状态键
    const val KEY_STATUS_MSG = "status_msg"
    const val KEY_STATUS_CODE = "status_code"
    const val KEY_STATUS_TIME = "status_time"

    // v1.5.0：自动升级默认开启（用户核心诉求：升级不再需要手动进模块点）
    private const val DEFAULT_AUTO_UPGRADE = true

    @Volatile
    private var initialized = false

    @Volatile
    private var enabledValue = true

    @Volatile
    private var autoUpgradeValue = DEFAULT_AUTO_UPGRADE

    @Volatile
    private var lastRefreshAt = 0L

    /**
     * 是否启用自动领取（默认启用）。进程内秒读。
     */
    fun isEnabled(ctx: Context): Boolean {
        if (!initialized) ensureInitialized(ctx)
        return enabledValue
    }

    /**
     * 是否自动升级概念版VIP（v1.5.0 默认启用）。进程内秒读。
     */
    fun isAutoUpgrade(ctx: Context): Boolean {
        if (!initialized) ensureInitialized(ctx)
        return autoUpgradeValue
    }

    /**
     * 每个进程首次使用前调用一次，同步拿到权威配置。
     * 只在后台线程调用，不会卡任何主流程。
     */
    @Synchronized
    fun ensureInitialized(ctx: Context) {
        if (initialized) return
        refreshBlocking(ctx)
        initialized = true
        ModuleLog.log("配置初始化完成: enabled=$enabledValue autoUpgrade=$autoUpgradeValue")
    }

    /**
     * 异步刷新配置（看门狗周期刷新 / 模块 APP 变更广播时调用）
     */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            try {
                refreshBlocking(app)
            } catch (_: Throwable) {
            }
        }, "KuGouLiteVip-CfgRefresh").start()
    }

    /**
     * 同步刷新：Provider 优先，createPackageContext 兜底，镜像保底。
     */
    @Synchronized
    private fun refreshBlocking(ctx: Context) {
        // 通道1：ContentProvider
        try {
            val b1 = ctx.contentResolver.call(CONTENT_URI, "get_config", KEY_ENABLED, null)
            if (b1 != null) {
                enabledValue = b1.getBoolean("value", true)
            }
            val b2 = ctx.contentResolver.call(CONTENT_URI, "get_config", KEY_AUTO_UPGRADE, null)
            if (b2 != null) {
                autoUpgradeValue = b2.getBoolean("value", DEFAULT_AUTO_UPGRADE)
            }
            lastRefreshAt = System.currentTimeMillis()
            PrefStore.saveConfigCache(ctx, enabledValue, autoUpgradeValue)
            return
        } catch (e: Exception) {
            XposedBridge.log(TAG + " ContentProvider读取配置失败: $e")
        }

        // 通道2：直接读模块 APP 的 SP 文件
        try {
            val moduleCtx = ctx.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            val sp = moduleCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enabledValue = sp.getBoolean(KEY_ENABLED, true)
            autoUpgradeValue = sp.getBoolean(KEY_AUTO_UPGRADE, DEFAULT_AUTO_UPGRADE)
            lastRefreshAt = System.currentTimeMillis()
            PrefStore.saveConfigCache(ctx, enabledValue, autoUpgradeValue)
            XposedBridge.log(TAG + " 直接读取配置: enabled=$enabledValue autoUpgrade=$autoUpgradeValue")
            return
        } catch (e: Exception) {
            XposedBridge.log(TAG + " 直接读取配置失败: $e")
        }

        // 通道3：本地镜像兜底（Provider 与文件都不可达时）
        val cached = PrefStore.loadConfigCache(ctx)
        if (cached != null) {
            enabledValue = cached[0]
            autoUpgradeValue = cached[1]
            XposedBridge.log(TAG + " 使用配置镜像: enabled=$enabledValue autoUpgrade=$autoUpgradeValue")
        }
    }

    /**
     * 回写运行状态到模块 APP（供 MainActivity 显示）。
     * v1.5.0：完全异步，失败静默，绝不阻塞领取/升级链路。
     */
    fun putStatus(ctx: Context, message: String?, code: Int) {
        val app = ctx.applicationContext
        Thread({
            try {
                val extras = Bundle()
                extras.putString("message", message)
                extras.putInt("code", code)
                extras.putLong("time", System.currentTimeMillis())
                app.contentResolver.call(CONTENT_URI, "put_status", null, extras)
            } catch (_: Exception) {
            }
        }, "KuGouLiteVip-Status").start()
    }
}
