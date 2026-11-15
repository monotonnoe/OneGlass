package com.monotonnoe.oneglass.engine;

import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * iPhone widget corners for One UI's widgets: iOS rounds a small widget by about 0.137 of its
 * side, larger ones by the same amount, and with continuous corners, whose curve eases into
 * the straight sides instead of meeting them as a circle does (Figma's corner smoothing 60%,
 * after figma-squircle, MIT). The widget itself is clipped to that shape, and its glass takes
 * the same shape.
 */
final class WidgetCorners {
    /** iOS widget corner radius over the side of a small (2×2) widget. */
    static final float RATIO = 0.137f;
    private static final float SMOOTHING = 0.6f;
    private static final Map<View, Provider> PROVIDERS = new WeakHashMap<>();
    private static final Map<Class<?>, Field[]> SPAN_FIELDS = new HashMap<>();

    private WidgetCorners() {
    }

    /** The corner radius for a widget of this size and span (cells across and down). */
    static float radius(float w, float h, int[] span) {
        float cells = Math.max(1, Math.min(span[0], span[1]));
        // The side of a 2×2 widget on this grid: iOS keeps the radius of the small widget.
        float small = Math.min(w / span[0], h / span[1]) * 2f;
        if (cells < 2) small = Math.min(w, h);
        return Math.min(RATIO * small, Math.min(w, h) / 2f);
    }

    /** The continuous rounded rect (0, 0, w, h) with corner radius r. */
    static Path path(float w, float h, float r) {
        Path p = new Path();
        r = Math.min(r, Math.min(w, h) / 2f);
        if (r <= 0f) {
            p.addRect(0, 0, w, h, Path.Direction.CW);
            return p;
        }
        // How far along each side the corner reaches, at most half the side.
        float s = Math.min(SMOOTHING, Math.min(w, h) / (2f * r) - 1f);
        s = Math.max(0f, s);
        float ext = (1 + s) * r;
        float arcDeg = 90f * (1 - s);
        float arcChord = (float) (Math.sin(Math.toRadians(arcDeg / 2)) * r * Math.sqrt(2));
        float alpha = (90f - arcDeg) / 2f;
        float p3p4 = (float) (r * Math.tan(Math.toRadians(alpha / 2)));
        float beta = 45f * s;
        float c = (float) (p3p4 * Math.cos(Math.toRadians(beta)));
        float d = (float) (c * Math.tan(Math.toRadians(beta)));
        float b = (ext - arcChord - c - d) / 3f;
        float a = 2f * b;
        RectF oval = new RectF();
        // Top edge, then each corner clockwise: curve in, circular arc, curve out.
        p.moveTo(ext, 0);
        p.lineTo(w - ext, 0);
        p.rCubicTo(a, 0, a + b, 0, a + b + c, d);
        oval.set(w - 2 * r, 0, w, 2 * r);
        p.arcTo(oval, -90f + alpha, arcDeg);
        p.lineTo(w - d, ext - a - b - c);
        p.cubicTo(w, ext - a - b, w, ext - a, w, ext);
        p.lineTo(w, h - ext);
        p.rCubicTo(0, a, 0, a + b, -d, a + b + c);
        oval.set(w - 2 * r, h - 2 * r, w, h);
        p.arcTo(oval, alpha, arcDeg);
        p.lineTo(w - ext + a + b + c, h - d);
        p.cubicTo(w - ext + a + b, h, w - ext + a, h, w - ext, h);
        p.lineTo(ext, h);
        p.rCubicTo(-a, 0, -a - b, 0, -a - b - c, -d);
        oval.set(0, h - 2 * r, 2 * r, h);
        p.arcTo(oval, 90f + alpha, arcDeg);
        p.lineTo(d, h - ext + a + b + c);
        p.cubicTo(0, h - ext + a + b, 0, h - ext + a, 0, h - ext);
        p.lineTo(0, ext);
        p.rCubicTo(0, -a, 0, -a - b, d, -a - b - c);
        oval.set(0, 0, 2 * r, 2 * r);
        p.arcTo(oval, 180f + alpha, arcDeg);
        p.lineTo(ext - a - b - c, d);
        p.cubicTo(ext - a - b, 0, ext - a, 0, ext, 0);
        p.close();
        return p;
    }

    private static Path lastPath;
    private static float lastW;
    private static float lastH;
    private static float lastR;

    /**
     * {@link #path}, kept for the last size asked: widgets are redrawn often, and most are
     * the same size. Main thread; the path must not be changed.
     */
    static Path cachedPath(float w, float h, float r) {
        if (lastPath == null || w != lastW || h != lastH || r != lastR) {
            lastPath = path(w, h, r);
            lastW = w;
            lastH = h;
            lastR = r;
        }
        return lastPath;
    }

    /** A widget's span in cells from its cell's layout params, 2×2 when they do not say. */
    static int[] span(ViewGroup.LayoutParams lp) {
        if (lp == null) return new int[]{2, 2};
        Class<?> c = lp.getClass();
        Field[] f = SPAN_FIELDS.get(c);
        if (f == null) {
            f = new Field[2];
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    String n = field.getName();
                    if (field.getType() != int.class) continue;
                    int axis = n.equals("cellHSpan") ? 0 : n.equals("cellVSpan") ? 1 : -1;
                    if (axis >= 0 && f[axis] == null) {
                        field.setAccessible(true);
                        f[axis] = field;
                    }
                }
            }
            SPAN_FIELDS.put(c, f);
        }
        try {
            if (f[0] != null && f[1] != null) {
                int x = f[0].getInt(lp);
                int y = f[1].getInt(lp);
                if (x > 0 && y > 0) return new int[]{x, y};
            }
        } catch (Throwable ignored) {
            // Fall back to a small widget.
        }
        return new int[]{2, 2};
    }

    /**
     * Clips {@code host} to the iOS shape (or gives it back One UI's outline when the setting
     * is off). One UI may set its own outline again: checked at every draw.
     */
    static void apply(View host, int[] span, boolean on) {
        ViewOutlineProvider current = host.getOutlineProvider();
        Provider mine = PROVIDERS.get(host);
        if (!on) {
            if (mine != null && current == mine) {
                host.setOutlineProvider(mine.previous);
                host.invalidateOutline();
            }
            return;
        }
        if (mine == null || current != mine) {
            mine = new Provider(current == mine ? null : current);
            PROVIDERS.put(host, mine);
            host.setOutlineProvider(mine);
            host.setClipToOutline(true);
        }
        if (mine.span[0] != span[0] || mine.span[1] != span[1]) {
            mine.span = span;
            host.invalidateOutline();
        }
        if (!host.getClipToOutline()) host.setClipToOutline(true);
    }

    private static final class Provider extends ViewOutlineProvider {
        final ViewOutlineProvider previous;
        int[] span = {2, 2};

        Provider(ViewOutlineProvider previous) {
            this.previous = previous;
        }

        @Override
        public void getOutline(View v, Outline o) {
            float w = v.getWidth();
            float h = v.getHeight();
            float r = radius(w, h, span);
            if (Build.VERSION.SDK_INT >= 33) {
                // Clipping to a path outline works from Android 13.
                o.setPath(cachedPath(w, h, r));
            } else {
                o.setRoundRect(0, 0, Math.round(w), Math.round(h), r);
            }
        }
    }
}
