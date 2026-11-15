package com.monotonnoe.oneglass.hook;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.GlassDrawable;
import com.monotonnoe.oneglass.glass.GlassSpec;
import com.monotonnoe.oneglass.glass.GlassTracker;
import com.monotonnoe.oneglass.glass.HomeScope;
import com.monotonnoe.oneglass.glass.Log;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Folders, following BlurIcons' approach:
 *  - folder icon: tile glass drawn before the icon draws itself,
 *  - open folder: starting from an icon inside the folder, the panel is the largest view with
 *    a background that covers the icon but is narrower than the screen; its background becomes
 *    Liquid Glass and stays glass when One UI sets a new one.
 */
final class FolderPanels {

    private static final Map<View, GlassDrawable> FOLDER_ICONS = new WeakHashMap<>();
    private static final Map<View, Boolean> PANELS = new WeakHashMap<>();
    private static final Map<ViewParent, Integer> SEARCHED = new WeakHashMap<>();
    private static final int[] LOC = new int[2];
    private static boolean logged;

    private FolderPanels() {
    }

    static void drawFolderIcon(View v, Canvas canvas) {
        if (!HomeScope.isHome(v)) {
            return;
        }
        GlassDrawable g = FOLDER_ICONS.get(v);
        if (g == null) {
            g = new GlassDrawable(GlassSpec.ROLE_FOLDER, Shapes.ICON).hostedBy(v);
            FOLDER_ICONS.put(v, g);
            GlassTracker.track(v, g);
        }
        g.drawIn(canvas, v);
    }

    /** Called when an app icon attaches; finds the panel of the open folder it is in. */
    static void onIcon(View icon) {
        if (!Config.current.folderPanel || !inOpenFolder(icon)) {
            return;
        }
        ViewParent start = icon.getParent();
        if (start == null) {
            return;
        }
        Integer tries = SEARCHED.get(start);
        int n = tries == null ? 0 : tries;
        if (n < 0 || n >= 5) {
            return;
        }
        SEARCHED.put(start, n + 1);
        // the folder opens with an animation: look again once it has a size
        icon.post(() -> search(icon, start));
    }

    private static boolean inOpenFolder(View v) {
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 12) {
            String n = p.getClass().getName();
            if (n.endsWith(".WorkspaceCellLayout") || n.endsWith(".HotseatCellLayout")) {
                return false;
            }
            if ((n.contains(".folder.") || n.contains("Folder")) && !n.contains("FolderIcon")) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    private static void search(View icon, ViewParent start) {
        if (icon.getWidth() <= 0) {
            return;
        }
        icon.getLocationOnScreen(LOC);
        float cx = LOC[0] + icon.getWidth() / 2f;
        float cy = LOC[1] + icon.getHeight() / 2f;
        int screenW = icon.getRootView().getWidth();
        View best = null;
        StringBuilder path = new StringBuilder();
        ViewParent p = start;
        for (int i = 0; i < 10 && p instanceof View; i++) {
            View v = (View) p;
            path.append(' ').append(v.getClass().getSimpleName()).append(v.getBackground() != null ? "(bg)" : "");
            best = larger(best, candidate(v, cx, cy, icon.getWidth(), screenW));
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int k = 0; k < g.getChildCount(); k++) {
                    best = larger(best, candidate(g.getChildAt(k), cx, cy, icon.getWidth(), screenW));
                }
            }
            p = p.getParent();
        }
        if (best == null) {
            if (!logged) {
                logged = true;
                Log.i("no folder panel found, from the icon up:" + path);
            }
            return;
        }
        SEARCHED.put(start, -1);
        install(best);
        Log.i("folder panel: " + best.getClass().getName() + " " + best.getWidth() + "x" + best.getHeight()
                + "; from the icon up:" + path);
    }

    private static View candidate(View v, float cx, float cy, int iconW, int screenW) {
        if (v.getVisibility() != View.VISIBLE || v instanceof TextView || v.getBackground() == null) {
            return null;
        }
        if (v.getWidth() >= screenW * 0.97f || v.getWidth() < iconW * 2) {
            return null;
        }
        v.getLocationOnScreen(LOC);
        if (cx < LOC[0] || cy < LOC[1] || cx > LOC[0] + v.getWidth() || cy > LOC[1] + v.getHeight()) {
            return null;
        }
        return v;
    }

    private static View larger(View a, View b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (long) b.getWidth() * b.getHeight() > (long) a.getWidth() * a.getHeight() ? b : a;
    }

    private static void install(View panel) {
        PANELS.put(panel, Boolean.TRUE);
        // goes through the setBackgroundDrawable hook below, which wraps it
        panel.setBackground(panel.getBackground());
    }

    /** Keeps the panel glass when One UI sets a new background on it. */
    static void hookBackgrounds() {
        XposedBridge.hookAllMethods(View.class, "setBackgroundDrawable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !PANELS.containsKey(param.thisObject)) {
                    return;
                }
                Object d = param.args[0];
                if (d instanceof PanelGlass) {
                    return;
                }
                param.args[0] = new PanelGlass((View) param.thisObject, (Drawable) d);
            }
        });
    }

    /** Glass in place of the folder panel's own background (keeps its padding and outline). */
    private static final class PanelGlass extends Drawable {
        private final Drawable original;
        private final GlassDrawable glass;
        private final View panel;

        PanelGlass(View panel, Drawable original) {
            this.panel = panel;
            this.original = original;
            this.glass = new GlassDrawable(GlassSpec.ROLE_FOLDER_PANEL, Shapes.FILL).hostedByBackground(panel);
            GlassTracker.track(panel, glass);
        }

        @Override
        public void draw(Canvas canvas) {
            if (!Config.current.folderPanel) {
                if (original != null) {
                    original.draw(canvas);
                }
                return;
            }
            glass.drawIn(canvas, panel);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (original != null) {
                original.setBounds(bounds);
            }
        }

        @Override
        public boolean getPadding(Rect padding) {
            return original != null ? original.getPadding(padding) : super.getPadding(padding);
        }

        @Override
        public void getOutline(Outline outline) {
            if (original != null) {
                original.getOutline(outline);
            } else {
                super.getOutline(outline);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            glass.setAlpha(alpha);
            if (original != null) {
                original.setAlpha(alpha);
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
