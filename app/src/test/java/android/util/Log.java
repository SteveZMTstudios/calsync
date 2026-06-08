package android.util;

public final class Log {
    private Log() {}

    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { System.out.println(tag + " " + msg); return 0; }
    public static int e(String tag, String msg, Throwable tr) { System.out.println(tag + " " + msg); tr.printStackTrace(); return 0; }
    public static int v(String tag, String msg) { return 0; }
    public static String getStackTraceString(Throwable tr) { return tr == null ? "" : tr.toString(); }
}
