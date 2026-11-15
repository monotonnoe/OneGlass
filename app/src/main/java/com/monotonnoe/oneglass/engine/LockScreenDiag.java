package com.monotonnoe.oneglass.engine;

import android.app.Application;
import android.app.Instrumentation;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lock screen diagnostics, in System UI: finds the views that may draw the lock screen clock
 * (classes named like a clock or One UI's face widgets, TextClocks, TextViews showing a time or
 * very large text) in the windows System UI adds, lists those windows, and checks whether the
 * lock screen wallpaper can be read. Changes nothing on screen; the findings go to the LSPosed
 * log and to the module's settings screen ({@link Settings#GLOBAL_LOCK_STATUS}), to build the
 * liquid glass clock on.
 */
final class LockScreenDiag {
    static final String SYSTEMUI = "com.android.systemui";
    /** Clock candidates kept, by window and class. */
    private static final int MAX_FOUND = 20;
    private static final int MAX_DEPTH = 40;

    private static final Map<String, String> FOUND = new LinkedHashMap<>();
    private static final Map<View, String> ROOTS = new WeakHashMap<>();
    private static final ExecutorService REPORTS = Executors.newSingleThreadExecutor();
    private static Context app;
    private static String wallpaper = "not checked";
    private static boolean installed;
    private static long lastScan;
    private static int scans;
    private static final int MAX_SCANS = 30;
    private static String lastLogged;

