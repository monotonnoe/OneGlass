package com.monotonnoe.oneglass.engine;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Live wallpaper blur behind One UI Home app icons.
 *
 * <p>Samsung's {@code View#semSetBlurInfo} mode 0 (what OneUI LiveBlur uses) is backed by
 * {@code ViewRootImpl#createBackgroundBlurDrawable()}: a drawable whose RenderNode reports its
 * on-screen position to SurfaceFlinger as a blur region. Instead of giving the whole cell view a
 * blur background, the icon's compound drawable is wrapped so that this blur drawable is drawn
 * right under the icon bitmap, inside the same canvas. The blur therefore follows every launcher
 * animation of the icon (paging, bounce, folder and app transitions) for free.
 *
 * <p>The blur takes the outline of the icon itself, measured from its pixels
 * ({@link IconOutline}), so it sits exactly behind whatever shape the icon pack draws.
 *
 * <p>The compositor does not see view alpha, so a pre-draw pass mirrors the effective alpha and
 * visibility of each icon onto its blur region.
 */
final class IconBlur {
    private static final Map<View, Holder> HOLDERS = new WeakHashMap<>();
    private static final Map<ViewTreeObserver, Boolean> OBSERVERS = new WeakHashMap<>();
    private static final ViewTreeObserver.OnPreDrawListener SYNC = () -> {
        syncAll();
        return true;
    };

    private static Method getViewRootImpl;
    private static Method createBlur;
    private static Method setBlurRadius;
    private static Method setCornerRadius;
    private static Method setBlurColor;
    private static Method setPorterDuffMode;
    private static boolean reflectionFailed;

    private IconBlur() {
    }

    /** Wraps the drawable a launcher icon view is about to show as its compound drawable. */
    static Drawable wrap(TextView view, Drawable icon) {
        if (icon == null) return icon;
        if (icon instanceof Wrapper) {
            // The launcher puts back the wrapper it read from the view: the icon shows again.
            ((Wrapper) icon).holder.placeholder = false;
            return icon;
        }
        if (icon instanceof android.graphics.drawable.ColorDrawable) {
            // Transparent placeholder used while the icon is hidden (drag, app launch).
            Holder hidden = HOLDERS.get(view);
            if (hidden != null) hidden.placeholder = true;
            return icon;
        }
        Holder h = HOLDERS.get(view);
        if (h == null) {
            h = new Holder(view);
            HOLDERS.put(view, h);
            holdersGen++;
            view.addOnAttachStateChangeListener(h);
            if (view.isAttachedToWindow()) h.onViewAttachedToWindow(view);
        }
        h.placeholder = false;
        Wrapper current = h.current;
        if (current != null && current.inner == icon) {
            // TextView lays out the icon and label from the compound drawable's bounds, and the
            // launcher resizes the inner icon directly (item size changes, view recycling).
            current.setBounds(icon.getBounds());
            return current;
        }
        Wrapper w = new Wrapper(h, icon);
        h.current = w;
        // Another icon in this view (recycled for another app): nothing to fade in.
        h.hiddenAt = 0;
        return w;
    }

    private static boolean folderLogged;

    /**
     * The glass under a folder icon on the Home screen, as under the icons beside it: drawn
     * before the folder icon draws itself, under its top drawable (the folder's own frosted
     * square and its small icons stay on top; a folder icon is not wrapped, its drawable is
     * the launcher's).
     */
    static void drawFolderIconGlass(TextView v, Canvas canvas) {
        Drawable[] d = v.getCompoundDrawables();
        Drawable top = d.length > 1 ? d[1] : null;
        if (!folderLogged) {
            folderLogged = true;
            XLog.i("folder icon " + v.getClass().getName() + " " + v.getWidth() + "x"
                    + v.getHeight() + ": top " + (top == null ? "none"
                    : top.getClass().getName() + " " + top.getBounds()) + ", background "
                    + (v.getBackground() == null ? "none" : v.getBackground().getClass()
                    .getName()));
        }
        if (top == null || top.getBounds().isEmpty()) return;
        Holder h = HOLDERS.get(v);
        if (h == null) {
            h = new Holder(v);
            HOLDERS.put(v, h);
            holdersGen++;
            v.addOnAttachStateChangeListener(h);
            if (v.isAttachedToWindow()) h.onViewAttachedToWindow(v);
        }
        // Not measured: a folder's outline is not an app icon's, and measuring it would make
        // it the outline the folder glass (CellGlass) takes for the pack's app icons.
        IconOutline outline = null;
        // Where TextView draws its top drawable: centred across, under the top padding.
        Rect b = top.getBounds();
        float hspace = v.getWidth() - v.getCompoundPaddingLeft() - v.getCompoundPaddingRight();
        float x = v.getScrollX() + v.getCompoundPaddingLeft() + (hspace - b.width()) / 2f;
        float y = v.getScrollY() + v.getPaddingTop();
        int save = canvas.save();
        canvas.translate(x, y);
        try {
            h.drawBlur(canvas, b, outline);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Redraws every icon so a new setting shows immediately. The peeled glyphs stay: they
     * depend only on the icon (no setting nor picture changes them), and dropping them
     * showed every pack icon with its own glass until it was peeled again.
     */
    static void onSettingsChanged() {
        ArrayList<Holder> list;
        synchronized (HOLDERS) {
            list = new ArrayList<>(HOLDERS.values());
        }
        for (Holder h : list) {
            // The Apps screen switch changes which icons are in scope.
            h.inScope = null;
            TextView v = h.viewRef.get();
            if (v != null) v.invalidate();
        }
    }

    /** Redraws every icon with glass, nothing else changed (the theme switched). */
    static void redrawAll() {
        ArrayList<Holder> list;
        synchronized (HOLDERS) {
            list = new ArrayList<>(HOLDERS.values());
        }
        for (Holder h : list) {
            TextView v = h.viewRef.get();
            if (v != null) v.invalidate();
        }
    }

    /** Pack peel was switched off: the glyphs are no longer drawn, their bitmaps can go. */
    static void onPeelOff() {
        PEELS.evictAll();
        ArrayList<Holder> list;
        synchronized (HOLDERS) {
            list = new ArrayList<>(HOLDERS.values());
        }
        for (Holder h : list) {
            if (h.current != null) h.current.dropPeel();
        }
    }

    /**
     * The wallpaper moved under the icons: a frame is asked for, and its pre-draw pass records
     * again the glasses that are on screen (each sees the mapping changed). Off-screen glass,
     * the Apps screen's included, waits until it is shown.
     */
    static void onWallpaperMoved() {
        // The launcher may set the offsets from a worker thread: views only on the main one.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            MAIN.post(IconBlur::onWallpaperMoved);
            return;
        }
        View any = null;
        synchronized (HOLDERS) {
            for (Holder h : HOLDERS.values()) {
                TextView v = h.viewRef.get();
                if (v != null && v.isAttachedToWindow()) {
                    any = v;
                    break;
                }
            }
        }
        if (any != null) LiquidGlass.scheduleFrame(any);
    }

    /**
     * The tone the glass gets, as straight ARGB for the lens pass: light or dark with the
     * theme at {@code alpha}. 0 when there is none, or when a colour was chosen (that tint,
     * with its hue pass, is painted over the glass as before).
     */
    static int autoTint(View v, int alpha) {
        if (alpha <= 0 || Settings.tintColor != 0) return 0;
        boolean night = (v.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return (Math.min(255, alpha) << 24) | (night ? 0x101010 : 0xFFFFFF);
    }

    private static final Paint TINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final Paint HUE = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        HUE.setBlendMode(BlendMode.HUE);
    }

    /**
     * "Background opacity": a tone over the blur or glass, in its outline. The automatic tone is
     * light or dark with the system theme (as F1Live widgets do); a chosen colour also turns the
     * glass to its hue first, as AndroidLiquidGlass tints do. Shared by icons, folders, widgets.
     */
    static void drawTint(Canvas canvas, View v, RectF rect, float corner) {
        drawTint(canvas, v, rect, corner, Settings.backgroundAlpha);
    }

    /** As above, with its own tint alpha (the Apps screen, folder panels). */
    static void drawTint(Canvas canvas, View v, RectF rect, float corner, int alpha) {
        if (alpha <= 0) return;
        int color = Settings.tintColor & 0xFFFFFF;
        if (Settings.tintColor == 0) {
            boolean night = (v.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            color = night ? 0x101010 : 0xFFFFFF;
        } else {
            HUE.setColor((Math.min(255, alpha * 2) << 24) | color);
            canvas.drawRoundRect(rect, corner, corner, HUE);
        }
        TINT.setColor((alpha << 24) | color);
        canvas.drawRoundRect(rect, corner, corner, TINT);
    }

    /** As above, in an outline of its own (widgets): the path filled, no clip. */
    static void drawTint(Canvas canvas, View v, android.graphics.Path path, int alpha) {
        if (alpha <= 0) return;
        int color = Settings.tintColor & 0xFFFFFF;
        if (Settings.tintColor == 0) {
            boolean night = (v.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            color = night ? 0x101010 : 0xFFFFFF;
        } else {
            HUE.setColor((Math.min(255, alpha * 2) << 24) | color);
            canvas.drawPath(path, HUE);
        }
        TINT.setColor((alpha << 24) | color);
        canvas.drawPath(path, TINT);
    }

    /** Reused every frame, so the pre-draw pass allocates nothing. Main thread. */
    private static final ArrayList<Holder> SYNC_LIST = new ArrayList<>();

    /** Lines left for the log about icons drawn without their glass (it stays short). */
    private static int bareLines = 8;
    private static boolean bareStackLogged;
    private static String lastBare = "";

    /**
     * Logs, a few times per launch, an in-scope icon drawn without its glass: why, how long
     * after the Home screen came back, and for a software canvas who drew it and which view
     * holds a software layer, if any.
     */
    static void noteBare(View v, Canvas canvas, String why) {
        if (!Config.DEBUG || bareLines <= 0 || why.equals(lastBare)) return;
        lastBare = why;
        bareLines--;
        StringBuilder b = new StringBuilder("icon drawn without glass: ").append(why);
        long since = SystemClock.uptimeMillis() - LiquidGlass.resumedAt;
        if (LiquidGlass.resumedAt > 0) b.append("; ").append(since).append(" ms after the Home screen resumed");
        if (!canvas.isHardwareAccelerated()) {
            for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
                if (((View) p).getLayerType() == View.LAYER_TYPE_SOFTWARE) {
                    b.append("; software layer on ").append(p.getClass().getName());
                    break;
                }
            }
            if (!bareStackLogged) {
                bareStackLogged = true;
                b.append("; drawn by");
                int n = 0;
                for (StackTraceElement e : new Throwable().getStackTrace()) {
                    String c = e.getClassName();
                    if (c.startsWith("android.") || c.startsWith("java.") || c.startsWith("dalvik.")
                            || c.startsWith("com.android.internal.") || c.startsWith("de.robv.")
                            || c.startsWith("dev.flexy.") || c.startsWith("LSP")
                            || c.startsWith("org.lsposed.") || c.startsWith("J.")) {
                        continue;
                    }
                    b.append(' ').append(c).append('#').append(e.getMethodName());
                    if (++n >= 6) break;
                }
                if (n == 0) b.append(" the framework only");
            }
        }
        XLog.debug(b.toString());
    }

    private static int backLines = 4;

    /** Logs, a few times, the glass coming back after frames without it. */
    static void noteGlassBack(View v, long bareMs) {
        if (!Config.DEBUG || backLines <= 0) return;
        backLines--;
        long since = SystemClock.uptimeMillis() - LiquidGlass.resumedAt;
        XLog.debug("glass back after " + bareMs + " ms without it, fading in"
                + (LiquidGlass.resumedAt > 0 ? "; " + since + " ms after the Home screen resumed" : "")
                + " (" + v.getClass().getSimpleName() + ")");
    }

    private static boolean syncing;

    /**
     * The pager scrolled inside the draw (a fling, see {@link PagerScroll}): the glass is
     * synced again now, in this frame, for where the icons are drawn. Main thread.
     */
    static void onPagerScrolled() {
        if (syncing || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        syncing = true;
        try {
            LiquidGlass.scrollSyncs++;
            syncAll();
        } finally {
            syncing = false;
        }
    }

    private static void syncAll() {
        long t0 = System.nanoTime();
        android.os.Trace.beginSection("BlurIcons sync");
        LiquidGlass.syncStarting();
        try {
            syncAllTimed();
        } finally {
            android.os.Trace.endSection();
            long nanos = System.nanoTime() - t0;
            LiquidGlass.syncDone(nanos);
            LiquidGlass.onSync(nanos);
            GlassLight.afterSync(LiquidGlass.lightTakenPass + LiquidGlass.deferredPass);
        }
    }

    /**
     * Icons of one page together: their records share the lens shader's content, tint and
     * geometry, set only when they differ from the record before (the Home and Apps screens
     * both visible as one slides over the other would otherwise set them on every record).
     */
    private static final java.util.Comparator<Holder> BY_PAGE = (a, b) -> {
        View pa = a.anchor != null ? a.anchor.get() : null;
        View pb = b.anchor != null ? b.anchor.get() : null;
        return Integer.compare(System.identityHashCode(pa), System.identityHashCode(pb));
    };

    /**
     * Bumped when a holder is added or finds its page: the sorted list of holders is built
     * again only then (or when the map lost a collected view), not on every pass. Building
     * and sorting several hundred holders a pass, with the arrays that takes, was a good
     * part of the pass's time whether anything moved or not.
     */
    static int holdersGen;
    private static int listedSize = -1;
    private static int listedGen = -1;

    private static void syncAllTimed() {
        LiquidGlass.nextFrame();
        ArrayList<Holder> list = SYNC_LIST;
        synchronized (HOLDERS) {
            int n = HOLDERS.size();
            if (n != listedSize || holdersGen != listedGen) {
                list.clear();
                list.addAll(HOLDERS.values());
                if (list.size() > 1) list.sort(BY_PAGE);
                listedSize = n;
                listedGen = holdersGen;
            }
        }
        for (Holder h : list) {
            try {
                h.sync();
            } catch (Throwable t) {
                XLog.e("blur sync", t);
            }
        }
        try {
            CellGlass.sync();
            SearchGlass.sync();
        } catch (Throwable t) {
            XLog.e("cell glass sync", t);
        }
    }

    /** The Apps screen's own lens and saturation, or the Home screen's (the settings). */
    static void drawerLens(LiquidGlass.Glass g, boolean drawer) {
        g.lensHeight = drawer ? Config.drawerLensHeight : -1f;
        g.lensAmount = drawer ? Config.drawerLensAmount : -1f;
        g.saturation = drawer ? Config.drawerSaturation : -1f;
        // What is behind seen as it is in the middle: only the edge band bends it (6.89
        // magnified the whole icon 1.05x, as measured on iOS 27; asked away).
        g.zoom = drawer ? 1f : ICON_ZOOM;
    }

    /** The whole icon's magnification: none, the middle of the glass is flat. */
    static final float ICON_ZOOM = 1f;

    private static boolean resolveReflection() {
        if (createBlur != null) return true;
        if (reflectionFailed) return false;
        try {
            getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
            Class<?> vri = Class.forName("android.view.ViewRootImpl");
            createBlur = vri.getDeclaredMethod("createBackgroundBlurDrawable");
            createBlur.setAccessible(true);
            Class<?> bbd = Class.forName(
                    "com.android.internal.graphics.drawable.BackgroundBlurDrawable");
            setBlurRadius = bbd.getDeclaredMethod("setBlurRadius", int.class);
            setCornerRadius = bbd.getDeclaredMethod("setCornerRadius", float.class);
            setBlurColor = bbd.getDeclaredMethod("setColor", int.class);
            setPorterDuffMode = bbd.getDeclaredMethod("setPorterDuffMode", PorterDuff.Mode.class);
            XLog.i("BackgroundBlurDrawable API resolved");
            return true;
        } catch (Throwable t) {
            reflectionFailed = true;
            XLog.e("BackgroundBlurDrawable API unavailable, live blur disabled", t);
            return false;
        }
    }

    /** Per icon view state. Holds the view weakly: it lives as the value of a WeakHashMap. */
    static final class Holder implements View.OnAttachStateChangeListener {
        final WeakReference<TextView> viewRef;
        Wrapper current;
        Drawable blur;
        Object blurRoot;
        Boolean inScope;
        int blurAlpha = -1;
        final Rect blurBounds = new Rect();
        /** The outline the blur region was last shaped to. */
        IconOutline blurOutline;
        /** The blur region in canvas coordinates and its corner radius, for the background. */
        final RectF region = new RectF();
        float regionCorner;
        /** Liquid glass, when that style is on and the wallpaper is available. */
        LiquidGlass.Glass glass;
        /** In the Apps screen, over its blurred wallpaper. */
        boolean inDrawer;
        /** In an open folder, over its blurred background. */
        boolean inFolder;
        /** The cell layout (or folder, Apps screen) the icon sits in, found with the scope. */
        WeakReference<View> anchor;

        Holder(TextView view) {
            viewRef = new WeakReference<>(view);
        }

        /** In an open folder without glass under its icons: the panel is found all the same. */
        void findFolderPanel() {
            TextView v = viewRef.get();
            if (v == null || !Config.blur || !Config.glassFolderPanel || !v.isAttachedToWindow()) {
                return;
            }
            inScope(v);
            if (inFolder) FolderGlass.onIcon(v);
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            inScope = null;
            ViewTreeObserver vto = v.getViewTreeObserver();
            synchronized (HOLDERS) {
                if (!OBSERVERS.containsKey(vto)) {
                    OBSERVERS.put(vto, Boolean.TRUE);
                    vto.addOnPreDrawListener(SYNC);
                }
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            inScope = null;
            releaseBlur();
            releaseGlass();
        }

        private void releaseGlass() {
            if (glass != null) glass.release();
        }

        private void releaseBlur() {
            Drawable b = blur;
            blur = null;
            blurRoot = null;
            blurAlpha = -1;
            if (b != null) {
                // Drops the region from the window's blur aggregator.
                b.setVisible(false, false);
                b.setCallback(null);
            }
        }

        boolean inScope(View v) {
            Boolean s = inScope;
            if (s != null) return s;
            if (!v.isAttachedToWindow()) return false;
            boolean result = false;
            inDrawer = false;
            inFolder = false;
            anchor = null;
            for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
                String name = p.getClass().getName();
                if (name.endsWith(".WorkspaceCellLayout") || name.endsWith(".HotseatCellLayout")) {
                    result = true;
                    if (p instanceof View) anchor = new WeakReference<>((View) p);
                    if (name.endsWith(".WorkspaceCellLayout")) PagerScroll.watch((View) p);
                    break;
                }
                if (name.contains(".folder.") || name.contains("Folder")) {
                    // The small icons inside a folder icon stay as they are; an open folder
                    // gets glass like the Home screen.
                    if (!(p instanceof TextView) && !name.contains("FolderIcon")) {
                        result = Config.glassOpenFolder;
                        inFolder = true;
                        if (p instanceof View) {
                            anchor = new WeakReference<>((View) p);
                            if (result) PagerScroll.watch((View) p);
                        }
                    }
                    break;
                }
                if (name.contains(".verticalapplist.") || name.contains(".appscreen.")
                        || name.contains(".applist.")) {
                    result = Config.drawer;
                    inDrawer = true;
                    if (p instanceof View) {
                        anchor = new WeakReference<>((View) p);
                        if (result) PagerScroll.watch((View) p);
                    }
                    break;
                }
            }
            inScope = result;
            // The page found (or lost): the holders are listed by page again.
            holdersGen++;
            logScopeOnce(v, result);
            return result;
        }

        /** Once per kind of place: the icon's ancestors and what it gets there. */
        private void logScopeOnce(View v, boolean result) {
            StringBuilder chain = new StringBuilder();
            int n = 0;
            for (ViewParent p = v.getParent(); p != null && n < 6; p = p.getParent(), n++) {
                chain.append(n == 0 ? "" : " < ").append(p.getClass().getName());
            }
            String key = result + "|" + inDrawer + "|" + inFolder + "|" + chain;
            if (SCOPES.size() >= 16 || !SCOPES.add(key)) return;
            XLog.i("icon scope: " + v.getClass().getSimpleName() + " glass " + result
                    + (inDrawer ? ", apps screen" : "") + (inFolder ? ", open folder" : "")
                    + "; in " + chain);
        }

        /**
         * Mirrors visibility and the accumulated alpha of the icon onto its blur region, and
         * re-records the liquid glass of an icon that moved on screen (paging, animations).
         */
        /** When the icon was last seen hidden (uptime), 0 while it shows. */
        long hiddenAt;
        /** The view holds the launcher's transparent placeholder, not the icon. */
        boolean placeholder;

        void sync() {
            LiquidGlass.Glass g = glass;
            if (g != null) {
                TextView gv = viewRef.get();
                boolean attached = gv != null && gv.isAttachedToWindow();
                // The launch animation hides the icon and shows a plain copy in its place; when
                // the app has closed the copy goes and the icon is shown again. Its glass then
                // fades in rather than appearing at once. An icon that is hidden for a frame
                // or more and comes back counts; new icons, and pages scrolling in, do not.
                // A detached view does not count: its glass is released, and a recycled page
                // scrolling back in draws it anew, at once.
                if (!attached) {
                    hiddenAt = 0;
                } else {
                    float va = gv.getAlpha();
                    int da = current != null ? current.inner.getAlpha() : 255;
                    // One UI 8.5 hides the icon for an app's launch and close
                    // (InvisibleState.APP_CLOSING) by emptying its drawable's bounds, not with
                    // a placeholder: the wrapper then draws nothing, glass included.
                    boolean gone = current != null && current.inner.getBounds().isEmpty();
                    boolean hidden = placeholder || gone || gv.getVisibility() != View.VISIBLE
                            || va < 0.02f || da == 0;
                    if (hidden) {
                        if (hiddenAt == 0) hiddenAt = SystemClock.uptimeMillis();
                    } else if (hiddenAt != 0) {
                        long hiddenFor = SystemClock.uptimeMillis() - hiddenAt;
                        hiddenAt = 0;
                        // Shown at once after a while away (an app was open; a drag or a
                        // flicker of a frame or two is shorter) and on a page that is on
                        // screen: the glass fades in. Shown through the launcher's own alpha
                        // ramp: the node is inside the view's alpha and ramps with it.
                        if (hiddenFor >= 250 && va >= 0.98f && da >= 250 && gv.isShown()) {
                            g.fadeIn();
                        }
                    }
                    g.hidden = hidden;
                    // Nothing of the icon is drawn while its bounds are empty: its node is
                    // not recorded either, and the fade's clock starts when it shows again.
                    if (!gone) g.update(gv);
                }
            }
            Drawable b = blur;
            if (b == null) return;
            TextView v = viewRef.get();
            float a = 0f;
            if (v != null && current != null && v.isAttachedToWindow() && isInstalled(v)) {
                a = current.inner.getAlpha() / 255f;
                View node = v;
                while (node != null && a > 0f) {
                    if (node.getVisibility() != View.VISIBLE) {
                        a = 0f;
                        break;
                    }
                    a *= node.getAlpha();
                    ViewParent p = node.getParent();
                    node = p instanceof View ? (View) p : null;
                }
            }
            int alpha = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
            boolean reappears = blurAlpha == 0 && alpha > 0;
            setBlurAlpha(b, alpha);
            if (reappears && v != null) {
                // At alpha 0 the blur drawable drops its path and stops recording its RenderNode,
                // so the region keeps an empty rect until the icon view is drawn again.
                v.invalidate();
            }
        }

        private boolean isInstalled(TextView v) {
            Drawable[] d = v.getCompoundDrawables();
            for (Drawable x : d) if (x == current) return true;
            Drawable[] r = v.getCompoundDrawablesRelative();
            for (Drawable x : r) if (x == current) return true;
            return false;
        }

        private void setBlurAlpha(Drawable b, int alpha) {
            if (alpha != blurAlpha) {
                blurAlpha = alpha;
                b.setAlpha(alpha);
            }
        }


        /**
         * "Background opacity": a tone colour over the live blur, in the blur's own outline,
         * light or dark with the system theme (as F1Live widgets do).
         */
        private void drawBackground(Canvas canvas, View v) {
            drawTint(canvas, v, region, regionCorner, tintAlpha());
        }

        private int tintAlpha() {
            return inDrawer && Config.drawerTint > 0 ? Config.drawerTint
                    : Settings.backgroundAlpha;
        }

        /**
         * The compositor only takes a rounded rect: the icon's measured outline, pulled in a
         * little so the blur never shows past the icon's own edge. Without a measurement, the
         * best fit to One UI's superellipse (n = 2.7, inset 1.2%).
         */
        private void computeRegion(Rect icon, IconOutline outline) {
            float corner;
            if (outline != null) {
                region.set(icon.left + outline.left * icon.width(),
                        icon.top + outline.top * icon.height(),
                        icon.left + outline.right * icon.width(),
                        icon.top + outline.bottom * icon.height());
                float inset = Math.max(1f, icon.width() * Config.outlineInset);
                region.inset(inset, inset);
                corner = Math.max(0f, outline.corner * icon.width() - inset);
            } else {
                region.set(icon);
                float inset = icon.width() * Config.blurInset;
                region.inset(inset, inset);
                corner = Config.corner * region.width();
            }
            corner = Math.min(corner, Math.min(region.width(), region.height()) / 2f);
            regionCorner = corner;
        }

        private void applyShape(Drawable b) throws Exception {
            b.setBounds(Math.round(region.left), Math.round(region.top),
                    Math.round(region.right), Math.round(region.bottom));
            setCornerRadius.invoke(b, regionCorner);
        }

        /** Draws the glass under the icon; true when it did (the icon is on the Home screen). */
        boolean drawBlur(Canvas canvas, Rect iconBounds, IconOutline outline) {
            TextView v = viewRef.get();
            if (v == null || !Config.blur) return false;
            if (!canvas.isHardwareAccelerated()) {
                // Drawn into a software canvas (a software layer or a bitmap): no glass there,
                // and the icon shows as the pack drew it. Its glass fades in when it is drawn
                // on the screen's own canvas again.
                if (glass != null && inScope(v)) {
                    glass.markBare();
                    noteBare(v, canvas, "software canvas " + canvas.getClass().getName());
                }
                return false;
            }
            if (!inScope(v)) return false;
            boolean reshape = !blurBounds.equals(iconBounds) || blurOutline != outline;
            if (reshape) {
                blurBounds.set(iconBounds);
                blurOutline = outline;
                computeRegion(iconBounds, outline);
            }
            if (Settings.style != Settings.STYLE_LIQUID) {
                LiquidGlass.onDrawFailed(v.getContext(), "the Home screen has the Blur effect"
                        + " selected (style " + Settings.style + ")");
            } else if (!LiquidGlass.available()) {
                LiquidGlass.onDrawFailed(v.getContext(), "no picture loaded yet");
            } else {
                if (blur != null) releaseBlur();
                if (glass == null) glass = new LiquidGlass.Glass();
                glass.anchor = anchor;
                drawerLens(glass, inDrawer);
                float glassBlur = inDrawer ? Config.drawerBlur
                        : inFolder ? Config.folderBlur : Config.glassBlur;
                int tintAlpha = tintAlpha();
                int tint = autoTint(v, tintAlpha);
                glass.tint = tint;
                try {
                    if (!inDrawer && !inFolder) {
                        LiquidGlass.iconMin = Math.min(region.width(), region.height());
                        LiquidGlass.iconCorner = regionCorner;
                    }
                    if (glass.draw(canvas, v, region, regionCorner, 1f, glassBlur)) {
                        LiquidGlass.watchFrames(v);
                        LiquidGlass.onDrawn(v.getContext());
                        if (inFolder) FolderGlass.onIcon(v);
                        if (tint == 0 || !glass.tintDrawn) {
                            // The tone painted here fades with the glass under it.
                            drawTint(canvas, v, region, regionCorner,
                                    Math.round(tintAlpha * glass.fadeAlpha()));
                        }
                        // While the glass fades the icon itself is redrawn each frame, for
                        // the tone (the glass node alone is re-recorded by the sync).
                        if (glass.fading()) v.invalidate();
                        return true;
                    }
                    LiquidGlass.onDrawFailed(v.getContext(), glass.failure);
                } catch (Throwable t) {
                    XLog.e("liquid glass draw", t);
                    Settings.style = Settings.STYLE_BLUR;
                    StringBuilder where = new StringBuilder(t.toString());
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < Math.min(3, st.length); i++) {
                        where.append(" at ").append(st[i]);
                    }
                    LiquidGlass.onDrawFailed(v.getContext(), where.toString());
                }
            }
            if (glass != null && Settings.style == Settings.STYLE_LIQUID) {
                noteBare(v, canvas, "glass failed: " + glass.failure);
                releaseGlass();
                glass.markBare();
            } else {
                releaseGlass();
            }
            if (!resolveReflection()) return false;
            try {
                Object root = getViewRootImpl.invoke(v);
                if (root == null) return false;
                if (blur == null || blurRoot != root) {
                    releaseBlur();
                    Drawable b = (Drawable) createBlur.invoke(root);
                    if (b == null) return false;
                    b.setCallback(v);
                    // Its own paint is a transparent SRC "hole"; don't erase what the view drew.
                    setPorterDuffMode.invoke(b, PorterDuff.Mode.SRC_OVER);
                    setBlurRadius.invoke(b, Config.blurRadius);
                    setBlurColor.invoke(b, Config.blurColor);
                    blur = b;
                    blurRoot = root;
                    blurAlpha = 255;
                    reshape = true;
                }
                Drawable b = blur;
                if (reshape) applyShape(b);
                b.draw(canvas);
                drawBackground(canvas, v);
                return true;
            } catch (Throwable t) {
                XLog.e("blur draw", t);
                reflectionFailed = true;
                createBlur = null;
                releaseBlur();
            }
            return false;
        }
    }

    /** Pack icons are peeled here, one at a time, never on the main thread. */
    private static final java.util.concurrent.ExecutorService PEEL_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BlurIconsPeel");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * Peel results by icon name, picture and size, so an icon view the launcher makes again
     * (back from an app, another page) draws the glyph from its first frame instead of the
     * pack's icon for a moment, and the same icon is never peeled twice. The picture is the
     * launcher's icon bitmap (shared by the views of one app): two apps of one name, or an
     * icon the launcher replaced (a new pack, an updated app), never share a glyph. NONE
     * marks an icon that carried no pack glass.
     */
    private static final Object NONE = new Object();
    /** The kinds of place already logged by logScopeOnce. */
    private static final java.util.Set<String> SCOPES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final android.util.LruCache<String, Object> PEELS =
            new android.util.LruCache<>(64);

    /**
     * A peeled glyph cropped to its own box (plus a clear margin, so filtered draws under a
     * press or zoom scale match the full-size picture) and where that box sits in the icon.
     * The rest of the icon is clear, and a cache of 64 full-size pictures held mostly
     * transparent pixels.
     */
    static final class Peel {
        final Bitmap bmp;
        final int dx;
        final int dy;

        Peel(Bitmap bmp, int dx, int dy) {
            this.bmp = bmp;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** The peeled picture cut down to its visible pixels; the full one is recycled. */
    static Peel crop(Bitmap full) {
        int w = full.getWidth();
        int h = full.getHeight();
        int[] px = new int[w * h];
        full.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, btm = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) != 0) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > btm) btm = y;
                }
            }
        }
        if (r < 0) return new Peel(full, 0, 0);
        final int margin = 2;
        l = Math.max(0, l - margin);
        t = Math.max(0, t - margin);
        r = Math.min(w - 1, r + margin);
        btm = Math.min(h - 1, btm + margin);
        int cw = r - l + 1;
        int ch = btm - t + 1;
        if (cw * (long) ch > w * (long) h * 85 / 100) return new Peel(full, 0, 0);
        Bitmap cut = Bitmap.createBitmap(full, l, t, cw, ch);
        if (cut != full) full.recycle();
        return new Peel(cut, l, t);
    }

    /** Compound drawable: live blur region, then the launcher's icon on top. */
    static final class Wrapper extends Drawable implements Drawable.Callback {
        final Holder holder;
        final Drawable inner;
        private IconOutline outline;
        private boolean measured;
        /** A glass icon pack's glyph without the pack's glass, at the size it was tried. */
        private Peel peeled;
        /** A peel is running or has run for the current size: no second one until it is dropped. */
        private boolean peelTried;
        private long peeledAt;
        private int peelW;
        private int peelH;
        private final Paint peelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Wrapper(Holder holder, Drawable inner) {
            this.holder = holder;
            this.inner = inner;
            setBounds(inner.getBounds());
            inner.setCallback(this);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = inner.getBounds();
            if (b.isEmpty()) return;
            if (!measured) {
                measured = true;
                outline = IconOutline.of(inner);
            }
            // The pack's glass comes off only where this glass went on: the Home screen. In
            // folders without glass, the Edge panel, Recents and anywhere else the icon stays
            // as the pack drew it (6.33-6.35 peeled folders bare: undone).
            boolean glassed = holder.drawBlur(canvas, b, outline);
            // An open folder's panel gets its glass whether or not its icons do.
            if (!glassed) holder.findFolderPanel();
            if (Config.packPeel && glassed) {
                boolean resized = peelW != b.width() || peelH != b.height();
                // One peel per size, off the main thread. An icon that keeps invalidating (a
                // press, an animation) is looked at again at most once a second, and only if
                // it was a pack icon before: one that was not stays as it is until it resizes.
                if (resized || (!peelTried && peeled != null
                        && SystemClock.uptimeMillis() - peeledAt > 1000)) {
                    peelTried = true;
                    peeledAt = SystemClock.uptimeMillis();
                    peelW = b.width();
                    peelH = b.height();
                    String key = peelKey(b);
                    Object known = resized && key != null ? PEELS.get(key) : null;
                    if (known != null) {
                        peeled = known == NONE ? null : (Peel) known;
                    } else {
                        startPeel(b, key);
                    }
                }
                if (peeled != null) {
                    int alpha = inner.getAlpha();
                    LiquidGlass.Glass g = holder.glass;
                    float f = g != null && g.fading() ? g.fadeAlpha() : 1f;
                    if (f < 1f) {
                        // The glass is coming in: the icon as the pack drew it (what showed
                        // while the glass was off) goes out as its glyph comes in.
                        inner.setAlpha(Math.round(alpha * (1f - f)));
                        inner.draw(canvas);
                        inner.setAlpha(alpha);
                    }
                    peelPaint.setAlpha(Math.round(alpha * f));
                    canvas.drawBitmap(peeled.bmp, b.left + peeled.dx, b.top + peeled.dy,
                            peelPaint);
                    return;
                }
            }
            inner.draw(canvas);
        }

        /**
         * Snapshots the icon now (cheap) and peels the snapshot on the worker; the result
         * lands here on the main thread and the icon is redrawn. Until then the icon shows as
         * the pack drew it.
         */
        /** The icon's name, picture and size: what a peel result is remembered by. */
        private String peelKey(Rect b) {
            TextView v = holder.viewRef.get();
            CharSequence text = v != null ? v.getText() : null;
            if (text == null || text.length() == 0) return null;
            return text + "|" + source() + "|" + b.width() + "x" + b.height();
        }

        /** The picture the icon draws: its bitmap (and that bitmap's pixel generation). */
        private String source() {
            if (inner instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap bm = ((android.graphics.drawable.BitmapDrawable) inner).getBitmap();
                if (bm != null) {
                    return Integer.toHexString(System.identityHashCode(bm)) + "."
                            + bm.getGenerationId();
                }
            }
            return "d" + Integer.toHexString(System.identityHashCode(inner));
        }

        private void startPeel(Rect b, final String key) {
            final Bitmap src;
            try {
                src = GlassIconDrawable.snapshot(inner, b);
            } catch (Throwable t) {
                XLog.e("peel snapshot", t);
                return;
            }
            if (src == null) return;
            final int w = b.width();
            final int h = b.height();
            PEEL_WORKER.execute(() -> {
                Peel result = null;
                try {
                    Bitmap full = PackGlass.peel(src);
                    if (full != null) result = crop(full);
                } catch (Throwable t) {
                    XLog.e("peel", t);
                } finally {
                    src.recycle();
                }
                final Peel out = result;
                MAIN.post(() -> {
                    if (key != null) PEELS.put(key, out == null ? NONE : out);
                    // Resized meanwhile: this result is stale here (kept for its size).
                    if (w != peelW || h != peelH) return;
                    peeled = out;
                    invalidateSelf();
                });
            });
        }

        /** Forgets the peeled glyph: the icon changed, or the setting did. */
        void dropPeel() {
            peeled = null;
            peelTried = false;
            peelW = 0;
            peelH = 0;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            inner.setBounds(bounds);
        }

        @Override
        public int getIntrinsicWidth() {
            return inner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return inner.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public int getAlpha() {
            return inner.getAlpha();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            inner.setColorFilter(colorFilter);
        }

        /**
         * The icon's own filter: One UI dims an icon while it is pressed and, on release,
         * clears the filter only if it sees one. Without this the wrapper said "none" and the
         * icon could stay dimmed after a press or a swipe that started on it.
         */
        @Override
        public ColorFilter getColorFilter() {
            return inner.getColorFilter();
        }

        @Override
        public void setTintList(android.content.res.ColorStateList tint) {
            inner.setTintList(tint);
        }

        @Override
        public void setTintBlendMode(BlendMode blendMode) {
            inner.setTintBlendMode(blendMode);
        }

        @Override
        public boolean isStateful() {
            return inner.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return inner.setState(state);
        }

        @Override
        public void jumpToCurrentState() {
            inner.jumpToCurrentState();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            inner.setVisible(visible, restart);
            return super.setVisible(visible, restart);
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            peelTried = false;
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
}
