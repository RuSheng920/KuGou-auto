package com.moekoe.xposed.kugoulite

import android.content.Context
import android.content.SharedPreferences
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import java.io.File

/**
 * 凭证与每日状态存储（v1.5.0）
 *
 * 数据存储在酷狗概念版的 SharedPreferences 中（Hook 运行在酷狗进程内，可直接访问）。
 * - 凭证缓存：避免每次启动都依赖 OkHttp 捕获
 * - 每日领取状态：防止一天内重复领取
 * - 每日升级状态：升级与领取解耦，已领取未升级时也能单独补升级
 * - 配置镜像：模块 APP 的开关配置在酷狗进程侧的缓存，避免每次判断都走跨进程
 * - SP 扫描退避：遍历酷狗 SP 是重 IO，1.4.0 每 5 秒全量扫一次，这里加缓存 + 失败退避
 */
object PrefStore {

    private const val TAG = "[KuGouLiteVip]"
    private const val PREFS_NAME = "kugoulite_vip_module"

    private const val KEY_TOKEN = "token"
    private const val KEY_USERID = "userid"
    private const val KEY_DFID = "dfid"
    private const val KEY_MID = "mid"
    private const val KEY_APPID = "appid"
    private const val KEY_CLIENTVER = "clientver"
    private const val KEY_LAST_CLAIM_DATE = "last_claim_date"
    private const val KEY_LAST_CLAIM_SUCCESS = "last_claim_success_date"
    private const val KEY_LAST_UPGRADE_DATE = "last_upgrade_date"
    private const val KEY_LAST_CLAIM_AT = "last_claim_at"

    // 配置镜像键
    private const val KEY_CFG_ENABLED = "cfg_enabled"
    private const val KEY_CFG_AUTO_UPGRADE = "cfg_auto_upgrade"
    private const val KEY_CFG_UPDATED_AT = "cfg_updated_at"

    // 凭证落盘节流：相同凭证 30 秒内不重复写
    private const val CRED_SAVE_MIN_GAP_MS = 30_000L

    // SP 扫描策略
    private const val SP_SCAN_SUCCESS_CACHE_MS = 5 * 60 * 1000L
    private const val SP_SCAN_FAIL_BACKOFF_MS = 10 * 1000L

    @Volatile
    private var lastSavedCredsKey: String? = null

    @Volatile
    private var lastSavedCredsAt = 0L

    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var lastScanSucceeded = false

    private fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 凭证

