package oldimpl;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import com.moekoe.xposed.kugoulite.compat.XposedBridge;

/**
 * 酷狗概念版 API 封装
 *
 * 核心接口：
 * 1. 领取每日听歌VIP  POST /youth/v1/recharge/receive_vip_listen_song
 * 2. 升级概念版VIP    POST /youth/v1/listen_song/upgrade_vip_reward
 *
 * 签名算法：MD5(salt + sorted_kv_string + body + salt)
 * 概念版 salt：LnT6xpN3khm36zse0QzvmgTZ3waWdRSA
 */
public class KugouApi {

    private static final String TAG = "[KuGouLiteVip]";

    // 概念版签名盐值
    private static final String SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

    // 网关地址
    private static final String BASE_URL = "https://gateway.kugou.com";

    // 概念版固定参数
    private static final String DEFAULT_APPID = "3116";
    private static final String DEFAULT_CLIENTVER = "11440";
    private static final String SOURCE_ID = "90139";

    // 请求头
    private static final String USER_AGENT =
            "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    // 结果码
    public static final int CODE_SUCCESS = 0;
    public static final int CODE_ALREADY = 1;
    public static final int CODE_RISK = 2;
    public static final int CODE_ERROR = -1;

    /**
     * 登录凭证，从酷狗 OkHttp 请求中捕获
     */
    public static class Credentials {
        public String token;
        public String userid;
        public String dfid;
        public String mid;
        public String appid;
        public String clientver;

        public boolean isValid() {
            return token != null && !token.isEmpty()
                    && userid != null && !userid.isEmpty();
        }
    }

    /**
     * 领取结果
     */
    public static class ClaimResult {
        public int code;
        public String message;
        public String rawResponse;

        public boolean isSuccess() {
            return code == CODE_SUCCESS;
        }
    }

