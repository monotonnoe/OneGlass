package com.monotonnoe.oneglass.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.engine.DockGlass;
import com.monotonnoe.oneglass.glass.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

/**
 * OneGlass' own One UI Home hooks, next to the glass engine: the iOS 27 dock platter (drawn by
 * the engine's glass, see {@link DockGlass}) and the optional iOS 27 grid ({@link IosGrid}).
 * Icons, folders, widgets, the search pill and everything on the lock screen are the engine's.
 */
public final class LauncherHooks {

    static final String HOTSEAT = "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatCellLayout";
    static final String PAGE = "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayout";
    static final String ICON = "com.honeyspace.ui.common.iconview.IconViewImpl";

    private static final int K_NONE = 0;
    private static final int K_HOTSEAT = 1;
    private static final int K_PAGE = 2;
    private static final int K_ICON = 3;
    private static final int K_INDICATOR = 4;

    private static final Map<Class<?>, Integer> KINDS = new HashMap<>();
    private static final Set<View> DONE = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static XSharedPreferences prefs;
    private static boolean started;
    private static boolean dumped;

    private LauncherHooks() {
    }

    public static void install(ClassLoader cl) {
        loadConfig();
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    onAttached((View) param.thisObject);
                } catch (Throwable t) {
                    Log.e("attach", t);
                }
            }
        });
        IosLabels.install(XposedHelpers.findClassIfExists(ICON, cl));
        Log.i("OneGlass dock / grid hooks installed; hotseat class "
                + (XposedHelpers.findClassIfExists(HOTSEAT, cl) != null ? "found" : "MISSING"));
    }

    private static void loadConfig() {
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(Config.class.getPackage().getName(), Config.PREFS);
            } else {
                prefs.reload();
            }
            Config c = Config.read(prefs);
            c.generation = Config.current.generation + 1;
            Config.current = c;
        } catch (Throwable t) {
            Log.e("settings", t);
        }
    }

    private static void start(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    loadConfig();
                    Log.i("OneGlass dock / grid settings reloaded");
                }
            }, new IntentFilter(Config.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.e("reload receiver", t);
        }
    }

    private static void onAttached(View v) {
        if (!started) {
            started = true;
            start(v.getContext());
        }
        if (!dumped && Config.current.diag && v.getParent() != null && !(v.getParent() instanceof View)) {
            dumped = true;
            final View root = v;
            MAIN.postDelayed(() -> Diagnostics.dump(root), 4000);
        }
        if (!com.monotonnoe.oneglass.engine.SearchPillGlass.suppressed
                && v.getClass().getName().startsWith("dev.flexy.searchpill.")) {
            // the SearchPill module already shows a search pill: no second one
            com.monotonnoe.oneglass.engine.SearchPillGlass.suppressed = true;
            Log.i("SearchPill module detected: OneGlass' own search pill is off");
        }
        int kind = kindOf(v.getClass());
        if (kind == K_NONE) {
            return;
        }
        if (kind == K_ICON) {
            IosLabels.apply(v); // every attach: icons are recycled between pages
            return;
        }
        if (!DONE.add(v)) {
            return;
        }
        switch (kind) {
            case K_HOTSEAT:
                IosGrid.hookLayout(v.getClass(), true);
                DockGlass.attach(v);
                if (Config.current.iosGrid) v.requestLayout();
                break;
            case K_PAGE:
                IosGrid.hookLayout(v.getClass(), false);
                if (Config.current.iosGrid) v.requestLayout();
                break;
            case K_INDICATOR:
                if (!com.monotonnoe.oneglass.glass.HomeScope.inAppsScreen(v)) {
                    com.monotonnoe.oneglass.engine.SearchPillGlass.attach(v);
                }
                break;
            default:
                break;
        }
    }

    private static int kindOf(Class<?> cls) {
        Integer k = KINDS.get(cls);
        if (k != null) {
            return k;
        }
        int kind = K_NONE;
        for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals(HOTSEAT) || n.endsWith(".HotseatCellLayout")) {
                kind = K_HOTSEAT;
            } else if (n.equals(PAGE) || n.endsWith(".WorkspaceCellLayout")) {
                kind = K_PAGE;
            } else if (n.equals(ICON)) {
                kind = K_ICON;
            } else if (n.startsWith("com.honeyspace.") && c.getSimpleName().equals("PageIndicatorView")) {
                kind = K_INDICATOR;
            }
            if (kind != K_NONE) {
                break;
            }
        }
        KINDS.put(cls, kind);
        return kind;
    }
}
