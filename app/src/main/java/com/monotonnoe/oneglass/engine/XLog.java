package com.monotonnoe.oneglass.engine;

import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

public final class XLog {
    private static final String TAG = "OneGlass";

    /**
     * The lines logged lately: one written again (the same status, the same error each frame)
     * is not logged twice. The oldest are forgotten past {@link #REMEMBERED}.
     */
    private static final int REMEMBERED = 256;
    private static final Map<String, Boolean> SEEN =
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            };

    private XLog() {
    }

    /** True the first time this line is seen (lately). */
    private static boolean first(String line) {
        synchronized (SEEN) {
            return SEEN.put(line, Boolean.TRUE) == null;
        }
    }

    /** An install, summary or state line; a line already logged is not logged again. */
    public static void i(String msg) {
        if (!first(msg)) return;
        write(msg);
    }

    /**
     * A diagnostic line (per event, per frame, per window): only with {@link Config#DEBUG}.
     * Callers building the line at a cost check {@code Config.DEBUG} first.
     */
    public static void debug(String msg) {
        if (!Config.DEBUG) return;
        write(msg);
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        String trace = Log.getStackTraceString(t);
        // The same failure again (a hook failing on every draw): logged once.
        if (!first(msg + "\n" + trace)) return;
        Log.e(TAG, msg, t);
        try {
            XposedBridge.log(TAG + ": " + msg + ": " + trace);
        } catch (Throwable ignored) {
            // Not running under Xposed.
        }
    }

    private static void write(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // Not running under Xposed.
        }
    }
}
