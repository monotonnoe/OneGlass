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

    /**
     * Dock: drawn on the hotseat's parent, around the hotseat cell layout plus padding, so the
     * platter can be bigger than the icon row (iOS: 368x103pt around 64pt icons).
     */
    static final class Hotseat implements GlassDrawable.Shape {
        private final WeakReference<View> child;
        private final RectF tmp = new RectF();
        private final RectF iconRect = new RectF();

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
            tmp.set(0, 0, c.getWidth(), c.getHeight());
            c.getMatrix().mapRect(tmp);
            tmp.offset(c.getLeft() - host.getScrollX(), c.getTop() - host.getScrollY());
            Config cfg = Config.current;
            float pt = Units.pt(host);
            if (cfg.iosGrid) {
                // Figma: Dock 357x99pt, centred, icon row centred inside it.
                float cy = tmp.centerY();
                if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0) {
                    View icon = ((ViewGroup) c).getChildAt(0);
                    if (IconGeometry.find(icon, iconRect)) {
                        float sy = c.getHeight() > 0 ? tmp.height() / c.getHeight() : 1f;
                        cy = tmp.top + (icon.getTop() + iconRect.centerY()) * sy;
                    }
                }
                float cx = bounds.exactCenterX();
                float hw = 357f * pt / 2f + cfg.dockPadH * pt;
                float hh = 99f * pt / 2f + cfg.dockPadV * pt;
                tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
            } else {
                tmp.inset(-cfg.dockPadH * pt - 12f * pt, -cfg.dockPadV * pt - 12f * pt);
            }
            if (!tmp.intersect(bounds.left, bounds.top, bounds.right, bounds.bottom)) {
                return false;
            }
            out.set(tmp);
            return true;
        }

        @Override
        public float alpha(View host) {
            View c = child.get();
            return c == null ? 0f : c.getAlpha();
        }
    }
}