    private LockScreenDiag() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;
        XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate",
                SafeHook.after("callApplicationOnCreate", p -> {
                    if (p.args.length > 0 && p.args[0] instanceof Application && app == null) {
                        app = (Application) p.args[0];
                        // File access stays off System UI's main thread.
                        REPORTS.execute(() -> {
                            checkWallpaper();
                            new Handler(Looper.getMainLooper()).post(LockScreenDiag::report);
                        });
                    }
                }));
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal", false,
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(wmg, "addView", SafeHook.after("addView", p -> {
                if (p.args.length > 1 && p.args[0] instanceof View) {
                    String title = p.args[1] instanceof WindowManager.LayoutParams
                            ? String.valueOf(((WindowManager.LayoutParams) p.args[1]).getTitle())
                            : "?";
                    watch((View) p.args[0], title);
                }
            }));
        } catch (Throwable t) {
            XLog.e("lock screen diag", t);
        }
        XLog.i("lock screen diag installed in " + lpparam.processName);
    }

    /** Scans a System UI window now and whenever its layout changes (the keyguard comes later). */
    private static void watch(View root, String title) {
        // Windows that come and go with every app launch or message are not the lock screen:
        // nothing of System UI's time at those moments.
        if (title.startsWith("Splash Screen") || title.startsWith("Toast")
                || title.startsWith("BezelPop")) {
            return;
        }
        ROOTS.put(root, title);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            long now = System.currentTimeMillis();
                            if (now - lastScan < 1000) return;
                            // Every window of System UI, at most once a second: only for
                            // the first scans after start, which is all the report needs.
                            if (++scans > MAX_SCANS) return;
                            lastScan = now;
                            for (Map.Entry<View, String> e : ROOTS.entrySet()) {
                                scan(e.getKey(), e.getValue(), 0);
                            }
                        } catch (Throwable t) {
                            XLog.e("lock screen scan", t);
                        }
                    }
                });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                scan(root, title, 0);
            } catch (Throwable t) {
                XLog.e("lock screen scan", t);
            }
        }, 1500);
    }

    /** Class name parts One UI's lock screen clock may go by. */
    private static final String[] CLOCK_NAMES = {"clock", "facewidget", "keyguardstatus",
            "lockstar"};

    /** A clock by class, or a TextView showing a time or very large text (the digits). */
    private static boolean clockLike(View v) {
        if (v instanceof TextClock) return true;
        String name = v.getClass().getName().toLowerCase(Locale.ROOT);
        for (String part : CLOCK_NAMES) if (name.contains(part)) return true;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String text = String.valueOf(t.getText()).trim();
            return text.matches("\\d{1,2}\\s*[:.\\s]\\s*\\d{2}.*") || t.getTextSize() >= 60f;
        }
        return false;
    }

    private static void scan(View v, String window, int depth) {
        if (depth > MAX_DEPTH || FOUND.size() >= MAX_FOUND) return;
        String name = v.getClass().getName();
        if (clockLike(v)) {
            String key = window + "|" + name;
            if (!FOUND.containsKey(key) && v.getWidth() > 0) {
                FOUND.put(key, describe(v, window));
                report();
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scan(g.getChildAt(i), window, depth + 1);
        }
    }

    private static String describe(View v, String window) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        StringBuilder b = new StringBuilder();
        b.append(v.getClass().getName()).append(" in \"").append(window).append("\": ")
                .append(v.getWidth()).append('x').append(v.getHeight()).append(" at ")
                .append(at[0]).append(',').append(at[1])
                .append(v.getVisibility() == View.VISIBLE ? "" : " hidden")
                .append(" alpha ").append(v.getAlpha());
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface f = t.getTypeface();
            b.append(", text \"").append(t.getText()).append("\" ")
                    .append(Math.round(t.getTextSize())).append("px")
                    .append(f != null ? " weight " + f.getWeight() : "");
        }
        if (v instanceof ViewGroup) b.append(", ").append(((ViewGroup) v).getChildCount())
                .append(" children");
        b.append(", parents:");
        ViewParent p = v.getParent();
        for (int i = 0; i < 3 && p != null; i++, p = p.getParent()) {
            b.append(' ').append(p.getClass().getSimpleName());
        }
        return b.toString();
    }

    private static void checkWallpaper() {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(app);
            if (wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK) != null) {
                wallpaper = "live";
                return;
            }
            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)) {
                if (pfd != null) {
                    wallpaper = "readable (lock file)";
                    return;
                }
            }
            Drawable d = wm.getDrawable(WallpaperManager.FLAG_LOCK);
            wallpaper = d instanceof BitmapDrawable ? "readable (same as home)"
                    : "unreadable: " + d;
            d = null;
            // A diagnostic string only: do not leave the full-size decode cached.
            wm.forgetLoadedWallpaper();
        } catch (Throwable t) {
            wallpaper = "unreadable: " + t;
        }
    }

    static void report() {
        Context ctx = app;
        if (ctx == null) return;
        StringBuilder b = new StringBuilder("[").append(Config.VERSION)
                .append("] glass clock: ").append(LockGlass.state)
                .append("; lock wallpaper ").append(wallpaper).append("; windows: ");
        for (String w : new java.util.LinkedHashSet<>(ROOTS.values())) b.append(w).append(", ");
        if (FOUND.isEmpty()) {
            b.append("no clock found yet (lock the screen once)");
        } else {
            b.append(FOUND.size()).append(" clock views:");
            int i = 1;
            for (String s : FOUND.values()) b.append("\n").append(i++).append(". ").append(s);
        }
        String status = b.toString();
        // The list of windows and views and the draw counts only with Config.DEBUG, when they
        // changed: written on every lock, they filled the LSPosed log (megabytes a day). The
        // settings screen shows them all. Otherwise one line per state of the glass clock.
        if (Config.DEBUG) {
            if (!status.equals(lastLogged)) {
                lastLogged = status;
                XLog.debug("lock screen: " + status);
            }
        } else {
            XLog.i("lock screen: [" + Config.VERSION + "] glass clock: "
                    + LockGlass.state.replaceAll(" \\[draws.*", "") + "; lock wallpaper "
                    + wallpaper);
        }
        REPORTS.execute(() -> {
            // System UI cannot see the module's provider (package visibility), but it may write
            // global settings, which every app can read.
            try {
                android.provider.Settings.Global.putString(ctx.getContentResolver(),
                        Settings.GLOBAL_LOCK_STATUS, status);
            } catch (Throwable t) {
                XLog.e("lock status report", t);
            }
        });
    }
}
