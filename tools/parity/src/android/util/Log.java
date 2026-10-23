package android.util;

/** JVM 测试用 Log 桩：android.jar 里的 Log 是抛异常的 stub，这里换成静默实现 */
public final class Log {
    private Log() {
    }

    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
}
