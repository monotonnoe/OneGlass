package com.monotonnoe.oneglass.glass;

/** Logs to the LSPosed log inside the launcher, to logcat inside the module app. */
public final class Log {

    private static final String TAG = "OneGlass";
    private static Boolean xposed;

    private Log() {
    }

    private static boolean xposed() {
        if (xposed == null) {
            boolean ok;
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            xposed = ok;
        }
        return xposed;
    }

    public static void i(String msg) {
        if (xposed()) {
            try {
                de.robv.android.xposed.XposedBridge.log(TAG + ": " + msg);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        android.util.Log.i(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        if (xposed()) {
            try {
                de.robv.android.xposed.XposedBridge.log(TAG + ": " + msg + ": " + t);
                de.robv.android.xposed.XposedBridge.log(t);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        android.util.Log.e(TAG, msg, t);
    }
}