    /**
     * 保存凭证到缓存（相同凭证 30 秒内去重，避免热路径频繁写 SP）
     */
    fun saveCreds(ctx: Context, creds: KugouApi.Credentials) {
        val key = (creds.token ?: "") + "|" + (creds.userid ?: "")
        val now = System.currentTimeMillis()
        if (key == lastSavedCredsKey && now - lastSavedCredsAt < CRED_SAVE_MIN_GAP_MS) {
            return
        }
        lastSavedCredsKey = key
        lastSavedCredsAt = now
        try {
            val editor = getPrefs(ctx).edit()
            editor.putString(KEY_TOKEN, creds.token)
            editor.putString(KEY_USERID, creds.userid)
            editor.putString(KEY_DFID, creds.dfid)
            editor.putString(KEY_MID, creds.mid)
            editor.putString(KEY_APPID, creds.appid)
            editor.putString(KEY_CLIENTVER, creds.clientver)
            editor.apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 从缓存加载凭证
     */
    fun loadCreds(ctx: Context): KugouApi.Credentials? {
        return try {
            val sp = getPrefs(ctx)
            val token = sp.getString(KEY_TOKEN, null)
            if (token.isNullOrEmpty()) return null

            val creds = KugouApi.Credentials()
            creds.token = token
            creds.userid = sp.getString(KEY_USERID, null)
            creds.dfid = sp.getString(KEY_DFID, null)
            creds.mid = sp.getString(KEY_MID, null)
            creds.appid = sp.getString(KEY_APPID, null)
            creds.clientver = sp.getString(KEY_CLIENTVER, null)
            creds
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从酷狗概念版自身的 SharedPreferences 中尝试读取凭证（备用方案）
     *
     * 当 OkHttp Hook 尚未捕获到凭证时，遍历酷狗的 SP 文件查找 token/userid。
     * v1.5.0：扫描是重 IO，成功后 5 分钟内不再扫；失败退避 10 秒。
     * 返回 null 表示"这次不扫"，由调用方走其他凭证来源（内存缓存等）。
     */
    fun tryReadFromAppPrefs(ctx: Context): KugouApi.Credentials? {
        val now = System.currentTimeMillis()
        val gap =
            if (lastScanSucceeded) SP_SCAN_SUCCESS_CACHE_MS else SP_SCAN_FAIL_BACKOFF_MS
        if (now - lastScanAt < gap) return null
        lastScanAt = now

        var creds: KugouApi.Credentials? = null
        try {
            val dataDir = ctx.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                val files = prefsDir.listFiles()
                if (files != null) {
                    var token: String? = null
                    var userid: String? = null
                    var dfid: String? = null
                    var mid: String? = null

                    for (file in files) {
                        if (!file.name.endsWith(".xml")) continue
                        // 跳过模块自身的 SP
                        if (file.name.startsWith(PREFS_NAME)) continue

                        val prefsName = file.name.replace(".xml", "")
                        val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

                        if (token == null) {
                            token = sp.getString("token", null)
                            if (token == null) token = sp.getString("access_token", null)
                            if (token == null) token = sp.getString("kugou_token", null)
                        }
                        if (userid == null) {
                            userid = sp.getString("userid", null)
                            if (userid == null) userid = sp.getString("uid", null)
                            if (userid == null) userid = sp.getString("kugouid", null)
                            if (userid == null) userid = sp.getString("user_id", null)
                            if (userid == null) userid = sp.getString("userID", null)
                        }
                        if (dfid == null) {
                            dfid = sp.getString("dfid", null)
                        }
                        if (mid == null) {
                            mid = sp.getString("mid", null)
                            if (mid == null) mid = sp.getString("KUGOU_API_MID", null)
                        }

                        if (token != null && userid != null) break
                    }

                    if (!token.isNullOrEmpty() && !userid.isNullOrEmpty()) {
                        creds = KugouApi.Credentials()
                        creds.token = token
                        creds.userid = userid
                        creds.dfid = dfid
                        creds.mid = mid
                        XposedBridge.log(TAG + " 从酷狗SP读取到凭证: userid=$userid")
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(TAG + " 读取酷狗SP失败: $e")
        }

        lastScanSucceeded = creds != null
        return creds
    }

    // ------------------------------------------------------------ 领取状态

    /**
     * 检查今天是否已尝试领取（按北京时间自然日）
     */
    fun isClaimedToday(ctx: Context): Boolean {
        return try {
            val today = KugouApi.getLocalDayKey()
            val lastClaim = getPrefs(ctx).getString(KEY_LAST_CLAIM_DATE, "")
            today == lastClaim
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 标记今天已尝试领取/未领取（按北京时间自然日）
     */
    fun setClaimedToday(ctx: Context, claimed: Boolean) {
        try {
            val editor = getPrefs(ctx).edit()
            if (claimed) {
                editor.putString(KEY_LAST_CLAIM_DATE, KugouApi.getLocalDayKey())
            } else {
                editor.remove(KEY_LAST_CLAIM_DATE)
            }
            editor.apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 检查今天是否已成功领取（按北京时间自然日）
     */
    fun isClaimedSuccessToday(ctx: Context): Boolean {
        return try {
            val today = KugouApi.getLocalDayKey()
            val lastSuccess = getPrefs(ctx).getString(KEY_LAST_CLAIM_SUCCESS, "")
            today == lastSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 标记今天已成功领取（按北京时间自然日）
     */
    fun setClaimedSuccessToday(ctx: Context, success: Boolean) {
        try {
            val editor = getPrefs(ctx).edit()
            if (success) {
                editor.putString(KEY_LAST_CLAIM_SUCCESS, KugouApi.getLocalDayKey())
                editor.putLong(KEY_LAST_CLAIM_AT, System.currentTimeMillis())
            } else {
                editor.remove(KEY_LAST_CLAIM_SUCCESS)
            }
            editor.apply()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 升级状态（v1.5.0 新增）

    /**
     * 检查今天升级是否已完成（与领取解耦，支持"已领取未升级"单独补）
     */
    fun isUpgradeDoneToday(ctx: Context): Boolean {
        return try {
            val today = KugouApi.getLocalDayKey()
            val last = getPrefs(ctx).getString(KEY_LAST_UPGRADE_DATE, "")
            today == last
        } catch (_: Exception) {
            false
        }
    }

    fun setUpgradeDoneToday(ctx: Context, done: Boolean) {
        try {
            val editor = getPrefs(ctx).edit()
            if (done) {
                editor.putString(KEY_LAST_UPGRADE_DATE, KugouApi.getLocalDayKey())
            } else {
                editor.remove(KEY_LAST_UPGRADE_DATE)
            }
            editor.apply()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 配置镜像（v1.5.0 新增）

    /**
     * 保存模块 APP 配置的本地镜像，供酷狗进程秒读
     */
    fun saveConfigCache(ctx: Context, enabled: Boolean, autoUpgrade: Boolean) {
        try {
            getPrefs(ctx).edit()
                .putBoolean(KEY_CFG_ENABLED, enabled)
                .putBoolean(KEY_CFG_AUTO_UPGRADE, autoUpgrade)
                .putLong(KEY_CFG_UPDATED_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 读取配置镜像。返回 null 表示没有可用镜像。
     */
    fun loadConfigCache(ctx: Context): BooleanArray? {
        return try {
            val sp = getPrefs(ctx)
            if (sp.getLong(KEY_CFG_UPDATED_AT, 0L) <= 0L) return null
            booleanArrayOf(
                sp.getBoolean(KEY_CFG_ENABLED, true),
                sp.getBoolean(KEY_CFG_AUTO_UPGRADE, true)
            )
        } catch (_: Exception) {
            null
        }
    }
}
