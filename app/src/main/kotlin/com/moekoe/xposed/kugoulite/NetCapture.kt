package com.moekoe.xposed.kugoulite

import com.moekoe.xposed.kugoulite.compat.MethodHookParam
import com.moekoe.xposed.kugoulite.compat.XC_MethodHook
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import com.moekoe.xposed.kugoulite.compat.XposedHelpers

/**
 * OkHttp 网络请求捕获器（v1.5.0 Kotlin 重写）
 *
 * 相比 1.4.0 的改进：
 *  1. 增加 Cookie 头解析（token=...; userid=...; dfid=...; KUGOU_API_MID=...），
 *     酷狗部分接口只走 Cookie 不带 query，之前会漏；
 *  2. 去重键从"单 token"改为 "token|userid"，换账号后回调不再被吞；
 *  3. 凭证变更即时落盘（PrefStore 内部 30 秒去重），下次冷启动 SP 里就是新凭证，
 *     把"等 OkHttp 捕获"的等待时间压到接近 0。
 */
object NetCapture {

    private const val TAG = "[KuGouLiteVip]"
    private const val OKHTTP_BUILDER = "okhttp3.Request\$Builder"

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近一次捕获到的凭证，供 ClaimWorker 主动拉取（不依赖回调时序） */
    @Volatile
    private var lastCreds: KugouApi.Credentials? = null

    @Volatile
    private var lastCredsKey: String? = null

    @Volatile
    private var callback: CredsCallback? = null

    private var appContext: android.content.Context? = null

    fun interface CredsCallback {
        fun onCredsCaptured(creds: KugouApi.Credentials)
    }

    /** 主动拉取最近捕获的凭证，没有就返回 null */
    fun getCapturedCreds(): KugouApi.Credentials? = lastCreds

