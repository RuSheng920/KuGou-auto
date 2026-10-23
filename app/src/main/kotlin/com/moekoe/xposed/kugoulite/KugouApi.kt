package com.moekoe.xposed.kugoulite

import com.moekoe.xposed.kugoulite.compat.XposedBridge
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

/**
 * 酷狗概念版 API 封装（v1.5.0 Kotlin 重写）
 *
 * 核心接口：
 * 1. 领取每日听歌VIP  POST /youth/v1/recharge/receive_vip_listen_song
 * 2. 升级概念版VIP    POST /youth/v1/listen_song/upgrade_vip_reward
 *
 * 签名算法：MD5(salt + sorted_kv_string + body + salt)
 * 概念版 salt：LnT6xpN3khm36zse0QzvmgTZ3waWdRSA
 *
 * 注意：私有方法的命名与 1.4.0 Java 版保持一致（buildSignature / buildCommonParams /
 * buildCookie / buildUrl / parseClaimResponse），便于做新旧实现的行为对比验证。
 */
object KugouApi {

    private const val TAG = "[KuGouLiteVip]"

    // 概念版签名盐值
    private const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    // 网关地址
    private const val BASE_URL = "https://gateway.kugou.com"

    // 概念版固定参数
    private const val DEFAULT_APPID = "3116"
    private const val DEFAULT_CLIENTVER = "11440"
    private const val SOURCE_ID = "90139"

    // 请求头
    private const val USER_AGENT =
        "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    // 结果码
    const val CODE_SUCCESS = 0
    const val CODE_ALREADY = 1
    const val CODE_RISK = 2
    const val CODE_ERROR = -1

    // v1.5.0：网络超时收紧，失败早反馈，重试交给上层（2s/4s 退避）
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    /**
     * 登录凭证，从酷狗 OkHttp 请求中捕获
     */
    class Credentials {
        @JvmField
        var token: String? = null

        @JvmField
        var userid: String? = null

        @JvmField
        var dfid: String? = null

        @JvmField
        var mid: String? = null

        @JvmField
        var appid: String? = null

        @JvmField
        var clientver: String? = null

        fun isValid(): Boolean =
            !token.isNullOrEmpty() && !userid.isNullOrEmpty()
    }

    /**
     * 领取结果
     */
    class ClaimResult {
        @JvmField
        var code: Int = CODE_ERROR

        @JvmField
        var message: String? = null

        @JvmField
        var rawResponse: String? = null

        fun isSuccess(): Boolean = code == CODE_SUCCESS
    }

