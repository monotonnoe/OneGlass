package com.monotonnoe.oneglass.engine;

import com.monotonnoe.oneglass.R;

import android.appwidget.AppWidgetHostView;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;

/**
 * Liquid glass under folder icons and app widgets on the Home screen and the dock. They are not
 * app icon views ({@link IconBlur}), so their cell layout draws the glass right before it draws
 * them: the folder background and the widget then sit on top of it.
 */
final class CellGlass {
    private static final String[] CELL_LAYOUTS = {
            "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayout",
            "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatCellLayout",
            "com.honeyspace.ui.honeypots.applist.presentation.ApplistCellLayout",
    };
    /** How deep in a cell to look for a folder icon or a widget. */
    private static final int MAX_DEPTH = 3;

    /** Per folder icon or widget: its glass and the cell layout that draws it. */
    private static final Map<View, Slot> SLOTS = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> IS_CELL_LAYOUT = new HashMap<>();
    private static final Map<Class<?>, Method> ICON_SIZE = new HashMap<>();
    private static final RectF RECT = new RectF();
    private static float widgetCorner = -1f;
    /** The cell and child being drawn (main thread), to spot a nested drawChild of the same. */
    private static final ArrayDeque<Object[]> STACK = new ArrayDeque<>();
    private static Object activeParent;
    private static Object activeChild;

    private CellGlass() {
    }

    private static final class Slot {
        final LiquidGlass.Glass glass;
        WeakReference<View> parent;
        /**
         * The cell and the folder or widget in it, and where the glass was drawn for them: the
         * cell layout draws the glass with their transform and alpha of that moment in its own
         * display list, which their animations (the Apps screen zooming its folders in) do not
         * record again.
         */
        WeakReference<View> child;
        WeakReference<View> target;
        float offsetX;
        float offsetY;
        final float[] baked = new float[9];
        float bakedAlpha = -1f;

        Slot(LiquidGlass.Glass glass) {
            this.glass = glass;
        }

        /** The transform the glass is drawn with, for the child and target as they are now. */
        boolean transform(Matrix m) {
            View c = child != null ? child.get() : null;
            View t = target != null ? target.get() : null;
            if (c == null || t == null) return false;
            m.setTranslate(c.getLeft(), c.getTop());
            m.preConcat(c.getMatrix());
            m.preTranslate(offsetX - t.getTranslationX(), offsetY - t.getTranslationY());
            m.preConcat(t.getMatrix());
            return true;
        }
    }

    /** Scratch for the offset of a target in its cell: draws record on one thread. */
    private static final float[] OFFSET = new float[2];
    private static final Matrix CHECK = new Matrix();
    private static final float[] CHECK_VALUES = new float[9];

    /** Re-record {@code glass}, drawn by {@code drawnBy} for {@code target}, when it moves. */
    static void watch(View target, View drawnBy, LiquidGlass.Glass glass) {
        Slot slot = SLOTS.get(target);
        if (slot == null || slot.glass != glass) {
            slot = new Slot(glass);
            SLOTS.put(target, slot);
        }
        slot.parent = new WeakReference<>(drawnBy);
    }

    /**
     * The cell layouts' dispatchDraw, once per cell layout drawn, rather than drawChild, which
     * ran for every child of every view group in the launcher each time it was recorded (all of
     * them on each frame of the Home screen's zoom as apps open and close). The nearest
     * dispatchDraw a cell layout class declares, or ViewGroup's when it declares none.
     */
    static void install(ClassLoader cl) {
        int hooks = 0;
        java.util.Set<Class<?>> hooked = new java.util.HashSet<>();
        boolean viewGroup = false;
        for (String name : CELL_LAYOUTS) {
            Class<?> target = ViewGroup.class;
            try {
                for (Class<?> k = Class.forName(name, false, cl); k != null && k != ViewGroup.class;
                     k = k.getSuperclass()) {
                    if (declares(k, "dispatchDraw")) {
                        target = k;
                        break;
                    }
                }
            } catch (Throwable t) {
                XLog.i("no " + name);
            }
            if (target == ViewGroup.class) viewGroup = true;
            else if (hooked.add(target)) {
                hooks += count(XposedBridge.hookAllMethods(target, "dispatchDraw", hook()));
                XLog.i("cell glass draws in " + target.getName() + "#dispatchDraw");
            }
        }
        if (viewGroup) {
            hooks += count(XposedBridge.hookAllMethods(ViewGroup.class, "dispatchDraw", hook()));
            XLog.i("cell glass draws in ViewGroup#dispatchDraw");
        }
        XLog.i("cell glass hooks: " + hooks);
    }

