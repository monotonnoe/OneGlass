package com.monotonnoe.oneglass.glass;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds where an app / folder icon is drawn inside One UI's icon view. The honeyspace icon views
 * are not TextViews with a compound drawable, so the icon drawable is located by reflection:
 * the largest square drawable held in a field of the view.
 */
public final class IconGeometry {

    private static final Map<Class<?>, List<Field>> FIELDS = new HashMap<>();
    private static final Set<Class<?>> LOGGED = new HashSet<>();

    /** The icon drawable found by the last successful {@link #find} (null for the heuristic). */
    public static Drawable lastDrawable;

    private IconGeometry() {
    }

    public static boolean find(View v, RectF out) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        String how;
        Drawable best = null;
        lastDrawable = null;
        if (v instanceof TextView) {
            Drawable top = ((TextView) v).getCompoundDrawables()[1];
            if (top != null && !top.getBounds().isEmpty()) {
                best = top;
            }
        }
        if (best == null) {
            best = largestSquareField(v, w);
        }
        if (best != null) {
            lastDrawable = best;
            Rect b = best.getBounds();
            out.set(b);
            if (b.left == 0 && b.top == 0 && b.width() < w - 2) {
                // Drawn after a canvas translate: assume centred horizontally, under the top padding.
                float l = (w - b.width()) / 2f;
                out.offsetTo(l, v.getPaddingTop());
                how = "drawable field (centred)";
            } else {
                how = "drawable field";
            }
        } else {
            float side = Math.min(w - v.getPaddingLeft() - v.getPaddingRight(), h * 0.62f);
            if (side <= 0) {
                return false;
            }
            out.set((w - side) / 2f, v.getPaddingTop(), (w + side) / 2f, v.getPaddingTop() + side);
            how = "heuristic";
        }
        if (LOGGED.add(v.getClass())) {
            Log.i("icon geometry for " + v.getClass().getName() + ": " + how + " " + out.toShortString()
                    + " in " + w + "x" + h);
        }
        return true;
    }

    private static Drawable largestSquareField(View v, int viewW) {
        Drawable bg = v.getBackground();
        Drawable best = null;
        int bestSide = 0;
        for (Field f : fields(v.getClass())) {
            Object o;
            try {
                o = f.get(v);
            } catch (Throwable t) {
                continue;
            }
            if (!(o instanceof Drawable) || o == bg) {
                continue;
            }
            Rect b = ((Drawable) o).getBounds();
            int bw = b.width();
            int bh = b.height();
            if (bw < 8 || bw > viewW + 1) {
                continue;
            }
            if (Math.abs(bw - bh) > Math.max(2, bw / 20)) {
                continue;
            }
            if (bw > bestSide) {
                bestSide = bw;
                best = (Drawable) o;
            }
        }
        return best;
    }

    private static List<Field> fields(Class<?> cls) {
        List<Field> list = FIELDS.get(cls);
        if (list != null) {
            return list;
        }
        list = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != View.class && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("android.")) {
                break;
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!Drawable.class.isAssignableFrom(f.getType()) && f.getType() != Object.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    list.add(f);
                } catch (Throwable ignored) {
                    // skip
                }
            }
        }
        FIELDS.put(cls, list);
        return list;
    }
}
