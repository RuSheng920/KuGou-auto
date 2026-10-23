import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 签名/URL/解析逻辑的新旧实现逐位对比（1.4.0 Java vs 1.5.0 Kotlin）。
 *
 * 对比项：
 *  1. buildSignature      — 签名算法（错了就是静默鉴权失败，最高优先级）
 *  2. buildCommonParams   — 公共参数构造（含 null 分支）
 *  3. buildCookie         — Cookie 拼接
 *  4. buildUrl            — URL 拼接
 *  5. parseClaimResponse  — 领取响应解析（成功/已领/风控/未知/坏 JSON/空响应）
 *  6. getTodayKey         — 北京时间日期
 *  7. md5                 — 哈希
 */
public class Harness {

    private static int pass = 0;
    private static int fail = 0;

    private static final String OLD = "oldimpl.KugouApi";
    private static final String NEW = "com.moekoe.xposed.kugoulite.KugouApi";

    public static void main(String[] args) throws Exception {
        Class<?> oldApi = Class.forName(OLD);
        Class<?> newApi = Class.forName(NEW);
        Object newInst = newApi.getField("INSTANCE").get(null);

        // ---------- 1. buildSignature ----------
        Map<String, String> p1 = baseParams();
        check("buildSignature/普通参数",
                call(oldApi, null, "buildSignature", p1, ""),
                call(newApi, newInst, "buildSignature", p1, ""));

        Map<String, String> p2 = baseParams();
        p2.put("source_id", "90139");
        p2.put("receive_day", "2026-10-23");
        check("buildSignature/含业务参数",
                call(oldApi, null, "buildSignature", p2, ""),
                call(newApi, newInst, "buildSignature", p2, ""));

        Map<String, String> p3 = baseParams();
        p3.put("kugouid", "998877");
        p3.put("ad_type", "1");
        check("buildSignature/升级接口参数",
                call(oldApi, null, "buildSignature", p3, ""),
                call(newApi, newInst, "buildSignature", p3, ""));

        Map<String, String> p4 = baseParams();
        p4.put("token", "a+b/c=d=e&f g");
        p4.put("dfid", "中文dfid值");
        check("buildSignature/特殊字符",
                call(oldApi, null, "buildSignature", p4, ""),
                call(newApi, newInst, "buildSignature", p4, ""));

        Map<String, String> p5 = baseParams();
        p5.put("signature", "preset-abc"); // 已存在 signature 键，验证排序稳定性
        check("buildSignature/已含signature键",
                call(oldApi, null, "buildSignature", p5, "body-content"),
                call(newApi, newInst, "buildSignature", p5, "body-content"));

        check("buildSignature/空Map",
                call(oldApi, null, "buildSignature", new TreeMap<String, String>(), ""),
                call(newApi, newInst, "buildSignature", new TreeMap<String, String>(), ""));

        // ---------- 2. buildCommonParams ----------
        Object oldCredsFull = newCreds(oldApi, "tok123", "998877", "dfid-1", "mid-1", "3116", "11440");
        Object newCredsFull = newCreds(newApi, "tok123", "998877", "dfid-1", "mid-1", "3116", "11440");
        check("buildCommonParams/全量凭证",
                stripClienttime(call(oldApi, null, "buildCommonParams", oldCredsFull)),
                stripClienttime(call(newApi, newInst, "buildCommonParams", newCredsFull)));

        Object oldCredsNull = newCreds(oldApi, "tok123", "998877", null, null, null, null);
        Object newCredsNull = newCreds(newApi, "tok123", "998877", null, null, null, null);
        check("buildCommonParams/null字段走默认值",
                stripClienttime(call(oldApi, null, "buildCommonParams", oldCredsNull)),
                stripClienttime(call(newApi, newInst, "buildCommonParams", newCredsNull)));

        // ---------- 3. buildCookie ----------
        check("buildCookie", call(oldApi, null, "buildCookie", p1), call(newApi, newInst, "buildCookie", p1));
        check("buildCookie/含特殊字符", call(oldApi, null, "buildCookie", p4), call(newApi, newInst, "buildCookie", p4));

        // ---------- 4. buildUrl ----------
        check("buildUrl/领取接口",
                call(oldApi, null, "buildUrl", "https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song", p2),
                call(newApi, newInst, "buildUrl", "https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song", p2));
        check("buildUrl/空参数",
                call(oldApi, null, "buildUrl", "https://x.y/z", new TreeMap<String, String>()),
                call(newApi, newInst, "buildUrl", "https://x.y/z", new TreeMap<String, String>()));

        // ---------- 5. parseClaimResponse ----------
        String[] responses = {
                "{\"status\":1,\"error_code\":0,\"error_msg\":\"\"}",
                "{\"status\":0,\"error_code\":131001,\"error_msg\":\"今日已领取\"}",
                "{\"status\":0,\"error_code\":20028,\"error_msg\":\"账号风控\"}",
                "{\"status\":0,\"error_code\":999,\"error_msg\":\"未知错误\"}",
                "{\"status\":0,\"error_code\":999}",
                "{\"status\":1}",
                "这不是JSON",
                "",
        };
        for (int i = 0; i < responses.length; i++) {
            Object oldR = newResult(oldApi);
            Object newR = newResult(newApi);
            call(oldApi, null, "parseClaimResponse", oldR, responses[i]);
            call(newApi, newInst, "parseClaimResponse", newR, responses[i]);
            check("parseClaimResponse/用例" + i,
                    readResult(oldApi, oldR), readResult(newApi, newR));
        }

        // ---------- 6. getTodayKey / md5 ----------
        check("getTodayKey",
                call(oldApi, null, "getTodayKey"),
                call(newApi, newInst, "getTodayKey"));
        check("md5", call(oldApi, null, "md5", "hello"), call(newApi, newInst, "md5", "hello"));
        check("md5/空串", call(oldApi, null, "md5", ""), call(newApi, newInst, "md5", ""));

        System.out.println();
        System.out.println("========== 结果: " + pass + " 通过, " + fail + " 失败 ==========");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 工具

    private static Map<String, String> baseParams() {
        Map<String, String> m = new TreeMap<String, String>();
        m.put("appid", "3116");
        m.put("clientver", "11440");
        m.put("clienttime", "1792724459");
        m.put("mid", "mid-abc123");
        m.put("dfid", "dfid-xyz789");
        m.put("uuid", "-");
        m.put("token", "tok-abc123");
        m.put("userid", "998877");
        return m;
    }

    /** clienttime 每次调用都取当前时间，对比时剔除 */
    private static String stripClienttime(Object map) {
        if (!(map instanceof Map)) return String.valueOf(map);
        Map<?, ?> m = (Map<?, ?>) map;
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if ("clienttime".equals(e.getKey())) continue;
            sb.append(e.getKey()).append("=").append(e.getValue()).append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    private static Object newCreds(Class<?> api, String token, String userid, String dfid,
                                   String mid, String appid, String clientver) throws Exception {
        Class<?> c = Class.forName(api.getName() + "$Credentials");
        Object o = c.getDeclaredConstructor().newInstance();
        set(o, "token", token);
        set(o, "userid", userid);
        set(o, "dfid", dfid);
        set(o, "mid", mid);
        set(o, "appid", appid);
        set(o, "clientver", clientver);
        return o;
    }

    private static Object newResult(Class<?> api) throws Exception {
        return Class.forName(api.getName() + "$ClaimResult").getDeclaredConstructor().newInstance();
    }

    private static String readResult(Class<?> api, Object result) throws Exception {
        Field codeF = result.getClass().getField("code");
        Field msgF = result.getClass().getField("message");
        return "code=" + codeF.get(result) + ", message=" + msgF.get(result);
    }

    private static void set(Object o, String field, String value) throws Exception {
        Field f = o.getClass().getField(field);
        f.set(o, value);
    }

    private static Object call(Class<?> api, Object inst, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = argClass(args[i]);
        }
        Method m = api.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(inst, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException());
        }
    }

    private static Class<?> argClass(Object arg) {
        if (arg instanceof Map) return Map.class;
        if (arg instanceof String) return String.class;
        return arg.getClass();
    }

    private static void check(String name, Object oldVal, Object newVal) {
        boolean ok = oldVal == null ? newVal == null : oldVal.equals(newVal);
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + " => " + oldVal);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
            System.out.println("   旧(Java): " + oldVal);
            System.out.println("   新(Kotlin): " + newVal);
        }
    }
}
