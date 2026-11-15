package com.monotonnoe.oneglass.engine;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Background of a launcher search bar (the Apps screen one, the Home screen one): gray liquid
 * glass in the bar's own pill shape instead of its flat fill, while the bar is idle. Once search
 * is in use (the bar or its field focused or pressed, the search screen open) it shows its
 * original background again, as does anything that is not a pill (the bar's container), and the
 * glass when it is off or unavailable.
 *
 * <p>The glass is opaque (it is a piece of the wallpaper), so it must be drawn under the bar's
 * text and icons. A bar that holds them draws it as its own background. A pill that is a layer
 * of its own, drawn over the text, hands the glass to its parent's background ({@link #forChild})
 * and draws nothing itself.
 */
final class SearchGlass extends Drawable implements Drawable.Callback {
    /**
     * Gray over the glass, like iOS 26 search fields; its opacity is the search gray setting
     * (also the settings screen's preview).
     */
    static final int GRAY = 0x8E8E93;
    /** Search Pill's pill's lens band and pull (also the settings screen's preview). */
    static final float HOME_PILL_LENS_HEIGHT = 0.18f;
    static final float HOME_PILL_LENS_AMOUNT = 0.10f;
    /** A search bar is a wide pill: rounded by at least this fraction of its height. */
    private static final float MIN_ASPECT = 2.5f;
    private static final float MIN_ROUNDNESS = 0.3f;
    private static final Map<SearchGlass, Boolean> ALL = new WeakHashMap<>();

    private final WeakReference<View> viewRef;
    private final Drawable original;
    /** Set on a parent's background that draws the glass for its pill layer {@code child}. */
    private final SearchGlass child;
    private final LiquidGlass.Glass glass = new LiquidGlass.Glass();
    private final Outline outline = new Outline();
    private final RectF rect = new RectF();
    private final Paint gray = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Whether the last frame showed the glass, to redraw when search starts or ends. */
    private boolean showing;
    /** Whether search was in use at the last draw: a redraw is due only when that changes. */
    private boolean drawnActive;
    /** A pill layer: its parent drew the glass under it this frame. */
    private boolean delegated;
    private boolean parentInstalled;
    private int drawableAlpha = 255;

    private SearchGlass(View view, Drawable original, SearchGlass child) {
        this.viewRef = new WeakReference<>(view);
        this.original = original;
        this.child = child;
        original.setCallback(this);
        ALL.put(this, Boolean.TRUE);
    }

    /** The background to give {@code view}: wrapped when the view is a search bar. */
    static Drawable wrap(View view, Drawable background) {
        if (background == null || background instanceof SearchGlass) return background;
        if (!isSearchBar(view)) return background;
        // The bar's previous wrapper, if any, is done with: out of the synced set at once.
        Drawable old = view.getBackground();
        if (old instanceof SearchGlass) ALL.remove(old);
        return new SearchGlass(view, background, null);
    }

    /** By its class or its resource id: One UI names them "...Search..." / "search_...". */
    private static boolean isSearchBar(View view) {
        if (view.getClass().getName().toLowerCase(Locale.ROOT).contains("search")) return true;
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            Resources r = view.getResources();
            return r.getResourceEntryName(id).toLowerCase(Locale.ROOT).contains("search");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Search is in use: the bar or its field has focus or is pressed, or the search screen. */
    private static boolean active(View v) {
        if (v.hasFocus() || v.isPressed() || v.isActivated()) return true;
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (isFinder(p.getClass())) return true;
        }
        return false;
    }

    /** Classes of One UI's search screen, by name; asked every frame, so remembered. */
    private static final Map<Class<?>, Boolean> FINDER = new java.util.HashMap<>();

    private static boolean isFinder(Class<?> c) {
        Boolean b = FINDER.get(c);
        if (b == null) {
            b = c.getName().toLowerCase(Locale.ROOT).contains("finder");
            FINDER.put(c, b);
        }
        return b;
    }

    private static boolean enabled(Canvas canvas) {
        return Config.glassSearch && Settings.style == Settings.STYLE_LIQUID
                && LiquidGlass.available() && canvas.isHardwareAccelerated();
    }

    /** The pill's corner radius for these bounds, or -1 when it is not a pill. */
    private float pillCorner(Rect b) {
        // Search Pill's capsule is one whatever its width (its settings make it narrower).
        View v = pill();
        boolean homePill = v != null && v.getClass().getName().startsWith("dev.flexy.searchpill.");
        if (b.height() <= 0 || (!homePill && b.width() < b.height() * MIN_ASPECT)) return -1f;
        original.getOutline(outline);
        float corner = Math.min(b.height() / 2f, outline.getRadius());
        return corner >= b.height() * MIN_ROUNDNESS ? corner : -1f;
    }

    /** The pill's view: the bar itself, or for a parent's background, the pill layer. */
    private View pill() {
        return child != null ? child.viewRef.get() : viewRef.get();
    }

    /** Every bar drawn again: the picture or the settings changed what it can show. Main thread. */
    static void invalidateAll() {
        ArrayList<SearchGlass> list = new ArrayList<>(ALL.keySet());
        for (SearchGlass g : list) {
            View v = g.viewRef.get();
            if (v != null) v.invalidate();
        }
    }

    /** Redraws bars whose search state changed since their last frame. Pre-draw, main thread. */
    private static final ArrayList<SearchGlass> SYNC_LIST = new ArrayList<>();

    static void sync() {
        if (ALL.isEmpty()) return;
        // Reused every frame: the pre-draw pass allocates nothing.
        ArrayList<SearchGlass> list = SYNC_LIST;
        list.clear();
        list.addAll(ALL.keySet());
        for (SearchGlass g : list) {
            View v = g.viewRef.get();
            View pill = g.pill();
            if (v == null || pill == null || !v.isAttachedToWindow()) continue;
            // A wrapper the bar no longer draws (its background was set again; the old one
            // stays here until collected) or a bar under a hidden parent never draws, so its
            // drawnActive never catches up: asking for a frame would ask for one on every
            // pass, a redraw loop at the display's rate until the next collection.
            if (v.getBackground() != g || !v.isShown()) continue;
            // Only a change of search state needs a redraw. Comparing with whether the glass
            // showed redrew, every frame, a bar that could not have glass (not a pill,
            // delegated to its parent), and kept the launcher drawing frames at rest.
            if (g.drawnActive != active(pill)) v.invalidate();
        }
        list.clear();
    }

    @Override
    public void draw(Canvas canvas) {
        if (child != null) {
            forChild(canvas);
            return;
        }
        View v = viewRef.get();
        boolean act = v != null && active(v);
        drawnActive = act;
        float corner = pillCorner(getBounds());
        // OneGlass: the One UI apps screen (its search bar too) stays stock
        if (v == null || corner < 0f || !enabled(canvas) || act
                || com.monotonnoe.oneglass.glass.HomeScope.inAppsScreen(v)) {
            showing = false;
            original.draw(canvas);
            return;
        }
        if (!(v instanceof ViewGroup) || ((ViewGroup) v).getChildCount() == 0) {
            // A pill layer over the bar's text: the parent draws the glass under both.
            installParent(v);
            showing = delegated;
            if (!delegated) original.draw(canvas);
            return;
        }
        rect.set(getBounds());
        showing = drawGlass(canvas, v, v, rect, corner, 1f);
        if (!showing) original.draw(canvas);
    }

    /** A parent's background: its own, then the glass for its pill layer. */
    private void forChild(Canvas canvas) {
        original.draw(canvas);
        View parent = viewRef.get();
        View pill = child.viewRef.get();
        boolean was = child.delegated;
        child.delegated = false;
        boolean act = pill != null && active(pill);
        drawnActive = act;
        if (parent != null && pill != null && pill.getVisibility() == View.VISIBLE
                && pill.getParent() == parent && enabled(canvas) && !act) {
            float corner = child.pillCorner(child.getBounds());
            if (corner >= 0f) {
                float left = pill.getLeft() + pill.getTranslationX();
                float top = pill.getTop() + pill.getTranslationY();
                rect.set(left, top, left + pill.getWidth(), top + pill.getHeight());
                child.delegated = drawGlass(canvas, parent, pill, rect, corner, pill.getAlpha());
            }
        }
        showing = child.delegated;
        if (pill != null && was != child.delegated) pill.invalidate();
    }

    private boolean drawGlass(Canvas canvas, View drawnBy, View pill, RectF r, float corner,
                              float alpha) {
        // Search Pill's pill sits on the Home screen's wallpaper, like the icons: their clear
        // liquid glass, half as gray. Search bars are nearly colourless, over the blurred
        // Apps and search screens.
        boolean homePill = pill.getClass().getName().startsWith("dev.flexy.searchpill.");
        glass.saturation = homePill ? -1f : Config.searchSaturation;
        // A small capsule: a narrow, gentle lens, so its round ends stay round (the icons'
        // lens would swell its edge into a wide bright band).
        glass.lensHeight = homePill ? HOME_PILL_LENS_HEIGHT : -1f;
        glass.lensAmount = homePill ? HOME_PILL_LENS_AMOUNT : Config.searchLens;
        glass.zoom = 1f;
        float blur = homePill ? Config.glassBlur : Config.searchBlur;
        float grayness = homePill ? Config.searchGray * 0.5f : Config.searchGray;
        int a = Math.max(0, Math.min(255, Math.round(grayness * alpha * drawableAlpha)));
        glass.tint = a > 0 ? (a << 24) | (GRAY & 0xFFFFFF) : 0;
        try {
            if (glass.draw(canvas, drawnBy, r, corner, alpha, blur)) {
                CellGlass.watch(pill, drawnBy, glass);
                if (a > 0 && !glass.tintDrawn) {
                    gray.setColor((a << 24) | GRAY);
                    canvas.drawRoundRect(r, corner, corner, gray);
                }
                return true;
            }
        } catch (Throwable t) {
            XLog.e("search glass", t);
        }
        return false;
    }

    /** Gives the parent of a pill layer a background that draws the glass under the layer. */
    private void installParent(View v) {
        if (parentInstalled) return;
        parentInstalled = true;
        v.post(() -> {
            ViewParent p = v.getParent();
            if (!(p instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) p;
            Drawable bg = parent.getBackground();
            if (bg instanceof SearchGlass) {
                // Already drawing glass for a pill layer, or just wrapped as a (non-pill)
                // search container itself: keep its original background under the glass.
                if (((SearchGlass) bg).child != null) return;
                bg = ((SearchGlass) bg).original;
            }
            parent.setBackground(new SearchGlass(parent,
                    bg != null ? bg : new ColorDrawable(0), this));
        });
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
        drawableAlpha = alpha;
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