    /**
     * 获取今日日期（北京时间），格式 yyyy-MM-dd
     * 酷狗接口的 receive_day 使用北京时间日期
     */
    public static String getTodayKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new Date());
    }

    /**
     * 获取本地日期（北京时间），格式 yyyy-MM-dd
     * 用于判断"今日是否已领取"，按用户所在时区的自然日计算
     */
    public static String getLocalDayKey() {
        return getTodayKey();
    }

    /**
     * MD5 哈希
     */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 构建签名
     * 算法：MD5(salt + sorted_kv_string + body + salt)
     * sorted_kv_string: 将参数按 key 字母序排列，拼接为 key1=value1key2=value2...
     */
    private static String buildSignature(Map<String, String> params, String body) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append("=").append(params.get(key));
        }
        return md5(SALT + sb.toString() + body + SALT);
    }

    /**
     * 构建公共参数 Map（不含签名）
     */
    private static Map<String, String> buildCommonParams(Credentials creds) {
        Map<String, String> params = new TreeMap<>();
        params.put("appid", creds.appid != null && !creds.appid.isEmpty()
                ? creds.appid : DEFAULT_APPID);
        params.put("clientver", creds.clientver != null && !creds.clientver.isEmpty()
                ? creds.clientver : DEFAULT_CLIENTVER);
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("mid", creds.mid != null ? creds.mid : "");
        params.put("dfid", creds.dfid != null ? creds.dfid : "");
        params.put("uuid", "-");
        params.put("token", creds.token != null ? creds.token : "");
        params.put("userid", creds.userid != null ? creds.userid : "0");
        return params;
    }

    /**
     * 构建 Cookie 字符串
     */
    private static String buildCookie(Map<String, String> params) {
        return "token=" + params.get("token")
                + "; userid=" + params.get("userid")
                + "; dfid=" + params.get("dfid")
                + "; KUGOU_API_MID=" + params.get("mid");
    }

    /**
     * 领取每日听歌VIP
     *
     * POST /youth/v1/recharge/receive_vip_listen_song
     * 参数: source_id=90139, receive_day=yyyy-MM-dd(UTC) + 公共参数 + signature
     */
    public static ClaimResult claimVip(Credentials creds) {
        ClaimResult result = new ClaimResult();
        if (creds == null || !creds.isValid()) {
            result.code = CODE_ERROR;
            result.message = "凭证无效";
            return result;
        }

        Map<String, String> params = buildCommonParams(creds);
        params.put("source_id", SOURCE_ID);
        params.put("receive_day", getTodayKey());

        String body = ""; // 该接口参数全部在 URL query 中，body 为空
        String signature = buildSignature(params, body);
        params.put("signature", signature);

        String urlStr = buildUrl(BASE_URL + "/youth/v1/recharge/receive_vip_listen_song", params);
        String cookie = buildCookie(params);

        XposedBridge.log(TAG + " 领取VIP请求: " + maskUrl(urlStr));

        String response = doPost(urlStr, cookie, body);
        result.rawResponse = response;
        parseClaimResponse(result, response);
        return result;
    }

    /**
     * 升级概念版VIP（获得更高音质）
     *
     * POST /youth/v1/listen_song/upgrade_vip_reward
     * 参数: kugouid=userid, ad_type=1 + 公共参数 + signature
     */
    public static ClaimResult upgradeVip(Credentials creds) {
        ClaimResult result = new ClaimResult();
        if (creds == null || !creds.isValid()) {
            result.code = CODE_ERROR;
            result.message = "凭证无效";
            return result;
        }

        Map<String, String> params = buildCommonParams(creds);
        params.put("kugouid", creds.userid);
        params.put("ad_type", "1");

        String body = "";
        String signature = buildSignature(params, body);
        params.put("signature", signature);

        String urlStr = buildUrl(BASE_URL + "/youth/v1/listen_song/upgrade_vip_reward", params);
        String cookie = buildCookie(params);

        XposedBridge.log(TAG + " 升级VIP请求: " + maskUrl(urlStr));

        String response = doPost(urlStr, cookie, body);
        result.rawResponse = response;

        if (response == null) {
            result.code = CODE_ERROR;
            result.message = "升级请求失败";
            return result;
        }

        try {
            JSONObject json = new JSONObject(response);
            int status = json.optInt("status", 0);
            int errorCode = json.optInt("error_code", 0);
            String errorMsg = json.optString("error_msg", "");

            if (status == 1) {
                result.code = CODE_SUCCESS;
                result.message = "升级概念版VIP成功";
            } else {
                result.code = CODE_ERROR;
                result.message = errorMsg.isEmpty() ? ("升级失败，错误码: " + errorCode) : errorMsg;
            }
        } catch (Exception e) {
            result.code = CODE_ERROR;
            result.message = "解析升级响应失败";
        }
        return result;
    }

    /**
     * 解析领取响应
     * status=1 → 成功
     * error_code=131001 → 今日已领取
     * error_code=20028 → 账号风控
     */
    private static void parseClaimResponse(ClaimResult result, String response) {
        if (response == null || response.isEmpty()) {
            result.code = CODE_ERROR;
            result.message = "网络请求失败，无响应";
            return;
        }

        try {
            JSONObject json = new JSONObject(response);
            int status = json.optInt("status", 0);
            int errorCode = json.optInt("error_code", 0);
            String errorMsg = json.optString("error_msg", "");

            XposedBridge.log(TAG + " 领取响应: status=" + status + " error_code=" + errorCode);

            if (status == 1) {
                result.code = CODE_SUCCESS;
                result.message = "领取成功，获得1天畅听VIP";
            } else if (errorCode == 131001) {
                result.code = CODE_ALREADY;
                result.message = "今日已领取";
            } else if (errorCode == 20028) {
                result.code = CODE_RISK;
                result.message = "账号风控，请前往手机端领取";
            } else {
                result.code = CODE_ERROR;
                result.message = errorMsg.isEmpty()
                        ? ("领取失败，错误码: " + errorCode)
                        : ("领取失败: " + errorMsg);
            }
        } catch (Exception e) {
            result.code = CODE_ERROR;
            result.message = "解析响应失败: " + e.getMessage();
            XposedBridge.log(TAG + " 解析响应异常: " + e);
        }
    }

    /**
     * 构建完整 URL（参数拼接为 query string）
     */
    private static String buildUrl(String baseUrl, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }
        String url = sb.toString();
        if (url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 执行 POST 请求
     */
    private static String doPost(String urlStr, String cookie, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoInput(true);

            // 请求头
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("kg-rc", "1");
            conn.setRequestProperty("kg-thash", "5d816a0");
            conn.setRequestProperty("kg-rec", "1");
            conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }

            int responseCode = conn.getResponseCode();
            XposedBridge.log(TAG + " HTTP响应码: " + responseCode);

            InputStream is;
            if (responseCode >= 200 && responseCode < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) {
                    is = conn.getInputStream();
                }
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            XposedBridge.log(TAG + " 请求异常: " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 遮蔽 URL 中的敏感信息（token），用于日志输出
     */
    private static String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("token=[^&]+", "token=***");
    }
}
