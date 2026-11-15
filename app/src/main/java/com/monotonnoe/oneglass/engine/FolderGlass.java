package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The panel of an open folder as liquid glass. One UI's folder classes vary between versions,
 * so the panel is found from the first icon drawn inside it ({@link #onIcon}): the nearest view
 * around the icon, among its ancestors and their children, that paints a background and is
 * larger than an icon but not the whole screen. Its background is then drawn as glass, tinted
 * light or dark with the theme (or the chosen glass colour), and stays so if One UI sets it again.
 */
final class FolderGlass extends Drawable implements Drawable.Callback {
    /** How far up from an icon to look for the panel. */
    private static final int MAX_UP = 10;
    /** Panels found, by view: their glass background. */
    private static final Map<View, FolderGlass> PANELS = new WeakHashMap<>();
    /** Searches from an icon parent (the folder's cells): how many, or -1 once found. */
    private static final Map<ViewParent, Integer> SEARCHED = new WeakHashMap<>();
    /** Folders open with an animation, before which the panel may not be laid out yet. */
    private static final int MAX_SEARCHES = 5;
    private static boolean reported;

    private final WeakReference<View> viewRef;
    private final Drawable original;
    private final LiquidGlass.Glass glass = new LiquidGlass.Glass();
    private final RectF rect = new RectF();

    private FolderGlass(View view, Drawable original) {
        this.viewRef = new WeakReference<>(view);
        this.original = original;
        original.setCallback(this);
    }

    /** An app icon in an open folder was drawn with glass: find the folder's panel once. */
    static void onIcon(View icon) {
        ViewParent cells = icon.getParent();
        if (!Config.glassFolderPanel || cells == null) return;
        Integer searches = SEARCHED.get(cells);
        int n = searches == null ? 0 : searches;
        if (n < 0 || n >= MAX_SEARCHES) return;
        SEARCHED.put(cells, n + 1);
        int[] at = new int[2];
        icon.getLocationOnScreen(at);
        float cx = at[0] + icon.getWidth() / 2f;
        float cy = at[1] + icon.getHeight() / 2f;
        int size = Math.max(1, icon.getWidth());
        int screen = icon.getResources().getDisplayMetrics().widthPixels;
        StringBuilder seen = new StringBuilder();
        // The largest panel round the icon: One UI 8.5's open folder has its dark panel
        // behind the cells, which have a background of their own (6.41 took the cells).
        View best = null;
        ViewParent p = cells;
        for (int up = 0; up < MAX_UP && p instanceof View; up++, p = p.getParent()) {
            View a = (View) p;
            seen.append(' ').append(a.getClass().getSimpleName())
                    .append(a.getBackground() != null ? "(bg)" : "");
            best = larger(best, panel(a, cx, cy, size, screen));
            if (a instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) a;
                for (int i = 0; i < g.getChildCount(); i++) {
                    best = larger(best, panel(g.getChildAt(i), cx, cy, size, screen));
                }
            }
        }
        if (best != null) {
            SEARCHED.put(cells, -1);
            boolean fresh = !(best.getBackground() instanceof FolderGlass);
            install(best);
            if (fresh) {
                XLog.i("folder panel: " + best.getClass().getName() + " " + best.getWidth()
                        + "x" + best.getHeight() + "; from the icon up:" + seen);
            }
            return;
        }
        if (n + 1 < MAX_SEARCHES) return;
        XLog.i("no folder panel found, from the icon up:" + seen);
        if (!reported) {
            reported = true;
            LiquidGlass.report(icon.getContext(), "folder icons drawn with the glass, but no"
                    + " folder panel found. Views from the icon up:" + seen);
        }
    }

    /** The folders' titles, by panel. */
    private static final Map<View, WeakReference<TextView>> TITLES = new WeakHashMap<>();

    /**
     * The folder's name as iOS shows it: above the panel, at its left, bold. The title is
     * the largest text over the panel, outside it, in the views round it; it is moved by its
     * translation (so it is tapped where it shows), towards where it should be each frame
     * the panel draws, so it follows the panel as it opens.
     */
    private static void placeTitle(View panel) {
        WeakReference<TextView> ref = TITLES.get(panel);
        TextView t = ref != null ? ref.get() : null;
        if (t == null) {
            t = findTitle(panel);
            if (t == null) return;
            TITLES.put(panel, new WeakReference<>(t));
            t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                    700, false));
            XLog.debug("folder title: " + t.getClass().getSimpleName() + " \"" + t.getText()
                    + "\" " + t.getWidth() + "x" + t.getHeight() + ", "
                    + Math.round(t.getTextSize()) + " px");
        }
        if (!t.isAttachedToWindow() || t.getWidth() == 0) return;
        float d = panel.getResources().getDisplayMetrics().density;
        int[] p = new int[2];
        int[] at = new int[2];
        panel.getLocationOnScreen(p);
        t.getLocationOnScreen(at);
        float lineLeft = t.getLayout() != null ? t.getLayout().getLineLeft(0) : 0f;
        float textLeft = at[0] + t.getTotalPaddingLeft() + lineLeft;
        float baseline = at[1] + t.getBaseline();
        float dx = p[0] + 10f * d - textLeft;
        float dy = p[1] - 22f * d - baseline;
        if (Math.abs(dx) > 0.5f) t.setTranslationX(t.getTranslationX() + dx);
        if (Math.abs(dy) > 0.5f) t.setTranslationY(t.getTranslationY() + dy);
    }

    /** The largest text above the panel, in the views round it (not in the panel). */
    private static TextView findTitle(View panel) {
        int[] p = new int[2];
        panel.getLocationOnScreen(p);
        TextView best = null;
        ViewParent up = panel.getParent();
        for (int i = 0; i < 4 && up instanceof ViewGroup; i++, up = up.getParent()) {
            best = biggerTitle(best, search((ViewGroup) up, panel, p[1], 0));
        }
        return best;
    }

    private static TextView search(ViewGroup g, View panel, int panelTop, int depth) {
        TextView best = null;
        if (depth > 5) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == panel || c.getVisibility() != View.VISIBLE) continue;
            if (c instanceof TextView) {
                TextView t = (TextView) c;
                int[] at = new int[2];
                t.getLocationOnScreen(at);
                if (t.getText() != null && t.getText().length() > 0
                        && at[1] + t.getHeight() / 2 < panelTop
                        && t.getTextSize() >= 18f * t.getResources().getDisplayMetrics().density) {
                    best = biggerTitle(best, t);
                }
            } else if (c instanceof ViewGroup) {
                best = biggerTitle(best, search((ViewGroup) c, panel, panelTop, depth + 1));
            }
        }
        return best;
    }

    private static TextView biggerTitle(TextView a, TextView b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.getTextSize() > a.getTextSize() ? b : a;
    }

    private static View larger(View a, View b) {
        if (a == null) return b;
        if (b == null) return a;
        return (long) b.getWidth() * b.getHeight() > (long) a.getWidth() * a.getHeight() ? b : a;
    }

    /** {@code v} when it looks like the panel around the icon at {@code cx, cy}. */
    private static View panel(View v, float cx, float cy, int icon, int screen) {
        if (v.getVisibility() != View.VISIBLE || v instanceof TextView) return null;
        Drawable bg = v.getBackground();
        if (bg == null) return null;
        if (bg instanceof FolderGlass) return v;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w < icon * 2.5f || h < icon * 1.5f || w >= screen * 0.97f) return null;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return cx >= at[0] && cx <= at[0] + w && cy >= at[1] && cy <= at[1] + h ? v : null;
    }

    private static void install(View panel) {
        Drawable bg = panel.getBackground();
        if (bg instanceof FolderGlass) return;
        FolderGlass g = new FolderGlass(panel, bg);
        PANELS.put(panel, g);
        panel.setBackground(g);
    }

    /** A background One UI sets again on a panel: keep it as glass. */
    static Drawable wrap(View view, Drawable background) {
        if (background == null || background instanceof FolderGlass) return background;
        if (!PANELS.containsKey(view)) return background;
        FolderGlass g = new FolderGlass(view, background);
        PANELS.put(view, g);
        return g;
    }

    /** Redraws the panels after a settings change. */
    static void invalidateAll() {
        for (View v : new ArrayList<>(PANELS.keySet())) {
            if (v != null) v.invalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        View v = viewRef.get();
        Rect b = getBounds();
        if (v == null || b.isEmpty() || !Config.glassFolderPanel
                || Settings.style != Settings.STYLE_LIQUID || !LiquidGlass.available()
                || !canvas.isHardwareAccelerated()) {
            original.draw(canvas);
            return;
        }
        rect.set(b);
        // iOS's folder: corners about an eighth of the panel's side, whatever One UI's are.
        float corner = Math.min(b.width(), b.height()) * 0.125f;
        // A panel is large: a narrow lens band, pulling about as far as it is wide.
        glass.lensHeight = Config.folderPanelLens;
        glass.lensAmount = Config.folderPanelLens;
        // A light frost, as iOS's folder panel, in a light or a dark theme alike.
        int argb = Config.folderPanelTint > 0
                ? (Math.min(255, Config.folderPanelTint) << 24) | 0xFFFFFF : 0;
        glass.tint = argb;
        placeTitle(v);
        // One UI grows the panel from the folder icon by scaling it: its glass grows out of
        // the icon's glass instead of being scaled down with it.
        glass.morphFromIcon = Config.folderMorph;
        try {
            if (glass.draw(canvas, v, rect, corner, 1f, Config.folderBlur)) {
                CellGlass.watch(v, v, glass);
                if (argb == 0 || !glass.tintDrawn) {
                    IconBlur.drawTint(canvas, v, rect, corner, Config.folderPanelTint);
                }
                return;
            }
        } catch (Throwable t) {
            XLog.e("folder glass", t);
        }
        original.draw(canvas);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        original.setBounds(bounds);
    }

    @Override
    public boolean isStateful() {
        return original.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return original.setState(state);
    }

    @Override
    public boolean getPadding(Rect padding) {
        return original.getPadding(padding);
    }

    @Override
    public void getOutline(Outline o) {
        original.getOutline(o);
    }

    @Override
    public int getIntrinsicWidth() {
        return original.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return original.getIntrinsicHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        original.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        original.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}
