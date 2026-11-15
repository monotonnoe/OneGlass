package com.monotonnoe.oneglass.glass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.monotonnoe.oneglass.Config;

import java.lang.ref.WeakReference;

/**
 * One Liquid Glass platter. It is either drawn by a host view's dispatchDraw (dock, widgets,
 * search pill: the launcher can not overwrite it) or used as a drawable (folder panel).
 * Where exactly the platter sits inside the host is decided by a {@link Shape}.
 */
public final class GlassDrawable extends Drawable {

    /** Places the platter inside the host. */
    public interface Shape {
        /**
         * @param host   the view whose canvas the glass is drawn in
         * @param bounds the host's drawing area (host coordinates)
         * @param out    platter rectangle in host coordinates
         * @return false to draw nothing this time
         */
        boolean compute(View host, Rect bounds, RectF out);

        /** Extra opacity 0..1. */
        float alpha(View host);
    }

    public final int role;
    public final Shape shape;
    private final RectF rect = new RectF();
    private final Rect hostBounds = new Rect();
    private WeakReference<View> fixedHost;
    /** dispatchDraw / onDraw canvases are shifted by the view's scroll; backgrounds are not. */
    private boolean scrolledCanvas = true;
    private int alpha = 255;

    public GlassDrawable(int role, Shape shape) {
        this.role = role;
        this.shape = shape;
    }

    /** Draw in this view's own canvas (from its dispatchDraw / onDraw) instead of as a drawable. */
    public GlassDrawable hostedBy(View host) {
        fixedHost = new WeakReference<>(host);
        scrolledCanvas = true;
        return this;
    }

    /** Drawn from {@code host}'s background drawable (canvas in plain view coordinates). */
    public GlassDrawable hostedByBackground(View host) {
        fixedHost = new WeakReference<>(host);
        scrolledCanvas = false;
        return this;
    }

    public boolean enabled(Config c) {
        switch (role) {
            case GlassSpec.ROLE_DOCK:
                return c.dock;
            case GlassSpec.ROLE_PILL:
                return c.pill;
            case GlassSpec.ROLE_WIDGET:
                return c.widgets;
            case GlassSpec.ROLE_FOLDER:
                return c.folders;
            case GlassSpec.ROLE_FOLDER_PANEL:
                return c.folderPanel;
            case GlassSpec.ROLE_ICON_TILE:
                return c.iconRim && c.style != Config.STYLE_DEFAULT;
            default:
                return false;
        }
    }

    /** The view at the end of the callback chain (we may sit inside a LayerDrawable). */
    public View host() {
        if (fixedHost != null) {
            return fixedHost.get();
        }
        Object cb = getCallback();
        int guard = 0;
        while (cb instanceof Drawable && guard++ < 8) {
            cb = ((Drawable) cb).getCallback();
        }
        return cb instanceof View ? (View) cb : null;
    }

    private Rect area(View host) {
        if (fixedHost != null) {
            hostBounds.set(0, 0, host.getWidth(), host.getHeight());
            return hostBounds;
        }
        return getBounds();
    }

    /** Writes the parameters that decide what this glass would paint. */
    int key(View host, float[] out, int at) {
        boolean ok = shape.compute(host, area(host), rect);
        out[at] = ok ? rect.left : -1f;
        out[at + 1] = ok ? rect.top : -1f;
        out[at + 2] = ok ? rect.right : -1f;
        out[at + 3] = ok ? rect.bottom : -1f;
        out[at + 4] = ok ? shape.alpha(host) : -1f;
        return at + 5;
    }

    @Override
    public void draw(Canvas canvas) {
        View host = host();
        if (host != null) {
            drawIn(canvas, host);
        }
    }

    /** Paints the glass into {@code canvas}, which is in {@code host}'s coordinates. */
    public void drawIn(Canvas canvas, View host) {
        Config cfg = Config.current;
        GlassTracker.drawn(host);
        if (!enabled(cfg) || !shape.compute(host, area(host), rect)) {
            return;
        }
        float a = shape.alpha(host) * (alpha / 255f);
        if (a <= 0.003f) {
            return;
        }
        GlassSpec spec = GlassSpec.resolve(role, cfg, Units.pt(host),
                GlassSpec.isDark(cfg, host.getResources()), rect);
        Shader content = Backdrop.shaderFor(host);
        if (fixedHost != null && scrolledCanvas && (host.getScrollX() != 0 || host.getScrollY() != 0)) {
            // dispatchDraw canvases are scrolled with the content; our rect is in view coordinates
            canvas.save();
            canvas.translate(host.getScrollX(), host.getScrollY());
            GlassShader.draw(canvas, rect, spec, content, a);
            canvas.restore();
        } else {
            GlassShader.draw(canvas, rect, spec, content, a);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // not supported
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
