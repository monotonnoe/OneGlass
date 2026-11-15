package com.monotonnoe.oneglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.monotonnoe.oneglass.Config;

/**
 * Wraps a home screen app icon. With the iOS 27 "Clear" / "Tinted" style it draws only the
 * symbol (Figma "Base Symbol": Clear = white 92%, Tinted = tint 100% -> 90% top to bottom); the
 * glass tile under it is the icon view's glass background. With "Default", or on the One UI apps
 * screen, it draws the original icon unchanged.
 *
 * The symbol comes from the adaptive icon's monochrome layer when available, otherwise it is
 * separated from the finished icon: the tile colour is estimated from a ring inside the icon and
 * every pixel that differs enough from it becomes part of the glyph.
 */
public final class GlyphIconDrawable extends Drawable implements Drawable.Callback {

    /** Decides whether a host view is on the (untouched) apps screen. */
    public interface Place {
        boolean appsScreen(View v);
    }

    public static Place place;

    private final Drawable original;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap glyph;
    private int glyphKey;
    private final Rect glyphFor = new Rect();
    private int alpha = 255;

    public GlyphIconDrawable(Drawable original) {
        this.original = original;
        original.setCallback(this);
    }

    public Drawable original() {
        return original;
    }

    private View host() {
        Object cb = getCallback();
        int guard = 0;
        while (cb instanceof Drawable && guard++ < 8) {
            cb = ((Drawable) cb).getCallback();
        }
        return cb instanceof View ? (View) cb : null;
    }

    @Override
    public void draw(Canvas canvas) {
        Config cfg = Config.current;
        View host = host();
        if (cfg.style == Config.STYLE_DEFAULT || host == null || (place != null && place.appsScreen(host))) {
            original.draw(canvas);
            return;
        }
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        int key = cfg.style * 31 + cfg.tintColor;
        if (glyph == null || key != glyphKey || !glyphFor.equals(b)) {
            glyph = render(b.width(), b.height(), cfg.style, cfg.tintColor);
            glyphKey = key;
            glyphFor.set(b);
        }
        if (glyph != null) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(glyph, b.left, b.top, paint);
        } else {
            original.draw(canvas);
        }
    }

    private Bitmap render(int w, int h, int style, int tint) {
        try {
            int[] px = new int[w * h];
            boolean mono = false;
            if (original instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) original).getMonochrome() != null) {
                Drawable m = ((AdaptiveIconDrawable) original).getMonochrome();
                Bitmap layer = snapshot(m, w, h, w / 4, h / 4);
                layer.getPixels(px, 0, w, 0, 0, w, h);
                for (int i = 0; i < px.length; i++) {
                    px[i] = (px[i] >>> 24) << 24;
                }
                mono = true;
            } else {
                Bitmap full = snapshot(original, w, h, 0, 0);
                full.getPixels(px, 0, w, 0, 0, w, h);
                if (!extractGlyph(px, w, h)) {
                    return fadedCopy(px, w, h);
                }
            }
            colour(px, w, h, style, tint);
            Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            if (mono) {
                out.setHasAlpha(true);
            }
            return out;
        } catch (Throwable t) {
            Log.e("glyph icon", t);
            return null;
        }
    }

    /** Draws a drawable into a software bitmap (via a Picture, so hardware bitmaps work too). */
    private static Bitmap snapshot(Drawable d, int w, int h, int outsetX, int outsetY) {
        Picture pic = new Picture();
        Canvas c = pic.beginRecording(w, h);
        Rect old = new Rect(d.getBounds());
        d.setBounds(-outsetX, -outsetY, w + outsetX, h + outsetY);
        d.draw(c);
        d.setBounds(old);
        pic.endRecording();
        Bitmap b = Bitmap.createBitmap(pic);
        if (b.getConfig() == Bitmap.Config.HARDWARE) {
            b = b.copy(Bitmap.Config.ARGB_8888, false);
        }
        return b;
    }

    /**
     * Turns the icon pixels into a glyph alpha mask (stored in the alpha byte). Returns false when
     * no clear symbol stands out from the tile (photos, gradients), so the caller falls back.
     */
    private static boolean extractGlyph(int[] px, int w, int h) {
        // tile colour: median of a ring 14% inside the edges
        int n = 0;
        int[] rs = new int[64], gs = new int[64], bs = new int[64];
        int ix = Math.max(1, Math.round(w * 0.14f));
        int iy = Math.max(1, Math.round(h * 0.14f));
        for (int k = 0; k < 16; k++) {
            float t = k / 16f;
            int[][] pts = {
                    {ix + Math.round(t * (w - 2 * ix)), iy},
                    {w - 1 - ix, iy + Math.round(t * (h - 2 * iy))},
                    {w - 1 - ix - Math.round(t * (w - 2 * ix)), h - 1 - iy},
                    {ix, h - 1 - iy - Math.round(t * (h - 2 * iy))}};
            for (int[] p : pts) {
                int c = px[p[1] * w + p[0]];
                if ((c >>> 24) > 200 && n < 64) {
                    rs[n] = (c >> 16) & 0xFF;
                    gs[n] = (c >> 8) & 0xFF;
                    bs[n] = c & 0xFF;
                    n++;
                }
            }
        }
        if (n < 8) {
            return false;
        }
        int br = median(rs, n), bg = median(gs, n), bb = median(bs, n);
        int covered = 0, opaque = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = c >>> 24;
            if (a == 0) {
                px[i] = 0;
                continue;
            }
            opaque++;
            int d = Math.max(Math.abs(((c >> 16) & 0xFF) - br),
                    Math.max(Math.abs(((c >> 8) & 0xFF) - bg), Math.abs((c & 0xFF) - bb)));
            // smoothstep between 30 and 90 of 255
            float x = Math.max(0f, Math.min(1f, (d - 30f) / 60f));
            float g = x * x * (3f - 2f * x);
            int ga = Math.round(g * a);
            if (ga > 128) {
                covered++;
            }
            px[i] = ga << 24;
        }
        if (opaque == 0) {
            return false;
        }
        float frac = covered / (float) opaque;
        return frac > 0.02f && frac < 0.7f;
    }

    private static int median(int[] v, int n) {
        int[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }

    /** Fills the glyph mask with the style colour. */
    private static void colour(int[] px, int w, int h, int style, int tint) {
        for (int y = 0; y < h; y++) {
            int rgb;
            float k;
            if (style == Config.STYLE_TINTED) {
                rgb = tint & 0xFFFFFF;
                k = 1f - 0.1f * y / Math.max(1, h - 1);
            } else {
                rgb = 0xFFFFFF;
                k = 0.92f;
            }
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int a = Math.round((px[i] >>> 24) * k);
                px[i] = (a << 24) | rgb;
            }
        }
    }

    /** Fallback: the icon in grey at 60%. */
    private static Bitmap fadedCopy(int[] px, int w, int h) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = Math.round((c >>> 24) * 0.6f);
            int l = Math.round(0.3f * ((c >> 16) & 0xFF) + 0.59f * ((c >> 8) & 0xFF) + 0.11f * (c & 0xFF));
            px[i] = (a << 24) | (l << 16) | (l << 8) | l;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        original.setBounds(bounds);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return original.setState(state);
    }

    @Override
    public boolean isStateful() {
        return original.isStateful();
    }

    @Override
    protected boolean onLevelChange(int level) {
        return original.setLevel(level);
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
        this.alpha = alpha;
        original.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        original.setColorFilter(colorFilter);
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // Drawable.Callback: forward the original's invalidations (animated / live icons)
    @Override
    public void invalidateDrawable(Drawable who) {
        glyph = null;
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
