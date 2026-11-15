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

    private static final int K_NONE = 0;
    private static final int K_HOTSEAT = 1;
    private static final int K_PAGE = 2;

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
        int kind = kindOf(v.getClass());
        if (kind == K_NONE || !DONE.add(v)) {
            return;
        }
        if (kind == K_HOTSEAT) {
            IosGrid.hookLayout(v.getClass(), true);
            DockGlass.attach(v);
        } else {
            IosGrid.hookLayout(v.getClass(), false);
        }
        if (Config.current.iosGrid) {
            v.requestLayout();
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
            }
            if (kind != K_NONE) {
                break;
            }
        }
        KINDS.put(cls, kind);
        return kind;
    }
}
