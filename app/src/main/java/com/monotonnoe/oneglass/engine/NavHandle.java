package com.monotonnoe.oneglass.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.view.View;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * The gesture bar (the navigation handle) hidden on the Home screen, as iOS shows none there.
 * One UI Home tells System UI when its own screen comes in front and goes ({@link #send});
 * System UI clips the handle's views to nothing meanwhile (a clip, which One UI never sets on
 * them, so its own alpha and visibility changes go on as they would). The gestures stay.
 */
final class NavHandle {
    static final String ACTION = "com.monotonnoe.oneglass.engine.HOME_SHOWN";
    static final String EXTRA_SHOWN = "shown";
    private static final String[] HANDLES = {
            "com.android.systemui.navigationbar.gestural.NavigationHandle",
            "com.android.systemui.navigationbar.gestural.NavigationHintHandle",
    };
    private static final Map<View, Boolean> VIEWS = new WeakHashMap<>();
    private static final Rect NOTHING = new Rect(0, 0, 0, 0);
    private static boolean homeShown;
    private static boolean listening;

    private NavHandle() {
    }

    /** System UI side. */
    static void install(ClassLoader cl) {
        int hooks = 0;
        for (String name : HANDLES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                hooks += XposedBridge.hookAllConstructors(c, SafeHook.after("nav handle",
                        p -> {
                            View v = (View) p.thisObject;
                            VIEWS.put(v, Boolean.TRUE);
                            listen(v.getContext());
                            v.post(() -> apply(v));
                        })).size();
            } catch (Throwable t) {
                XLog.i("no " + name);
            }
        }
        XLog.i("navigation handle hooks: " + hooks);
    }

    private static void listen(Context c) {
        if (listening) return;
        listening = true;
        c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean shown = intent.getBooleanExtra(EXTRA_SHOWN, false);
                if (shown == homeShown) return;
                homeShown = shown;
                for (View v : new ArrayList<>(VIEWS.keySet())) {
                    if (v != null) apply(v);
                }
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    private static void apply(View v) {
        v.setClipBounds(homeShown ? NOTHING : null);
    }

    /** One UI Home side: its Home screen came in front, or went. */
    static void send(Context c, boolean shown) {
        try {
            c.sendBroadcast(new Intent(ACTION).setPackage(LockScreenDiag.SYSTEMUI)
                    .putExtra(EXTRA_SHOWN, shown));
        } catch (Throwable t) {
            XLog.e("home shown", t);
        }
    }
}
