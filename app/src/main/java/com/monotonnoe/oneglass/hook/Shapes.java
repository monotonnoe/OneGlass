package com.monotonnoe.oneglass.hook;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.GlassDrawable;
import com.monotonnoe.oneglass.glass.IconGeometry;
import com.monotonnoe.oneglass.glass.Units;

import java.lang.ref.WeakReference;

/** Where each glass platter goes inside its host view. */
final class Shapes {

    /** Another module (SearchPill) already draws the search pill. */
    static volatile boolean otherPill;

    private Shapes() {
    }

    /** Fills the host (widgets). */
    static final GlassDrawable.Shape FILL = new GlassDrawable.Shape() {
        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            out.set(bounds);
            return !out.isEmpty();
        }

        @Override
        public float alpha(View host) {
            return 1f;
        }
    };

    /** iOS search pill behind the page indicator: 30pt tall, centred. */
    static final GlassDrawable.Shape PILL = new GlassDrawable.Shape() {
        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            float pt = Units.pt(host);
            float w = bounds.width();
            float h = bounds.height();
            if (w <= 0 || h <= 0) {
                return false;
            }
            // Figma: "Search" 78x30pt
            float ph = 30f * pt;
            int cfgW = Config.current.pillWidth;
            float pw = cfgW > 0 ? cfgW * pt : 78f * pt;
            pw = Math.min(pw, w);
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            out.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
            return true;
        }

        @Override
        public float alpha(View host) {
            return 1f;
        }
    };

    /** Opened folder: the panel itself, unless it is a full-screen scrim container. */
    static final GlassDrawable.Shape PANEL = new GlassDrawable.Shape() {
        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            View root = host.getRootView();
            if (bounds.width() >= root.getWidth() * 0.97f && bounds.height() >= root.getHeight() * 0.6f) {
                return false;
            }
            out.set(bounds);
            return !out.isEmpty() && Config.current.folderPanel;
        }

        @Override
        public float alpha(View host) {
            return 1f;
        }
    };

    /** Folder icon: the icon square. */
    static final GlassDrawable.Shape ICON = new GlassDrawable.Shape() {
        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            return IconGeometry.find(host, out);
        }

        @Override
        public float alpha(View host) {
            return 1f;
        }
    };

    /** Rectangle of a descendant view in the host's (view) coordinates. */
    static boolean rectOf(View target, View host, RectF out) {
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            return false;
        }
        out.set(0, 0, target.getWidth(), target.getHeight());
        View cur = target;
        int guard = 0;
        while (cur != host && guard++ < 12) {
            cur.getMatrix().mapRect(out);
            out.offset(cur.getLeft(), cur.getTop());
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                return false;
            }
            View pv = (View) p;
            out.offset(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        return cur == host;
    }

    /** A descendant view's rectangle (widgets), or a 78x30pt pill centred on it (page dots). */
    static final class Child implements GlassDrawable.Shape {
        private final WeakReference<View> target;
        private final boolean pill;

        Child(View target, boolean pill) {
            this.target = new WeakReference<>(target);
            this.pill = pill;
        }

        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            View t = target.get();
            if (t == null || t.getVisibility() != View.VISIBLE || !rectOf(t, host, out)) {
                return false;
            }
            if (pill) {
                if (otherPill || !Config.current.pill) {
                    return false;
                }
                float pt = Units.pt(host);
                int cfgW = Config.current.pillWidth;
                float pw = cfgW > 0 ? cfgW * pt : 78f * pt; // Figma "Search" 78x30pt
                float ph = 30f * pt;
                float cx = out.centerX();
                float cy = out.centerY();
                out.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
            } else {
                out.left += t.getPaddingLeft();
                out.top += t.getPaddingTop();
                out.right -= t.getPaddingRight();
                out.bottom -= t.getPaddingBottom();
            }
            return out.width() > 1 && out.height() > 1;
        }

        @Override
        public float alpha(View host) {
            View t = target.get();
            if (t == null) {
                return 0f;
            }
            float a = t.getAlpha();
            android.view.ViewParent p = t.getParent();
            int guard = 0;
            while (p instanceof View && p != host && guard++ < 12) {
                a *= ((View) p).getAlpha();
                p = p.getParent();
            }
            return a;
        }
    }

    /**
     * Dock, drawn by the hotseat's parent: Figma "Dock" 357x99pt with the icon row centred in it
     * (19pt around the 60pt icons); wider if One UI's icon row is wider.
     */
    static final class Hotseat implements GlassDrawable.Shape {
        private final WeakReference<View> child;
        private final RectF tmp = new RectF();
        private final RectF icons = new RectF();
        private final RectF one = new RectF();

        Hotseat(View child) {
            this.child = new WeakReference<>(child);
        }

        @Override
        public boolean compute(View host, Rect bounds, RectF out) {
            View c = child.get();
            if (c == null || c.getParent() != host || c.getVisibility() != View.VISIBLE
                    || c.getWidth() <= 0 || c.getHeight() <= 0) {
                return false;
            }
            float pt = Units.pt(host);
            Config cfg = Config.current;
            icons.setEmpty();
            collectIcons(c, host, 0);
            if (icons.isEmpty()) {
                if (!rectOf(c, host, tmp)) {
                    return false;
                }
                float s = Math.min(tmp.height(), 60f * pt);
                icons.set(tmp.centerX() - s / 2f, tmp.centerY() - s / 2f, tmp.centerX() + s / 2f, tmp.centerY() + s / 2f);
            }
            float hw = Math.max(357f * pt, icons.width() + 2f * 19f * pt) / 2f + cfg.dockPadH * pt;
            float hh = 99f * pt / 2f + cfg.dockPadV * pt;
            float cx = cfg.iosGrid ? bounds.exactCenterX() : icons.centerX();
            float cy = icons.centerY();
            tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
            if (!tmp.intersect(bounds.left, bounds.top, bounds.right, bounds.bottom)) {
                return false;
            }
            out.set(tmp);
            return true;
        }

        private void collectIcons(View v, View host, int depth) {
            if (!(v instanceof ViewGroup) || depth > 2) {
                return;
            }
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View k = g.getChildAt(i);
                if (k.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (IconGeometry.iconIn(k, host, one)) {
                    if (icons.isEmpty()) {
                        icons.set(one);
                    } else {
                        icons.union(one);
                    }
                } else {
                    collectIcons(k, host, depth + 1);
                }
            }
        }

        @Override
        public float alpha(View host) {
            View c = child.get();
            return c == null ? 0f : c.getAlpha();
        }
    }
}