    private static int count(java.util.Set<?> hooks) {
        return hooks == null ? 0 : hooks.size();
    }

    private static SafeHook hook() {
        return new SafeHook("dispatchDraw") {
            @Override
            protected void before(MethodHookParam p) {
                if (!(p.thisObject instanceof ViewGroup) || p.args.length < 1
                        || !(p.args[0] instanceof Canvas)
                        || !isCellLayout(p.thisObject.getClass())) {
                    return;
                }
                // A cell layout's dispatchDraw calling a superclass's must not draw twice.
                if (p.thisObject == activeParent) {
                    nested++;
                    return;
                }
                STACK.push(new Object[]{activeParent, nested});
                activeParent = p.thisObject;
                nested = 0;
                ViewGroup parent = (ViewGroup) p.thisObject;
                Canvas canvas = (Canvas) p.args[0];
                // Under all the cells, before any is drawn (cells do not overlap).
                for (int i = 0; i < parent.getChildCount(); i++) {
                    beforeDrawChild(canvas, parent, parent.getChildAt(i));
                }
            }

            @Override
            protected void after(MethodHookParam p) {
                if (!(p.thisObject instanceof ViewGroup) || p.thisObject != activeParent) return;
                if (nested > 0) {
                    nested--;
                    return;
                }
                ViewGroup parent = (ViewGroup) p.thisObject;
                Canvas canvas = (Canvas) p.args[0];
                // The widgets are drawn: their glare on top.
                for (int i = 0; i < parent.getChildCount(); i++) {
                    GalleryGlare.draw(canvas, parent, parent.getChildAt(i));
                }
                Object[] previous = STACK.pop();
                activeParent = previous[0];
                nested = (Integer) previous[1];
            }
        };
    }

    private static int nested;

