package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import com.monotonnoe.oneglass.glass.IconGeometry;
import com.monotonnoe.oneglass.glass.Units;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * OneGlass addition to the BlurIcons engine: the iOS 27 dock platter. Figma ("iOS 27 Widgets",
 * Playground, Home Screen Layout): Dock 357x99pt, corner radius 35pt, corner smoothing 0.6, the
 * 60pt icons centred in it (19pt around them). Drawn with the engine's own liquid glass, by the
 * hotseat's parent right before its children, so it looks exactly like the icons' glass and
 * follows the wallpaper (or the chosen picture for live wallpapers) the same way.
 *
 * Lens of the platter: refraction height 36.4pt and amount 6.84pt, fitted to Figma's render of
 * the Playground dock, expressed as shares of the platter's shorter side (99pt).
 */
public final class DockGlass {

    private static final float DOCK_W = 357f;
    private static final float DOCK_H = 99f;
    private static final float DOCK_RADIUS = 35f;
    private static final float ICON_INSET = 19f;
    private static final float LENS_HEIGHT = 36.4f / DOCK_H;
    private static final float LENS_AMOUNT = 6.84f / DOCK_H;

    private static final WeakHashMap<View, Item> HOSTS = new WeakHashMap<>();
    private static final Set<Method> HOOKED = new HashSet<>();

    private static final class Item {
        final WeakReference<View> hotseat;
        final LiquidGlass.Glass glass = new LiquidGlass.Glass();

        Item(View hotseat, View host) {
            this.hotseat = new WeakReference<>(hotseat);
            glass.anchor = new WeakReference<>(host);
            glass.lensHeight = LENS_HEIGHT;
            glass.lensAmount = LENS_AMOUNT;
        }
    }

    private static final RectF RECT = new RectF();
    private static final RectF ICONS = new RectF();
    private static final RectF ONE = new RectF();

    private DockGlass() {
    }

    /** Called with One UI's HotseatCellLayout once it is attached. */
    public static void attach(View hotseat) {
        if (!(hotseat.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup host = (ViewGroup) hotseat.getParent();
        Item item = HOSTS.get(host);
        if (item != null && item.hotseat.get() == hotseat) {
            return;
        }
        HOSTS.put(host, new Item(hotseat, host));
        host.setWillNotDraw(false);
        hook(host.getClass());
        host.invalidate();
        XLog.i("dock glass drawn by " + host.getClass().getName());
    }

    private static void hook(Class<?> cls) {
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
                    Item item = HOSTS.get(param.thisObject);
                    if (item == null || !(param.args[0] instanceof Canvas)) {
                        return;
                    }
                    try {
                        draw((Canvas) param.args[0], (View) param.thisObject, item);
                    } catch (Throwable t) {
                        XLog.e("dock glass", t);
                    }
                }
            });
            return;
        }
    }

    private static void draw(Canvas canvas, View host, Item item) {
        View hotseat = item.hotseat.get();
        if (!com.monotonnoe.oneglass.Config.current.dock || hotseat == null
                || hotseat.getParent() != host || hotseat.getVisibility() != View.VISIBLE
                || !LiquidGlass.available() || !rect(host, hotseat)) {
            return;
        }
        float pt = Units.pt(host);
        float corner = Math.min(DOCK_RADIUS * pt, Math.min(RECT.width(), RECT.height()) / 2f);
        float alpha = hotseat.getAlpha();
        if (alpha <= 0.01f) {
            return;
        }
        int tint = Settings.backgroundAlpha;
        int argb = IconBlur.autoTint(host, tint);
        item.glass.tint = argb;
        canvas.save();
        try {
            // dispatchDraw's canvas is scrolled with the content; RECT is in view coordinates
            canvas.translate(host.getScrollX(), host.getScrollY());
            if (item.glass.draw(canvas, host, RECT, corner, alpha, Config.glassBlur)) {
                LiquidGlass.onDrawn(host.getContext());
                if (argb == 0 || !item.glass.tintDrawn) {
                    IconBlur.drawTint(canvas, host, RECT, corner, tint);
                }
            } else {
                item.glass.markBare();
                IconBlur.drawTint(canvas, host, RECT, corner, tint);
            }
        } finally {
            canvas.restore();
        }
        CellGlass.watch(hotseat, host, item.glass);
    }

    /** Figma dock around the icons One UI shows (wider if its icon row is wider). */
    private static boolean rect(View host, View hotseat) {
        float pt = Units.pt(host);
        ICONS.setEmpty();
        collect(hotseat, host, 0);
        if (ICONS.isEmpty()) {
            return false;
        }
        com.monotonnoe.oneglass.Config cfg = com.monotonnoe.oneglass.Config.current;
        float hw = Math.max(DOCK_W * pt, ICONS.width() + 2f * ICON_INSET * pt) / 2f + cfg.dockPadH * pt;
        float hh = DOCK_H * pt / 2f + cfg.dockPadV * pt;
        float cx = cfg.iosGrid ? host.getWidth() / 2f : ICONS.centerX();
        float cy = ICONS.centerY();
        RECT.set(cx - hw, cy - hh, cx + hw, cy + hh);
        return RECT.intersect(0, 0, host.getWidth(), host.getHeight());
    }

    private static void collect(View v, View host, int depth) {
        if (!(v instanceof ViewGroup) || depth > 2) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View k = g.getChildAt(i);
            if (k.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (IconGeometry.iconIn(k, host, ONE)) {
                if (ICONS.isEmpty()) {
                    ICONS.set(ONE);
                } else {
                    ICONS.union(ONE);
                }
            } else {
                collect(k, host, depth + 1);
            }
        }
    }
}
