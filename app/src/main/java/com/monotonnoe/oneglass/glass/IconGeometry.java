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
        lastDrawable = null;
        String how;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Drawable top = tv.getCompoundDrawables()[1];
            if (top != null && !top.getBounds().isEmpty()) {
                // exactly where TextView#onDraw puts the top compound drawable (view coordinates)
                Rect b = top.getBounds();
                int cpl = tv.getCompoundPaddingLeft();
                int cpr = tv.getCompoundPaddingRight();
                float left = cpl + ((w - cpl - cpr) - b.width()) / 2f;
                float t = tv.getPaddingTop();
                out.set(left + b.left, t + b.top, left + b.right, t + b.bottom);
                lastDrawable = top;
                log(v, "compound top drawable", out, w, h);
                return true;
            }
            float size = iconSize(tv);
            if (size > 0) {
                float left = (w - size) / 2f;
                out.set(left, tv.getPaddingTop(), left + size, tv.getPaddingTop() + size);
                log(v, "getIconSize()", out, w, h);
                return true;
            }
        }
        Drawable best = largestSquareField(v, w);
        if (best != null) {
            lastDrawable = best;
            Rect b = best.getBounds();
            out.set(b);
            if (b.left == 0 && b.top == 0 && b.width() < w - 2) {
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
        log(v, how, out, w, h);
        return true;
    }

    /**
     * The icon square of the first icon view (TextView) inside {@code container}, in the
     * coordinates of {@code ancestor} (an ancestor of container, or container itself).
     */
    public static boolean iconIn(View container, View ancestor, RectF out) {
        TextView tv = firstTextView(container, 0);
        if (tv == null || !find(tv, out)) {
            return false;
        }
        View cur = tv;
        int guard = 0;
        while (cur != ancestor && guard++ < 12) {
            out.offset(cur.getX(), cur.getY());
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                return false;
            }
            View pv = (View) p;
            out.offset(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        return cur == ancestor;
    }

    private static TextView firstTextView(View v, int depth) {
        if (v instanceof TextView) {
            return (TextView) v;
        }
        if (v instanceof android.view.ViewGroup && depth < 3) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = firstTextView(g.getChildAt(i), depth + 1);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static final Map<Class<?>, java.lang.reflect.Method> ICON_SIZE = new HashMap<>();

    /** honeyspace icon views expose getIconSize() (BlurIcons uses it too). */
    private static float iconSize(TextView v) {
        Class<?> c = v.getClass();
        java.lang.reflect.Method m;
        if (ICON_SIZE.containsKey(c)) {
            m = ICON_SIZE.get(c);
        } else {
            try {
                m = c.getMethod("getIconSize");
            } catch (Throwable t) {
                m = null;
            }
            ICON_SIZE.put(c, m);
        }
        if (m == null) {
            return -1f;
        }
        try {
            Object r = m.invoke(v);
            return r instanceof Number ? ((Number) r).floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static void log(View v, String how, RectF out, int w, int h) {
        if (LOGGED.add(v.getClass())) {
            Log.i("icon geometry for " + v.getClass().getName() + ": " + how + " " + out.toShortString()
                    + " in " + w + "x" + h);
        }
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
