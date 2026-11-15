package com.monotonnoe.oneglass.hook;

import android.appwidget.AppWidgetHostView;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.IconGeometry;
import com.monotonnoe.oneglass.glass.Log;
import com.monotonnoe.oneglass.glass.Units;

import android.graphics.RectF;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Puts One UI's home grid on the iOS 27 grid of the Figma kit (Playground, "Home Screen Layout").
 * Same idea as SquareWidgets: after the launcher laid out a page, every child is moved (and
 * widgets re-measured) to the rectangle its cell has on iOS, and widgets are told their new size.
 *
 * Kit geometry on the 402 x 874 pt screen:
 *   icons 60pt, columns x = 36 + 90 * c, rows y = 101 + 97.8 * r (6 rows)
 *   widget width  = 90 * (cols - 1) + 68  (158 / 338), starting 4pt left of its first icon
 *   widget height = 97.8 * (rows - 1) + 60 (158 / 354 / 550), starting at its first icon's top
 *   dock icons 60pt at x = 41 + 86.33 * c
 */
final class IosGrid {

    static final float COL_X0 = 36f;
    static final float COL_PITCH = 90f;
    static final float ROW_Y0 = 101f;
    static final float ROW_PITCH = 97.8f;
    static final float ICON = 60f;
    static final float WIDGET_OUTSET = 4f;
    static final float DOCK_X0 = 41f;
    static final float DOCK_PITCH = 86.33f;
    static final int COLS = 4;
    static final int ROWS = 6;

    private static final String[] CELL_X = {"cellX", "mCellX"};
    private static final String[] CELL_Y = {"cellY", "mCellY"};
    private static final String[] SPAN_X = {"cellHSpan", "spanX", "spanx", "hspan", "mSpanX", "cellHorizontalSpan"};
    private static final String[] SPAN_Y = {"cellVSpan", "spanY", "spany", "vspan", "mSpanY", "cellVerticalSpan"};

    private static final Map<Class<?>, Field[]> LP_FIELDS = new HashMap<>();
    private static final Set<Class<?>> HOOKED = new HashSet<>();
    private static final Set<Class<?>> LOGGED = new HashSet<>();
    private static final Map<View, String> REPORTED = new WeakHashMap<>();
    private static final RectF ICON_RECT = new RectF();
    private static final int[] CELL = new int[4];

    private IosGrid() {
    }

