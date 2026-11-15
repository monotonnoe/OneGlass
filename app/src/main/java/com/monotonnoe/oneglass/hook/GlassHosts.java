package com.monotonnoe.oneglass.hook;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import com.monotonnoe.oneglass.glass.GlassDrawable;
import com.monotonnoe.oneglass.glass.GlassTracker;
import com.monotonnoe.oneglass.glass.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Glass drawn by a host ViewGroup right before its children (from dispatchDraw), the way
 * BlurIcons' CellGlass does it. Unlike a background, the launcher can not replace it, and it can
 * extend past the child it belongs to (the dock platter is bigger than the icon row).
 */
final class GlassHosts {

    private static final WeakHashMap<View, List<GlassDrawable>> ITEMS = new WeakHashMap<>();
    private static final Set<Method> HOOKED = new HashSet<>();

    private GlassHosts() {
    }

    static void add(ViewGroup host, GlassDrawable glass) {
        List<GlassDrawable> list = ITEMS.get(host);
        if (list == null) {
            list = new ArrayList<>(2);
            ITEMS.put(host, list);
        }
        for (GlassDrawable g : list) {
            if (g.role == glass.role && g.shape == glass.shape) {
                return;
            }
        }
        glass.hostedBy(host);
        list.add(glass);
        GlassTracker.track(host, glass);
        host.setWillNotDraw(false);
        hookDispatchDraw(host.getClass());
        host.invalidate();
    }

    static boolean has(View host, int role) {
        List<GlassDrawable> list = ITEMS.get(host);
        if (list != null) {
            for (GlassDrawable g : list) {
                if (g.role == role) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void hookDispatchDraw(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method m;
            try {
                m = c.getDeclaredMethod("dispatchDraw", Canvas.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            synchronized (HOOKED) {
                if (!HOOKED.add(m)) {
                    return;
                }
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    List<GlassDrawable> list = ITEMS.get(param.thisObject);
                    if (list == null || !(param.args[0] instanceof Canvas)) {
                        return;
                    }
                    View host = (View) param.thisObject;
                    Canvas canvas = (Canvas) param.args[0];
                    for (int i = 0; i < list.size(); i++) {
                        try {
                            list.get(i).drawIn(canvas, host);
                        } catch (Throwable t) {
                            Log.e("glass draw", t);
                        }
                    }
                }
            });
            Log.i("glass drawn in " + c.getName() + "#dispatchDraw (for " + cls.getName() + ")");
            return;
        }
    }
}
