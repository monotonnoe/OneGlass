package com.monotonnoe.oneglass.glass;

import android.graphics.Matrix;
import android.view.View;
import android.view.ViewTreeObserver;

import com.monotonnoe.oneglass.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Display lists are cached: when the home pages scroll, an icon or widget is only moved, not
 * redrawn, so the wallpaper seen through its glass would slide along with it. Before every frame
 * this checks where each glass host is on the display (and the wallpaper offset); hosts that
 * moved are invalidated so the glass is re-recorded with the backdrop in the right place.
 */
public final class GlassTracker {

    private static final class Entry {
        final ArrayList<GlassDrawable> glass = new ArrayList<>(2);
        float[] last = new float[0];
        float[] now = new float[0];
    }

    private static final WeakHashMap<View, Entry> HOSTS = new WeakHashMap<>();
    private static final WeakHashMap<ViewTreeObserver, Boolean> OBSERVERS = new WeakHashMap<>();
    private static final Matrix M = new Matrix();
    private static final float[] V = new float[9];

    private static final ViewTreeObserver.OnPreDrawListener PRE_DRAW = () -> {
        try {
            check();
        } catch (Throwable t) {
            Log.e("tracker", t);
        }
        return true;
    };

    private GlassTracker() {
    }

    public static void track(View host, GlassDrawable d) {
        Entry e = HOSTS.get(host);
        if (e == null) {
            e = new Entry();
            HOSTS.put(host, e);
        }
        if (!e.glass.contains(d)) {
            e.glass.add(d);
        }
        observe(host);
    }

    /** Called from draw(): remembers what was painted. */
    static void drawn(View host) {
        Entry e = HOSTS.get(host);
        if (e == null) {
            return;
        }
        e.last = key(host, e, e.last);
        observe(host);
    }

    private static void observe(View host) {
        ViewTreeObserver vto = host.getViewTreeObserver();
        if (vto.isAlive() && OBSERVERS.put(vto, Boolean.TRUE) == null) {
            vto.addOnPreDrawListener(PRE_DRAW);
        }
    }

    private static float[] key(View host, Entry e, float[] into) {
        int n = 9 + 4 + e.glass.size() * 5;
        float[] k = into.length == n ? into : new float[n];
        MatrixUtil.localToScreen(host, M);
        M.getValues(V);
        System.arraycopy(V, 0, k, 0, 9);
        k[9] = Backdrop.generation();
        k[10] = Backdrop.xOffset();
        k[11] = Backdrop.yOffset();
        k[12] = Config.current.generation;
        int at = 13;
        for (GlassDrawable d : e.glass) {
            at = d.key(host, k, at);
        }
        return k;
    }

    private static void check() {
        for (Map.Entry<View, Entry> me : HOSTS.entrySet()) {
            View host = me.getKey();
            Entry e = me.getValue();
            if (host == null || !host.isAttachedToWindow() || host.getVisibility() != View.VISIBLE) {
                continue;
            }
            e.now = key(host, e, e.now);
            if (!Arrays.equals(e.now, e.last)) {
                host.invalidate();
                for (GlassDrawable d : e.glass) {
                    d.invalidateSelf();
                }
            }
        }
    }

    /** Settings or wallpaper changed: repaint every glass host. */
    public static void refreshAll() {
        for (Map.Entry<View, Entry> me : HOSTS.entrySet()) {
            View host = me.getKey();
            if (host == null) {
                continue;
            }
            for (GlassDrawable d : me.getValue().glass) {
                if (d.role == GlassSpec.ROLE_WIDGET) {
                    Config c = Config.current;
                    host.setClipToOutline(c.widgets && c.widgetClip);
                    host.invalidateOutline();
                }
                d.invalidateSelf();
            }
            host.invalidate();
        }
    }
}