    /**
     * 获取今日日期（北京时间），格式 yyyy-MM-dd
     * 酷狗接口的 receive_day 使用北京时间日期
     */
    fun getTodayKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date())
    }

    /**
     * 获取本地日期（北京时间），格式 yyyy-MM-dd
     * 用于判断"今日是否已领取"，按用户所在时区的自然日计算
     */
    fun getLocalDayKey(): String = getTodayKey()

    /**
     * MD5 哈希
     */
    fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b.toInt() and 0xff))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 构建签名
     * 算法：MD5(salt + sorted_kv_string + body + salt)
     * sorted_kv_string: 将参数按 key 字母序排列，拼接为 key1=value1key2=value2...
     */
    private fun buildSignature(params: Map<String, String>, body: String): String {
        val keys = params.keys.sorted()
        val sb = StringBuilder()
        for (key in keys) {
            sb.append(key).append("=").append(params[key])
        }
        return md5(SALT + sb.toString() + body + SALT)
    }

    /**
     * 构建公共参数 Map（不含签名）
     */
    private fun buildCommonParams(creds: Credentials): Map<String, String> {
        val params = TreeMap<String, String>()
        params["appid"] = if (!creds.appid.isNullOrEmpty()) creds.appid!! else DEFAULT_APPID
        params["clientver"] =
            if (!creds.clientver.isNullOrEmpty()) creds.clientver!! else DEFAULT_CLIENTVER
        params["clienttime"] = (System.currentTimeMillis() / 1000).toString()
        params["mid"] = creds.mid ?: ""
        params["dfid"] = creds.dfid ?: ""
        params["uuid"] = "-"
        params["token"] = creds.token ?: ""
        params["userid"] = creds.userid ?: "0"
        return params
    }

    /**
     * 构建 Cookie 字符串
     */
    private fun buildCookie(params: Map<String, String>): String {
        val token = params["token"] ?: ""
        val userid = params["userid"] ?: ""
        val dfid = params["dfid"] ?: ""
        val mid = params["mid"] ?: ""
        return "token=$token; userid=$userid; dfid=$dfid; KUGOU_API_MID=$mid"
    }

    /**
     * 领取每日听歌VIP
     *
     * POST /youth/v1/recharge/receive_vip_listen_song
     * 参数: source_id=90139, receive_day=yyyy-MM-dd(北京时间) + 公共参数 + signature
     */
    fun claimVip(creds: Credentials?): ClaimResult {
        val result = ClaimResult()
        if (creds == null || !creds.isValid()) {
            result.code = CODE_ERROR
            result.message = "凭证无效"
            return result
        }

        val params = buildCommonParams(creds).toMutableMap()
        params["source_id"] = SOURCE_ID
        params["receive_day"] = getTodayKey()

        val body = "" // 该接口参数全部在 URL query 中，body 为空
        val signature = buildSignature(params, body)
        params["signature"] = signature

        val urlStr = buildUrl(BASE_URL + "/youth/v1/recharge/receive_vip_listen_song", params)
        val cookie = buildCookie(params)

        XposedBridge.log(TAG + " 领取VIP请求: " + maskUrl(urlStr))

        val response = doPost(urlStr, cookie, body)
        result.rawResponse = response
        parseClaimResponse(result, response)
        return result
    }

    /**
     * 升级概念版VIP（获得更高音质）
     *
     * POST /youth/v1/listen_song/upgrade_vip_reward
     * 参数: kugouid=userid, ad_type=1 + 公共参数 + signature
     */
    fun upgradeVip(creds: Credentials?): ClaimResult {
        val result = ClaimResult()
        if (creds == null || !creds.isValid()) {
            result.code = CODE_ERROR
            result.message = "凭证无效"
            return result
        }

        val params = buildCommonParams(creds).toMutableMap()
        params["kugouid"] = creds.userid ?: ""
        params["ad_type"] = "1"

        val body = ""
        val signature = buildSignature(params, body)
        params["signature"] = signature

        val urlStr = buildUrl(BASE_URL + "/youth/v1/listen_song/upgrade_vip_reward", params)
        val cookie = buildCookie(params)

        XposedBridge.log(TAG + " 升级VIP请求: " + maskUrl(urlStr))

        val response = doPost(urlStr, cookie, body)
        result.rawResponse = response

        if (response == null) {
            result.code = CODE_ERROR
            result.message = "升级请求失败"
            return result
        }

        try {
            val json = JSONObject(response)
            val status = json.optInt("status", 0)
            val errorCode = json.optInt("error_code", 0)
            val errorMsg = json.optString("error_msg", "")

            result.code = if (status == 1) {
                CODE_SUCCESS
            } else if (errorCode == 131001) {
                CODE_ALREADY
            } else {
                CODE_ERROR
            }
            result.message = when (result.code) {
                CODE_SUCCESS -> "升级概念版VIP成功"
                CODE_ALREADY -> "概念版VIP今日已升级"
                else -> if (errorMsg.isEmpty()) "升级失败，错误码: $errorCode" else errorMsg
            }
        } catch (e: Exception) {
            result.code = CODE_ERROR
            result.message = "解析升级响应失败"
        }
        return result
    }

    /**
     * 解析领取响应
     * status=1 → 成功
     * error_code=131001 → 今日已领取
     * error_code=20028 → 账号风控
     */
    private fun parseClaimResponse(result: ClaimResult, response: String?) {
        if (response.isNullOrEmpty()) {
            result.code = CODE_ERROR
            result.message = "网络请求失败，无响应"
            return
        }

        try {
            val json = JSONObject(response)
            val status = json.optInt("status", 0)
            val errorCode = json.optInt("error_code", 0)
            val errorMsg = json.optString("error_msg", "")

            XposedBridge.log(TAG + " 领取响应: status=$status error_code=$errorCode")

            result.code = when {
                status == 1 -> CODE_SUCCESS
                errorCode == 131001 -> CODE_ALREADY
                errorCode == 20028 -> CODE_RISK
                else -> CODE_ERROR
            }
            result.message = when (result.code) {
                CODE_SUCCESS -> "领取成功，获得1天畅听VIP"
                CODE_ALREADY -> "今日已领取"
                CODE_RISK -> "账号风控，请前往手机端领取"
                else -> if (errorMsg.isEmpty()) "领取失败，错误码: $errorCode" else "领取失败: $errorMsg"
            }
        } catch (e: Exception) {
            result.code = CODE_ERROR
            result.message = "解析响应失败: " + e.message
            XposedBridge.log(TAG + " 解析响应异常: $e")
        }
    }

    /**
     * 构建完整 URL（参数拼接为 query string）
     */
    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val sb = StringBuilder(baseUrl)
        sb.append("?")
        for ((key, value) in params) {
            sb.append(key).append("=").append(value).append("&")
        }
        var url = sb.toString()
        if (url.endsWith("&")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    /**
     * 执行 POST 请求
     */
    private fun doPost(urlStr: String, cookie: String, body: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doInput = true

            // 请求头
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("kg-rc", "1")
            conn.setRequestProperty("kg-thash", "5d816a0")
            conn.setRequestProperty("kg-rec", "1")
            conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")

            if (body.isNotEmpty()) {
                conn.doOutput = true
                val os: OutputStream = conn.outputStream
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
            }

            val responseCode = conn.responseCode
            XposedBridge.log(TAG + " HTTP响应码: $responseCode")

            val inputStream: InputStream? =
                if (responseCode in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: conn.inputStream
                }

            if (inputStream == null) return null

            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            reader.use { r ->
                r.forEachLine { line -> sb.append(line) }
            }
            return sb.toString()
        } catch (e: Exception) {
            XposedBridge.log(TAG + " 请求异常: $e")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 遮蔽 URL 中的敏感信息（token），用于日志输出
     */
    private fun maskUrl(url: String?): String {
        if (url == null) return ""
        return url.replace(Regex("token=[^&]+"), "token=***")
    }
}
