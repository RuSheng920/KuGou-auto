package com.moekoe.xposed.kugoulite

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 跨进程状态共享 ContentProvider
 *
 * 运行在模块 APP 进程中，供 Hook（运行在酷狗概念版进程）通过
 * ContentResolver.call() 读写配置与状态。
 *
 * 调用方法：
 * - get_config:  arg=配置键, 返回 Bundle{value:boolean}
 * - put_config:  arg=配置键, extras={value:boolean}
 * - get_status:  返回 Bundle{message, code, time}
 * - put_status:  extras={message, code, time}
 * - reset_claim:        设置重置标记（领取 + 升级都重置）
 * - check_reset_claim:  酷狗进程检查是否需要重置（命中后清除标记）
 */
class StateProvider : ContentProvider() {

    private fun getPrefs(): SharedPreferences? {
        val ctx = getContext() ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val sp = getPrefs() ?: return null
        val result = Bundle()

        when (method) {
            "get_config" -> {
                if (arg == null) return null
                // enabled 默认 true，其余（auto_upgrade）v1.5.0 起默认 true
                val defaultValue = true
                result.putBoolean("value", sp.getBoolean(arg, defaultValue))
                return result
            }

            "put_config" -> {
                if (arg != null && extras != null) {
                    sp.edit().putBoolean(arg, extras.getBoolean("value", true)).apply()
                }
                return null
            }

            "get_status" -> {
                result.putString("message", sp.getString(Settings.KEY_STATUS_MSG, "未运行"))
                result.putInt("code", sp.getInt(Settings.KEY_STATUS_CODE, -1))
                result.putLong("time", sp.getLong(Settings.KEY_STATUS_TIME, 0))
                return result
            }

            "put_status" -> {
                if (extras != null) {
                    val editor = sp.edit()
                    editor.putString(Settings.KEY_STATUS_MSG, extras.getString("message", ""))
                    editor.putInt(Settings.KEY_STATUS_CODE, extras.getInt("code", -1))
                    editor.putLong(Settings.KEY_STATUS_TIME, extras.getLong("time", 0))
                    editor.apply()
                }
                return null
            }

            "reset_claim" -> {
                // v1.5.0：领取与升级标记一起重置，手动触发可补升级
                sp.edit()
                    .putBoolean("reset_claim_pending", true)
                    .apply()
                return null
            }

            "check_reset_claim" -> {
                val pending = sp.getBoolean("reset_claim_pending", false)
                if (pending) {
                    sp.edit().remove("reset_claim_pending").apply()
                }
                result.putBoolean("value", pending)
                return result
            }

            else -> return null
        }
    }

    // ===== 以下方法为 ContentProvider 必需实现，本 Provider 仅使用 call() =====

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        private const val PREFS_NAME = "module_state"
    }
}