    private static boolean declares(Class<?> c, String method) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(method)) return true;
        }
        return false;
    }

    private static boolean isCellLayout(Class<?> c) {
        Boolean b = IS_CELL_LAYOUT.get(c);
        if (b == null) {
            String name = c.getName();
            b = name.endsWith(".WorkspaceCellLayout") || name.endsWith(".HotseatCellLayout")
                    || name.endsWith(".ApplistCellLayout");
            IS_CELL_LAYOUT.put(c, b);
        }
        return b;
    }

    private static void beforeDrawChild(Canvas canvas, ViewGroup parent, View child) {
        corners(child);
        if (!Config.glassFolders && !Config.glassWidgets) return;
        if (Settings.style != Settings.STYLE_LIQUID || !LiquidGlass.available()) return;
        // The Apps screen: its folders, over its own blurred wallpaper.
        boolean drawer = parent.getClass().getName().endsWith(".ApplistCellLayout");
        if (drawer && !Config.drawer) return;
        if (child.getVisibility() != View.VISIBLE) return;
        float[] offset = OFFSET;
        offset[0] = 0f;
        offset[1] = 0f;
        View target = find(child, 0, offset);
        if (target == null) return;
        float live = child.getAlpha() * target.getAlpha();
        boolean folderHidden = placeholder(target);
        if (live <= 0f || folderHidden) {
            // Nothing drawn, but what is on screen now is baked: sync() has the cell layout
            // draw again the frame the cell fades back in (a RenderNode alpha never records
            // the parent again). A folder hidden by One UI (its icon swapped for a transparent
            // placeholder while it opens or closes) leaves no empty glass behind, and its
            // glass fades in when the icon is back.
            Slot hiddenSlot = SLOTS.get(target);
            if (hiddenSlot != null) {
                if (hiddenSlot.child == null || hiddenSlot.child.get() != child) {
                    hiddenSlot.child = new WeakReference<>(child);
                }
                hiddenSlot.offsetX = offset[0];
                hiddenSlot.offsetY = offset[1];
                if (hiddenSlot.transform(CHECK)) CHECK.getValues(hiddenSlot.baked);
                hiddenSlot.bakedAlpha = live;
                if (folderHidden) hiddenSlot.glass.markBare();
            }
            return;
        }
        if (!shape(target)) return;
        if (!canvas.isHardwareAccelerated()) {
            // A software canvas has no glass: it fades in when drawn on the screen again.
            Slot bare = SLOTS.get(target);
            if (bare != null) {
                bare.glass.markBare();
                IconBlur.noteBare(parent, canvas, "widget in software canvas "
                        + canvas.getClass().getName());
            }
            return;
        }

        Slot slot = SLOTS.get(target);
        if (slot == null) {
            slot = new Slot(new LiquidGlass.Glass());
            SLOTS.put(target, slot);
        }
        if (slot.parent == null || slot.parent.get() != parent) {
            slot.parent = new WeakReference<>(parent);
            // Followed through the cell layout's own matrix, found once a pass for the page.
            slot.glass.anchor = slot.parent;
        }
        IconBlur.drawerLens(slot.glass, drawer);
        android.graphics.Path shapePath = null;
        boolean widget = target instanceof AppWidgetHostView;
        if (widget) {
            // iPhone corners: the widget is clipped to them; its glass is the rounded rect that
            // matches them best (a circle 1.24 times their radius meets the continuous corner
            // where it is deepest), so the lens pass draws its rim and hairline, as iOS 27's
            // widgets have the icons' (6.88 stroked a narrower rim along the path).
            int[] span = WidgetCorners.span(child.getLayoutParams());
            if (Config.iosWidgetCorners) {
                RECT.set(0, 0, target.getWidth(), target.getHeight());
                RECT_CORNER[0] = Math.min(Math.min(RECT.width(), RECT.height()) / 2f,
                        WidgetCorners.radius(RECT.width(), RECT.height(), span) * 1.24f);
            }
        }
        slot.glass.shape = shapePath;
        if (slot.child == null || slot.child.get() != child) slot.child = new WeakReference<>(child);
        if (slot.target == null || slot.target.get() != target) {
            slot.target = new WeakReference<>(target);
        }
        slot.offsetX = offset[0];
        slot.offsetY = offset[1];
        if (slot.transform(CHECK)) CHECK.getValues(slot.baked);
        slot.bakedAlpha = child.getAlpha() * target.getAlpha();
        canvas.save();
        try {
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            // The target's own scale too: One UI draws widgets scaled (0.83 on a 4-column grid).
            canvas.translate(offset[0] - target.getTranslationX(),
                    offset[1] - target.getTranslationY());
            canvas.concat(target.getMatrix());
            float alpha = child.getAlpha() * target.getAlpha();
            // The blur as a share of the glass's size: a widget's is the icons' share of it.
            float blur = drawer ? Config.drawerBlur : Config.glassBlur;
            int tint = drawer && Config.drawerTint > 0 ? Config.drawerTint
                    : Settings.backgroundAlpha;
            int argb = IconBlur.autoTint(parent, tint);
            slot.glass.tint = argb;
            if (slot.glass.draw(canvas, parent, RECT, RECT_CORNER[0], alpha, blur)) {
                LiquidGlass.onDrawn(parent.getContext());
                if (argb == 0 || !slot.glass.tintDrawn) {
                    if (shapePath != null) {
                        IconBlur.drawTint(canvas, parent, shapePath, tint);
                    } else {
                        IconBlur.drawTint(canvas, parent, RECT, RECT_CORNER[0], tint);
                    }
                }
            }
        } finally {
            canvas.restore();
        }
    }

    /** iPhone corners (or One UI's) for an app widget in this cell, glass or not. */
    private static void corners(View cell) {
        View host = widgetHost(cell, 0);
        if (host != null) {
            WidgetCorners.apply(host, WidgetCorners.span(cell.getLayoutParams()),
                    Config.iosWidgetCorners);
        }
    }

    private static View widgetHost(View v, int level) {
        if (v instanceof AppWidgetHostView) return v;
        if (level >= MAX_DEPTH || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View found = widgetHost(g.getChildAt(i), level + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** A folder icon or an app widget in {@code v}, and its offset from the cell's origin. */
    private static View find(View v, int level, float[] offset) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (Config.glassWidgets && v instanceof AppWidgetHostView) return v;
        if (Config.glassFolders && isFolderIcon(v)) return v;
        if (level >= MAX_DEPTH || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            float dx = c.getLeft() + c.getTranslationX() - g.getScrollX();
            float dy = c.getTop() + c.getTranslationY() - g.getScrollY();
            offset[0] += dx;
            offset[1] += dy;
            View found = find(c, level + 1, offset);
            if (found != null) return found;
            offset[0] -= dx;
            offset[1] -= dy;
        }
        return null;
    }

    /** One UI's folder icon: a launcher icon view of a folder class (app icons have their own). */
    private static boolean isFolderIcon(View v) {
        return v instanceof TextView && v.getClass().getName().contains("Folder");
    }

    private static final float[] RECT_CORNER = new float[1];

    /**
     * One UI hides a folder icon while its folder opens and closes by putting a transparent
     * ColorDrawable in place of the icon (IconViewImpl#setIconVisible, STATE_CHANGING); it puts
     * the icon back (through setCompoundDrawable, which lays the cell out and records the
     * cell layout again) when the folder has closed.
     */
    private static boolean placeholder(View target) {
        if (!(target instanceof TextView)) return false;
        Drawable top = ((TextView) target).getCompoundDrawables()[1];
        return top instanceof android.graphics.drawable.ColorDrawable
                && (((android.graphics.drawable.ColorDrawable) top).getColor() >>> 24) == 0;
    }

    /** The glass rect and corner of {@code target}, in its own coordinates. */
    private static boolean shape(View target) {
        if (target instanceof AppWidgetHostView) {
            if (widgetShape(target)) return true;
            RECT.set(target.getPaddingLeft(), target.getPaddingTop(),
                    target.getWidth() - target.getPaddingRight(),
                    target.getHeight() - target.getPaddingBottom());
            RECT_CORNER[0] = widgetCorner();
            return !RECT.isEmpty();
        }
        TextView tv = (TextView) target;
        float size;
        float left;
        float top;
        Drawable icon = tv.getCompoundDrawables()[1];
        if (icon != null && !icon.getBounds().isEmpty()) {
            // Where TextView draws its top drawable.
            size = icon.getBounds().width();
            float space = tv.getWidth() - tv.getCompoundPaddingLeft()
                    - tv.getCompoundPaddingRight();
            left = tv.getCompoundPaddingLeft() + (space - size) / 2f;
            top = tv.getPaddingTop();
        } else {
            size = iconSize(tv);
            if (size <= 0f) size = Math.min(tv.getWidth(), tv.getHeight()) * 0.7f;
            left = (tv.getWidth() - size) / 2f;
            top = tv.getPaddingTop();
        }
        if (size <= 0f) return false;
        // The same outline as the pack's app icons, which folders follow.
        IconOutline o = IconOutline.last;
        float inset = Math.max(1f, size * Config.outlineInset);
        if (o != null) {
            RECT.set(left + o.left * size, top + o.top * size, left + o.right * size,
                    top + o.bottom * size);
            RECT.inset(inset, inset);
            RECT_CORNER[0] = Math.max(0f, o.corner * size - inset);
        } else {
            RECT.set(left, top, left + size, top + size);
            RECT.inset(inset, inset);
            RECT_CORNER[0] = RECT.width() * 0.24f;
        }
        RECT_CORNER[0] = Math.min(RECT_CORNER[0], Math.min(RECT.width(), RECT.height()) / 2f);
        return true;
    }

    private static final Outline OUTLINE = new Outline();
    private static final Rect OUTLINE_RECT = new Rect();
    /** Largest rounded outline of a view that paints a background, and of a clip only. */
    private static final RectF PAINTED = new RectF();
    private static final RectF CLIPPED = new RectF();
    private static final float[] CORNERS = new float[2];

    /**
     * The widget's own shape, so a round or pill widget gets glass of its shape rather than the
     * standard corner. Among the host and its first views, the largest rounded outline of a view
     * that paints a background (the shape one sees) wins; a clip alone (One UI rounds every
     * widget host) only counts when nothing paints one. False when none covers half the widget.
     */
    private static boolean widgetShape(View host) {
        PAINTED.setEmpty();
        CLIPPED.setEmpty();
        CORNERS[0] = -1f;
        CORNERS[1] = -1f;
        outlines(host, 0, 0f, 0f);
        float half = host.getWidth() * (float) host.getHeight() * 0.5f;
        RectF best;
        float corner;
        if (CORNERS[0] > 0f && PAINTED.width() * PAINTED.height() >= half) {
            best = PAINTED;
            corner = CORNERS[0];
        } else if (CORNERS[1] > 0f && CLIPPED.width() * CLIPPED.height() >= half) {
            best = CLIPPED;
            corner = CORNERS[1];
        } else {
            return false;
        }
        RECT.set(best);
        float inset = Math.max(1f, Math.min(RECT.width(), RECT.height()) * 0.01f);
        RECT.inset(inset, inset);
        RECT_CORNER[0] = Math.max(0f, Math.min(corner - inset,
                Math.min(RECT.width(), RECT.height()) / 2f));
        return !RECT.isEmpty();
    }

    private static void outlines(View v, int level, float dx, float dy) {
        if (v.getVisibility() != View.VISIBLE) return;
        ViewOutlineProvider provider = v.getOutlineProvider();
        if (provider != null && v.getWidth() > 0 && v.getHeight() > 0) {
            OUTLINE.setEmpty();
            try {
                provider.getOutline(v, OUTLINE);
                float radius = OUTLINE.getRadius();
                // One UI keeps a widget's outline at its cells' size even when the widget is
                // smaller (Square Widgets): the glass never goes past the view itself.
                if (radius > 0f && OUTLINE.getRect(OUTLINE_RECT)
                        && OUTLINE_RECT.intersect(0, 0, v.getWidth(), v.getHeight())
                        && !OUTLINE_RECT.isEmpty()) {
                    boolean painted = v.getBackground() != null;
                    RectF best = painted ? PAINTED : CLIPPED;
                    float area = OUTLINE_RECT.width() * (float) OUTLINE_RECT.height();
                    if (area > best.width() * best.height()) {
                        best.set(OUTLINE_RECT);
                        best.offset(dx, dy);
                        CORNERS[painted ? 0 : 1] = radius;
                    }
                }
            } catch (Throwable ignored) {
                // A provider that cannot answer here: no outline from this view.
            }
        }
        if (level >= MAX_DEPTH || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            outlines(c, level + 1, dx + c.getLeft() + c.getTranslationX() - g.getScrollX(),
                    dy + c.getTop() + c.getTranslationY() - g.getScrollY());
        }
    }

    /** IconViewImpl#getIconSize(), when it is there and answers in pixels. */
    private static float iconSize(TextView tv) {
        Class<?> c = tv.getClass();
        Method m;
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
        if (m == null) return -1f;
        try {
            Object r = m.invoke(tv);
            return r instanceof Number ? ((Number) r).floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static float widgetCorner() {
        if (widgetCorner < 0f) {
            try {
                widgetCorner = Resources.getSystem().getDimension(
                        android.R.dimen.system_app_widget_background_radius);
            } catch (Throwable t) {
                widgetCorner = 24f * Resources.getSystem().getDisplayMetrics().density;
            }
        }
        return widgetCorner;
    }

    /** Re-records the glass of cells that moved on screen (paging). Main thread, pre-draw. */
    private static final ArrayList<Slot> SYNC_LIST = new ArrayList<>();


    private static boolean changed(Slot s) {
        View c = s.child != null ? s.child.get() : null;
        View t = s.target != null ? s.target.get() : null;
        if (c == null || t == null) return false;
        if (Math.abs(c.getAlpha() * t.getAlpha() - s.bakedAlpha) > 0.004f) return true;
        if (!s.transform(CHECK)) return false;
        CHECK.getValues(CHECK_VALUES);
        for (int i = 0; i < 9; i++) {
            float limit = i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y ? 0.5f : 0.001f;
            if (Math.abs(CHECK_VALUES[i] - s.baked[i]) > limit) return true;
        }
        return false;
    }

    static void sync() {
        if (SLOTS.isEmpty()) return;
        // Reused every frame: the pre-draw pass allocates nothing.
        SYNC_LIST.addAll(SLOTS.values());
        for (Slot s : SYNC_LIST) {
            View parent = s.parent != null ? s.parent.get() : null;
            if (parent == null || !parent.isAttachedToWindow()) continue;
            s.glass.update(parent);
            if (s.bakedAlpha >= 0f && changed(s)) {
                // The cell or the folder animated on its own: the cell layout draws the glass
                // again with it, or the glass stays at its old size and place.
                s.bakedAlpha = -1f;
                parent.invalidate();
            }
        }
        SYNC_LIST.clear();
    }

    /** Redraws every cell that has glass, after a settings change. */
    static void invalidateAll() {
        for (Slot s : new ArrayList<>(SLOTS.values())) {
            View parent = s.parent != null ? s.parent.get() : null;
            if (parent != null) parent.invalidate();
        }
    }
}
