package com.monotonnoe.oneglass.engine;

import android.app.KeyguardManager;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * The fingerprint sensor's icon hidden on the lock screen, where the notifications now sit
 * over it (a setting). One UI draws it from its biometrics app, in a window of that app, not
 * in System UI: the windows that app adds are logged once each (with {@link Config#DEBUG}:
 * the app starts on every unlock), and those that are the
 * icon's (by their title or their view's class) are drawn transparent while the phone is
 * locked. Only drawn transparent: the sensor and its touch window work as before. The
 * biometrics app's process.
 */
final class FingerprintIcon {
    static final String PACKAGE = "com.samsung.android.biometrics.app.setting";
    private static final Set<String> LOGGED = new HashSet<>();
    private static boolean wanted = true;
    private static long readAt;

    private FingerprintIcon() {
    }

    static void install(ClassLoader cl) throws ClassNotFoundException {
        Class<?> global = Class.forName("android.view.WindowManagerGlobal", false, cl);
        Set<?> h = XposedBridge.hookAllMethods(global, "addView",
                SafeHook.after("fingerprint window", p -> {
                    if (p.args.length < 2 || !(p.args[0] instanceof View)
                            || !(p.args[1] instanceof WindowManager.LayoutParams)) {
                        return;
                    }
                    onWindow((View) p.args[0], (WindowManager.LayoutParams) p.args[1]);
                }));
        // The biometrics app starts again on every unlock: only a failure is worth a line.
        int n = h == null ? 0 : h.size();
        if (n == 0) {
            XLog.i("fingerprint icon: no window hooks");
        } else {
            XLog.debug("fingerprint icon: window hooks " + n);
        }
    }

    private static void onWindow(View root, WindowManager.LayoutParams lp) {
        String title = String.valueOf(lp.getTitle());
        String cls = root.getClass().getName();
        boolean icon = isIcon(title) || isIcon(cls) || hasIconChild(root, 0);
        String key = title + "|" + cls;
        if (Config.DEBUG && LOGGED.add(key)) {
            XLog.debug("fingerprint window \"" + title + "\" type " + lp.type + " " + lp.width + "x"
                    + lp.height + " flags 0x" + Integer.toHexString(lp.flags) + ", view " + cls
                    + (icon ? ": the icon, hidden on the lock screen" : ""));
        }
        if (!icon) return;
        float shown = root.getAlpha() > 0f ? root.getAlpha() : 1f;
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            float want = hide(root.getContext()) ? 0f : shown;
            if (root.getAlpha() != want) root.setAlpha(want);
            return true;
        });
    }

    private static boolean isIcon(String s) {
        String l = s.toLowerCase(java.util.Locale.ROOT);
        return l.contains("icon") && !l.contains("touch");
    }

    private static boolean hasIconChild(View v, int depth) {
        if (depth > 3 || !(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (isIcon(c.getClass().getSimpleName()) || hasIconChild(c, depth + 1)) return true;
        }
        return false;
    }

    /** Locked, and the setting on (asked of the module at most every 5 s). */
    private static boolean hide(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - readAt > 5000) {
            readAt = now;
            String key = Settings.GLASS_KEYS[Settings.LOCK_HIDE_FP_ICON];
            try (Cursor q = c.getContentResolver().query(Settings.URI, null, null, null, null)) {
                while (q != null && q.moveToNext()) {
                    if (key.equals(q.getString(0))) wanted = q.getInt(1) != 0;
                }
            } catch (Throwable t) {
                // The module not reachable: as set last.
            }
        }
        return wanted;
    }
}