    /**
     * 设置凭证捕获回调。
     * 如果之前已经捕到过凭证（那会儿还没人接），这里立刻补发一次。
     */
    fun setCallback(cb: CredsCallback?) {
        callback = cb
        val creds = lastCreds
        if (cb != null && creds != null && creds.isValid()) {
            XposedBridge.log(TAG + " 补发此前捕获的凭证: userid=" + creds.userid)
            cb.onCredsCaptured(creds)
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed.get()) return

        try {
            val builderClass = XposedHelpers.findClass(OKHTTP_BUILDER, classLoader)
            XposedHelpers.findAndHookMethod(builderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        handleRequest(param.getResult())
                    } catch (_: Throwable) {
                        // 静默处理，避免影响酷狗正常运行
                    }
                }
            })

            installed.set(true)
            XposedBridge.log(TAG + " OkHttp Hook 安装成功")

        } catch (e: Exception) {
            XposedBridge.log(TAG + " OkHttp Hook 安装失败（酷狗可能未使用标准OkHttp）: $e")
        }
    }

    /** 由 ClaimWorker.start 注入上下文，用于凭证落盘 */
    fun attachContext(ctx: android.content.Context) {
        appContext = ctx.applicationContext
    }

    private fun handleRequest(request: Any?) {
        if (request == null) return

        val urlObj = XposedHelpers.callMethod(request, "url") ?: return
        val urlStr = urlObj.toString()
        if (!urlStr.contains("kugou")) return

        var token = pick(getQueryParam(urlObj, "token"), null)
        var userid = pick(getQueryParam(urlObj, "userid"), getQueryParam(urlObj, "uid"))
        if (userid == null) userid = getQueryParam(urlObj, "kugouid")
        var dfid = getQueryParam(urlObj, "dfid")
        var mid = getQueryParam(urlObj, "mid")
        var appid = getQueryParam(urlObj, "appid")
        var clientver = getQueryParam(urlObj, "clientver")

        // 请求头兜底（含 v1.5.0 新增的 Cookie 头解析）
        if (token == null || userid == null) {
            val headers = try {
                XposedHelpers.callMethod(request, "headers")
            } catch (_: Throwable) {
                null
            }
            if (headers != null) {
                if (token == null) token = pick(getHeader(headers, "token"), getHeader(headers, "Token"))
                if (token == null) token = pick(getHeader(headers, "x-token"), getHeader(headers, "X-Token"))
                if (userid == null) userid = pick(getHeader(headers, "userid"), getHeader(headers, "userId"))
                if (userid == null) userid = pick(getHeader(headers, "uid"), null)
                if (dfid == null) dfid = getHeader(headers, "dfid")
                if (mid == null) mid = getHeader(headers, "mid")

                // Cookie: token=...; userid=...; dfid=...; KUGOU_API_MID=...
                if (token == null || userid == null) {
                    val cookie = getHeader(headers, "Cookie")
                    if (cookie != null) {
                        val map = parseCookie(cookie)
                        if (token == null) token = pick(map["token"], map["Token"])
                        if (userid == null) userid = pick(map["userid"], map["uid"])
                        if (dfid == null) dfid = map["dfid"]
                        if (mid == null) mid = map["KUGOU_API_MID"]
                    }
                }
            }
        }

        // 表单请求体兜底（酷狗不少接口把参数放在 body 里）
        if (token == null || userid == null || dfid == null || mid == null) {
            val form = readFormBody(request)
            if (form != null) {
                if (token == null) token = pick(form[0], null)
                if (userid == null) userid = pick(form[1], null)
                if (dfid == null) dfid = pick(form[2], null)
                if (mid == null) mid = pick(form[3], null)
                if (appid == null) appid = pick(form[4], null)
                if (clientver == null) clientver = pick(form[5], null)
            }
        }

        if (token.isNullOrEmpty() || userid.isNullOrEmpty()) {
            return
        }

        val creds = KugouApi.Credentials()
        creds.token = token
        creds.userid = userid
        creds.dfid = dfid
        creds.mid = mid
        creds.appid = appid
        creds.clientver = clientver

        // 关键：先落内存，不管回调在不在
        lastCreds = creds

        val key = token + "|" + userid
        val isNew = key != lastCredsKey
        if (isNew) {
            lastCredsKey = key
            // 凭证变更即时落盘（内部 30 秒去重），加速下次冷启动
            appContext?.let { PrefStore.saveCreds(it, creds) }
        }

        val cb = callback
        if (cb == null) {
            XposedBridge.log(TAG + " 捕获到凭证但回调未就绪，先缓存（ClaimWorker 会来取）")
            return
        }

        if (!isNew) return   // 回调就绪后按凭证去重

        XposedBridge.log(TAG + " 捕获到凭证: userid=" + userid
                + " dfid=" + shortOf(dfid)
                + " mid=" + (if (mid != null) "yes" else "null"))
        cb.onCredsCaptured(creds)
    }

    /** 解析 okhttp3.FormBody：size()/name(i)/value(i)，找不到就返回 null */
    private fun readFormBody(request: Any): Array<String?>? {
        return try {
            val body = XposedHelpers.callMethod(request, "body") ?: return null
            val sizeObj = XposedHelpers.callMethod(body, "size")
            if (sizeObj !is Int) return null
            val size = sizeObj
            if (size <= 0 || size > 200) return null

            val out = arrayOfNulls<String>(6)
            for (i in 0 until size) {
                val nameObj = XposedHelpers.callMethod(body, "name", i) ?: continue
                val valueObj = XposedHelpers.callMethod(body, "value", i) ?: continue
                val name = nameObj.toString()
                val value = valueObj.toString()
                when (name) {
                    "token" -> if (out[0] == null) out[0] = value
                    "userid", "uid", "kugouid" -> if (out[1] == null) out[1] = value
                    "dfid" -> if (out[2] == null) out[2] = value
                    "mid" -> if (out[3] == null) out[3] = value
                    "appid" -> if (out[4] == null) out[4] = value
                    "clientver" -> if (out[5] == null) out[5] = value
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /** 解析 Cookie 字符串为键值对 */
    private fun parseCookie(cookie: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in cookie.split(";")) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    private fun pick(first: String?, second: String?): String? {
        if (!first.isNullOrEmpty()) return first
        if (!second.isNullOrEmpty()) return second
        return null
    }

    private fun shortOf(value: String?): String {
        if (value == null) return "null"
        return value.substring(0, minOf(8, value.length)) + "..."
    }

    private fun getQueryParam(urlObj: Any, key: String): String? {
        return try {
            val value = XposedHelpers.callMethod(urlObj, "queryParameter", key)
            value?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun getHeader(headers: Any, name: String): String? {
        return try {
            val value = XposedHelpers.callMethod(headers, "get", name)
            value?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
