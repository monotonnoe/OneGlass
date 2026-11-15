package com.monotonnoe.oneglass.hook;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.GlassDrawable;
import com.monotonnoe.oneglass.glass.IconGeometry;

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
            float dp = host.getResources().getDisplayMetrics().density;
            float w = bounds.width();
            float h = bounds.height();
            if (w <= 0 || h <= 0) {
                return false;
            }
            float ph = Math.min(h, 30f * dp);
            int cfgW = Config.current.pillWidth;
            float pw = cfgW > 0 ? cfgW * dp : Math.max(ph * 2.6f, 77f * dp);
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
            float dp = host.getResources().getDisplayMetrics().density;
            Config cfg = Config.current;
            tmp.inset(-cfg.dockPadH * dp, -cfg.dockPadV * dp);
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
