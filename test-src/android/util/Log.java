package android.util;

public final class Log {
    public static int i(String tag, String message) {
        System.out.println(tag + ": " + message);
        return 0;
    }

    public static int e(String tag, String message) {
        System.err.println(tag + ": " + message);
        return 0;
    }

    public static int w(String tag, String message) {
        System.out.println(tag + ": " + message);
        return 0;
    }

    public static int d(String tag, String message) {
        return 0;
    }
}