    /** Hooks the onLayout that the given cell layout class (or its nearest parent) declares. */
    static void hookLayout(Class<?> cls, final boolean hotseat) {
        for (Class<?> c = cls; c != null && c != ViewGroup.class; c = c.getSuperclass()) {
            Method m;
            try {
                m = c.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            synchronized (HOOKED) {
                if (!HOOKED.add(c)) {
                    return;
                }
            }
            final Class<?> target = cls;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!target.isInstance(param.thisObject) || !Config.current.iosGrid) {
                        return;
                    }
                    try {
                        if (hotseat) {
                            layoutDock((ViewGroup) param.thisObject);
                        } else {
                            layoutPage((ViewGroup) param.thisObject);
                        }
                    } catch (Throwable t) {
                        Log.e("ios grid", t);
                    }
                }
            });
            Log.i("iOS grid: hooked " + c.getName() + "#onLayout for " + cls.getSimpleName());
            return;
        }
        Log.i("iOS grid: no onLayout found above " + cls.getName());
    }

    /** Offset of {@code v}'s top-left on the (unscrolled, untransformed) page, in px. */
    private static void pagePosition(View v, float[] out) {
        float x = 0, y = 0;
        View cur = v;
        while (true) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                break;
            }
            View pv = (View) p;
            y -= pv.getScrollY();
            cur = pv;
        }
        int w = v.getRootView().getWidth();
        if (w > 0) {
            // pages sit side by side; scrolling between them must not move the grid
            x = ((x % w) + w) % w;
            if (x > w - 1) {
                x = 0;
            }
        }
        out[0] = x;
        out[1] = y;
    }

    private static void layoutPage(ViewGroup cl) {
        int n = cl.getChildCount();
        if (n == 0) {
            return;
        }
        float pt = Units.pt(cl);
        float[] pos = new float[2];
        pagePosition(cl, pos);
        // children may sit inside an intermediate container (ShortcutAndWidgetContainer)
        for (int i = 0; i < n; i++) {
            View child = cl.getChildAt(i);
            if (child instanceof ViewGroup && cellOf(child) == null && !isWidget(child)) {
                ViewGroup inner = (ViewGroup) child;
                float[] p2 = new float[2];
                pagePosition(inner, p2);
                for (int j = 0; j < inner.getChildCount(); j++) {
                    place(inner.getChildAt(j), p2, pt);
                }
            } else {
                place(child, pos, pt);
            }
        }
    }

    private static void place(View child, float[] origin, float pt) {
        if (child.getVisibility() == View.GONE) {
            return;
        }
        int[] cell = cellOf(child);
        if (cell == null) {
            return;
        }
        int cx = cell[0], cy = cell[1], sx = Math.max(1, cell[2]), sy = Math.max(1, cell[3]);
        if (cx < 0 || cy < 0 || cx + sx > COLS || cy + sy > ROWS) {
            return;
        }
        boolean widget = isWidget(child) || (sx > 1 || sy > 1);
        float left, top;
        if (widget) {
            float w = (COL_PITCH * (sx - 1) + ICON + 2 * WIDGET_OUTSET) * pt;
            float h = (ROW_PITCH * (sy - 1) + ICON) * pt;
            left = (COL_X0 + COL_PITCH * cx - WIDGET_OUTSET) * pt - origin[0];
            top = (ROW_Y0 + ROW_PITCH * cy) * pt - origin[1];
            int wi = Math.round(w), hi = Math.round(h);
            if (child.getMeasuredWidth() != wi || child.getMeasuredHeight() != hi) {
                child.measure(View.MeasureSpec.makeMeasureSpec(wi, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(hi, View.MeasureSpec.EXACTLY));
            }
            int l = Math.round(left), t = Math.round(top);
            child.layout(l, t, l + wi, t + hi);
            reportSize(child, w / pt, h / pt);
        } else {
            // icon: put the icon square (not the whole view with its label) on the iOS cell
            float iconLeft = (COL_X0 + COL_PITCH * cx) * pt - origin[0];
            float iconTop = (ROW_Y0 + ROW_PITCH * cy) * pt - origin[1];
            float iconSize = ICON * pt;
            float dx = 0, dy = 0;
            if (IconGeometry.find(child, ICON_RECT)) {
                dx = ICON_RECT.centerX();
                dy = ICON_RECT.centerY();
            } else {
                dx = child.getWidth() / 2f;
                dy = iconSize / 2f;
            }
            int l = Math.round(iconLeft + iconSize / 2f - dx);
            int t = Math.round(iconTop + iconSize / 2f - dy);
            child.layout(l, t, l + child.getMeasuredWidth(), t + child.getMeasuredHeight());
        }
        if (LOGGED.add(child.getClass())) {
            Log.i("iOS grid places " + child.getClass().getName() + " cell " + cx + "," + cy + " span " + sx + "x" + sy);
        }
    }

    private static void layoutDock(ViewGroup hotseat) {
        float pt = Units.pt(hotseat);
        float[] pos = new float[2];
        pagePosition(hotseat, pos);
        List<View> icons = new ArrayList<>();
        collectDock(hotseat, icons, 0);
        if (icons.isEmpty() || icons.size() > 5) {
            return;
        }
        // keep their order on screen
        Collections.sort(icons, (a, b) -> Integer.compare(a.getLeft(), b.getLeft()));
        int n = icons.size();
        // iOS centres a dock with fewer icons
        float first = DOCK_X0 + (4 - n) * DOCK_PITCH / 2f;
        if (n == 5) {
            first = DOCK_X0 - DOCK_PITCH / 2f;
        }
        for (int i = 0; i < n; i++) {
            View v = icons.get(i);
            ViewGroup parent = (ViewGroup) v.getParent();
            float[] origin = pos;
            if (parent != hotseat) {
                origin = new float[2];
                pagePosition(parent, origin);
            }
            float iconLeft = (first + DOCK_PITCH * i) * pt - origin[0];
            float iconSize = ICON * pt;
            float dx = IconGeometry.find(v, ICON_RECT) ? ICON_RECT.centerX() : v.getWidth() / 2f;
            int l = Math.round(iconLeft + iconSize / 2f - dx);
            v.layout(l, v.getTop(), l + v.getMeasuredWidth(), v.getTop() + v.getMeasuredHeight());
        }
    }

    private static void collectDock(ViewGroup g, List<View> out, int depth) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                continue;
            }
            if (cellOf(c) != null) {
                out.add(c);
            } else if (c instanceof ViewGroup && depth < 2) {
                collectDock((ViewGroup) c, out, depth + 1);
            }
        }
    }

    static boolean isWidget(View v) {
        if (v instanceof AppWidgetHostView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && i < 3; i++) {
                View c = g.getChildAt(i);
                if (c instanceof AppWidgetHostView) {
                    return true;
                }
                if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0
                        && ((ViewGroup) c).getChildAt(0) instanceof AppWidgetHostView) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tell the widget provider the size it now has (dp), like a resize in the stock launcher. */
    private static void reportSize(View child, float wPt, float hPt) {
        AppWidgetHostView host = findHost(child);
        if (host == null) {
            return;
        }
        float dp = host.getResources().getDisplayMetrics().density;
        float pt = Units.pt(host);
        float wDp = wPt * pt / dp;
        float hDp = hPt * pt / dp;
        String key = Math.round(wDp) + "x" + Math.round(hDp);
        if (key.equals(REPORTED.get(host))) {
            return;
        }
        REPORTED.put(host, key);
        try {
            List<SizeF> sizes = new ArrayList<>();
            sizes.add(new SizeF(wDp, hDp));
            host.updateAppWidgetSize(new Bundle(), sizes);
        } catch (Throwable t) {
            Log.e("widget size report", t);
        }
    }

    private static AppWidgetHostView findHost(View v) {
        if (v instanceof AppWidgetHostView) {
            return (AppWidgetHostView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                AppWidgetHostView h = findHost(g.getChildAt(i));
                if (h != null) {
                    return h;
                }
            }
        }
        return null;
    }

    /** cellX, cellY, spanX, spanY from the honeyspace layout params, or null. */
    static int[] cellOf(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            return null;
        }
        Field[] f = fields(lp.getClass());
        if (f == null) {
            return null;
        }
        try {
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) {
                r[i] = f[i] == null ? 1 : f[i].getInt(lp);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field[] fields(Class<?> cls) {
        if (LP_FIELDS.containsKey(cls)) {
            return LP_FIELDS.get(cls);
        }
        Map<String, Field> ints = new HashMap<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("android.")) {
                break;
            }
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    ints.putIfAbsent(f.getName().toLowerCase(java.util.Locale.ROOT), f);
                }
            }
        }
        Field[] r = {pick(ints, CELL_X), pick(ints, CELL_Y), pick(ints, SPAN_X), pick(ints, SPAN_Y)};
        if (r[0] == null || r[1] == null) {
            r = null;
        }
        LP_FIELDS.put(cls, r);
        if (r != null) {
            Log.i("iOS grid: layout params " + cls.getName() + " -> " + r[0].getName() + ", " + r[1].getName()
                    + ", " + (r[2] == null ? "-" : r[2].getName()) + ", " + (r[3] == null ? "-" : r[3].getName()));
        }
        return r;
    }

    private static Field pick(Map<String, Field> ints, String[] names) {
        for (String n : names) {
            Field f = ints.get(n.toLowerCase(java.util.Locale.ROOT));
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}
