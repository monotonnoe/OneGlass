package com.monotonnoe.oneglass.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import java.util.Arrays;

/**
 * A "clear glass" app icon: a faint frosted fill and a specular rim inside One UI's own icon
 * shape, with the app's adaptive-icon foreground on top. The background layer of the adaptive
 * icon is dropped, so the live blur drawn behind the icon shows through.
 *
 * <p>The launcher immediately rasterises this into its icon cache, so the bitmap is rendered once
 * per requested size.
 */
final class GlassIconDrawable extends Drawable {
    /** Glyphs stay out of this outer fraction of the icon, like iOS clear icons. */
    private static final float SAFE_MARGIN = 0.12f;

    private final Drawable shape;
    private final AdaptiveIconDrawable adaptive;
    private final Drawable legacy;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap rendered;
    private int alpha = 255;
    /** The app's name, for the log. */
    String tag = "";

    /**
     * @param shape    the icon One UI would have shown; for adaptive icons its alpha is the mask
     * @param adaptive the app's unthemed adaptive icon, source of the foreground layer
     */
    GlassIconDrawable(Drawable shape, AdaptiveIconDrawable adaptive) {
        this.shape = shape;
        this.adaptive = adaptive;
        this.legacy = null;
    }

    /**
     * A legacy (non-adaptive, usually square) icon: drawn semi-transparent and cut to the One UI
     * squircle, since One UI's own version of it may not have that shape.
     */
    GlassIconDrawable(Drawable shape, Drawable legacy) {
        this.shape = shape;
        this.adaptive = null;
        this.legacy = legacy;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;
        Bitmap bmp = rendered;
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
            bmp = render(w, h);
            rendered = bmp;
        }
        if (bmp == null) return;
        bitmapPaint.setAlpha(alpha);
        canvas.drawBitmap(bmp, b.left, b.top, bitmapPaint);
    }

    private Bitmap render(int w, int h) {
        try {
            Drawable flat = legacy;
            boolean flatIsLayer = false;
            if (flat == null) {
                Bitmap themedShape = shapeMask(w, h);
                if (isSquare(themedShape)) {
                    // A legacy bitmap the system wrapped into an adaptive icon: One UI left it
                    // unshaped, so treat it like a legacy icon. Its foreground layer is the
                    // app's own picture, without One UI's shaded tray around it.
                    Drawable fg = adaptive.getForeground();
                    flat = fg != null ? fg : shape;
                    flatIsLayer = fg != null;
                }
                themedShape.recycle();
            }
            // Every icon is cut hard to the same squircle the blur and background use; the
            // shape One UI returns can be wider for some apps.
            Bitmap mask = squircleMask(w, h);
            Rect box = opaqueBounds(mask);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);

            drawFill(c, mask, h);
            if (Config.packPeel && drawPeeledThemed(c, mask, box)) {
                // The pack's glyph alone, on this glass.
            } else if (flat != null) {
                drawLegacy(c, mask, box, flat, flatIsLayer);
            } else {
                drawForeground(c, mask, box);
            }
            drawRim(c, mask, box);

            mask.recycle();
            return out;
        } catch (Throwable t) {
            XLog.e("glass render failed", t);
            return null;
        }
    }

    /** ALPHA_8 copy of One UI's icon shape at the requested size. */
    private Bitmap shapeMask(int w, int h) {
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        Rect old = new Rect(shape.getBounds());
        shape.setBounds(0, 0, w, h);
        shape.draw(c);
        shape.setBounds(old);
        Bitmap alphaOnly = full.extractAlpha();
        full.recycle();
        return alphaOnly;
    }

    /** One UI's icon outline: a superellipse with n = 2.7, inset ~1.2% of the size. */
    private static Bitmap squircleMask(int w, int h) {
        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(mask);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawPath(superellipse(w, h, 2.7, Math.min(w, h) * 0.012f), p);
        return mask;
    }

    static Path superellipse(float w, float h, double n, float inset) {
        final int samples = 128;
        double power = 2.0 / n;
        double cx = w / 2.0;
        double cy = h / 2.0;
        double rx = Math.max(0, cx - inset);
        double ry = Math.max(0, cy - inset);
        Path path = new Path();
        for (int i = 0; i <= samples; i++) {
            double a = Math.PI * 2.0 * i / samples;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            float x = (float) (cx + rx * Math.copySign(Math.pow(Math.abs(cos), power), cos));
            float y = (float) (cy + ry * Math.copySign(Math.pow(Math.abs(sin), power), sin));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    /** True when the shape fills the corners, where One UI's squircle is transparent. */
    private static boolean isSquare(Bitmap mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int dx = Math.max(1, Math.round(w * 0.04f));
        int dy = Math.max(1, Math.round(h * 0.04f));
        int filled = 0;
        int[][] corners = {{dx, dy}, {w - 1 - dx, dy}, {dx, h - 1 - dy}, {w - 1 - dx, h - 1 - dy}};
        for (int[] p : corners) {
            if ((mask.getPixel(p[0], p[1]) >>> 24) > 0x80) filled++;
        }
        return filled >= 3;
    }

    /**
     * The icon as One UI themed it, when it is a glass icon pack's: its glass taken away, its
     * glyph on this glass. False when the themed icon is not of that kind.
     */
    private boolean drawPeeledThemed(Canvas c, Bitmap mask, Rect box) {
        Bitmap src = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);
        Rect old = new Rect(shape.getBounds());
        shape.setBounds(box);
        shape.draw(sc);
        shape.setBounds(old);
        Bitmap peeled = PackGlass.peel(src);
        src.recycle();
        if (peeled == null) return false;
        int save = c.saveLayer(0, 0, c.getWidth(), c.getHeight(), null);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(Math.round(255 * (Config.mono ? Config.monoAlpha : 1f)));
        Matrix fit = fitInside(contentBounds(peeled, 0f, 0f), box);
        c.drawBitmap(peeled, fit != null ? fit : new Matrix(), p);
        peeled.recycle();
        clipToShape(c, mask);
        c.restoreToCount(save);
        return true;
    }

    /**
     * A flat icon (legacy bitmap, or a square one One UI left unshaped), clipped to the squircle.
     * In colour mode it is shown semi-transparent; in white mode its glyph is extracted.
     */
    private static void drawLegacy(Canvas c, Bitmap mask, Rect box, Drawable flat,
                                   boolean adaptiveLayer) {
        Drawable icon = flat.mutate();
        int save = c.saveLayer(0, 0, c.getWidth(), c.getHeight(), null);
        Bitmap src = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);
        Rect old = new Rect(icon.getBounds());
        if (adaptiveLayer) {
            // 108dp layer for the 72dp viewport.
            int hw = Math.round(box.width() * 0.75f);
            int hh = Math.round(box.height() * 0.75f);
            icon.setBounds(box.centerX() - hw, box.centerY() - hh, box.centerX() + hw,
                    box.centerY() + hh);
        } else {
            icon.setBounds(box);
        }
        icon.draw(sc);
        icon.setBounds(old);

        Bitmap peeled = Config.packPeel ? PackGlass.peel(src) : null;
        if (peeled != null) {
            // An icon pack's own glass is gone: its glyph alone, on this glass.
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(Math.round(255 * (Config.mono ? Config.monoAlpha : 1f)));
            Matrix fit = fitInside(contentBounds(peeled, 0f, 0f), box);
            c.drawBitmap(peeled, fit != null ? fit : new Matrix(), p);
            peeled.recycle();
        } else if (Config.mono) {
            Bitmap glyph = extractGlyph(src, mask);
            if (glyph != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                p.setAlpha(Math.round(255 * Config.monoAlpha));
                Matrix fit = fitInside(contentBounds(glyph, 0f, 0f), box);
                c.drawBitmap(glyph, fit != null ? fit : new Matrix(), p);
                glyph.recycle();
            } else {
                // No dominant background (photo-like icon): lift the whole picture to white.
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setColorFilter(whiteGlyphFilter());
                p.setAlpha(Math.round(255 * Config.legacyAlpha));
                c.drawBitmap(src, 0, 0, p);
            }
        } else {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(Math.round(255 * Config.legacyAlpha));
            c.drawBitmap(src, 0, 0, p);
        }
        src.recycle();

        clipToShape(c, mask);
        c.restoreToCount(save);
    }

    /**
     * White glyph of a flat icon: the icon's own background colour becomes transparent and
     * everything that differs from it becomes white. The background is the median colour of the
     * outer ring of the shape; when that ring is mostly empty (a round or small picture), it is
     * the dominant colour of the picture itself. Returns null for photo-like icons.
     */
    private static Bitmap extractGlyph(Bitmap src, Bitmap mask) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] m = new int[w * h];
        mask.getPixels(m, 0, w, 0, 0, w, h);

        float cx = w / 2f;
        float cy = h / 2f;
        int ringTotal = 0;
        int[] ringR = new int[px.length];
        int[] ringG = new int[px.length];
        int[] ringB = new int[px.length];
        int ringSolid = 0;
        int solid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((m[i] >>> 24) < 128) continue;
                int a = px[i] >>> 24;
                if (a >= 200) solid++;
                double u = Math.abs((x + 0.5f - cx) / cx);
                double v = Math.abs((y + 0.5f - cy) / cy);
                double r = Math.pow(Math.pow(u, 2.7) + Math.pow(v, 2.7), 1 / 2.7);
                if (r < 0.80 || r > 0.95) continue;
                ringTotal++;
                if (a < 200) continue;
                ringR[ringSolid] = (px[i] >> 16) & 0xFF;
                ringG[ringSolid] = (px[i] >> 8) & 0xFF;
                ringB[ringSolid] = px[i] & 0xFF;
                ringSolid++;
            }
        }
        if (solid == 0) return null;

        int cr, cg, cb;
        if (ringTotal > 0 && ringSolid >= ringTotal * 0.5f) {
            cr = median(ringR, ringSolid);
            cg = median(ringG, ringSolid);
            cb = median(ringB, ringSolid);
        } else {
            int[] hist = new int[512];
            for (int i = 0; i < px.length; i++) {
                if ((px[i] >>> 24) < 200 || (m[i] >>> 24) < 128) continue;
                hist[bucket(px[i])]++;
            }
            int best = 0;
            for (int i = 1; i < hist.length; i++) if (hist[i] > hist[best]) best = i;
            long sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int i = 0; i < px.length; i++) {
                if ((px[i] >>> 24) < 200 || (m[i] >>> 24) < 128) continue;
                if (bucket(px[i]) != best) continue;
                sr += (px[i] >> 16) & 0xFF;
                sg += (px[i] >> 8) & 0xFF;
                sb += px[i] & 0xFF;
                n++;
            }
            if (n == 0) return null;
            cr = (int) (sr / n);
            cg = (int) (sg / n);
            cb = (int) (sb / n);
        }

        // The chosen colour must really be a background, not one of many photo tones.
        int close = 0;
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) < 200 || (m[i] >>> 24) < 128) continue;
            if (distance(px[i], cr, cg, cb) < 48f) close++;
        }
        if (close < solid * 0.25f) return null;

        final float lo = 40f;
        final float hi = 100f;
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            if (a == 0) {
                px[i] = 0;
                continue;
            }
            float t = Math.max(0f, Math.min(1f, (distance(px[i], cr, cg, cb) - lo) / (hi - lo)));
            t = t * t * (3f - 2f * t);
            px[i] = (Math.round(a * t) << 24) | 0x00FFFFFF;
        }
        Bitmap glyph = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        glyph.setPixels(px, 0, w, 0, 0, w, h);
        return glyph;
    }

    /** {@code d} as the launcher draws it at {@code bounds}, in a bitmap that size; null if absurd. */
    static Bitmap snapshot(Drawable d, Rect bounds) {
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        c.translate(-bounds.left, -bounds.top);
        d.draw(c);
        return src;
    }

    /** 3 bits per channel colour bucket. */
    private static int bucket(int c) {
        return ((c >> 21) & 7) << 6 | ((c >> 13) & 7) << 3 | ((c >> 5) & 7);
    }

    private static float distance(int color, int r, int g, int b) {
        int dr = ((color >> 16) & 0xFF) - r;
        int dg = ((color >> 8) & 0xFF) - g;
        int db = (color & 0xFF) - b;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int median(int[] values, int n) {
        int[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy);
        return copy[n / 2];
    }

    /**
     * Hard clip of the current layer to the shape. An ALPHA_8 bitmap is drawn as a coverage mask,
     * which leaves pixels outside it untouched even with DST_IN, so the mask is first turned into
     * real ARGB alpha.
     */
    private static void clipToShape(Canvas c, Bitmap mask) {
        Bitmap argb = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(),
                Bitmap.Config.ARGB_8888);
        Paint solid = new Paint();
        solid.setColor(0xFF000000);
        new Canvas(argb).drawBitmap(mask, 0, 0, solid);
        Paint keep = new Paint();
        keep.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(argb, 0, 0, keep);
        argb.recycle();
    }

    /** Bounds of the visible pixels of {@code bmp}, shifted by (-dx, -dy). Empty if none. */
    private static RectF contentBounds(Bitmap bmp, float dx, float dy) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] row = new int[w];
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 48) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return new RectF();
        return new RectF(left - dx, top - dy, right + 1 - dx, bottom + 1 - dy);
    }

    /**
     * Keeps a glyph off the icon edge: art that pokes into the outer {@link #SAFE_MARGIN} of the
     * shape is scaled down and moved inside it. Full-bleed artwork (covering the whole shape) is
     * left alone and only clipped. Returns null when nothing needs to change.
     */
    private static Matrix fitInside(RectF art, Rect box) {
        float bw = art.width();
        float bh = art.height();
        if (bw <= 0f || bh <= 0f) return null;
        if (bw >= box.width() * 0.97f && bh >= box.height() * 0.97f) return null;
        float mx = box.width() * SAFE_MARGIN;
        float my = box.height() * SAFE_MARGIN;
        RectF safe = new RectF(box.left + mx, box.top + my, box.right - mx, box.bottom - my);
        float s = Math.min(1f, Math.min(safe.width() / bw, safe.height() / bh));
        float cx = art.centerX();
        float cy = art.centerY();
        float nw = bw * s;
        float nh = bh * s;
        float ncx = Math.max(safe.left + nw / 2f, Math.min(safe.right - nw / 2f, cx));
        float ncy = Math.max(safe.top + nh / 2f, Math.min(safe.bottom - nh / 2f, cy));
        if (s >= 0.999f && Math.abs(ncx - cx) < 0.5f && Math.abs(ncy - cy) < 0.5f) return null;
        Matrix m = new Matrix();
        m.postTranslate(-cx, -cy);
        m.postScale(s, s);
        m.postTranslate(ncx, ncy);
        return m;
    }

    private static Rect round(RectF r) {
        return new Rect(Math.round(r.left), Math.round(r.top), Math.round(r.right),
                Math.round(r.bottom));
    }

    private static Rect opaqueBounds(Bitmap mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int[] row = new int[w];
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            mask.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 0x80) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return new Rect(0, 0, w, h);
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static void drawFill(Canvas c, Bitmap mask, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, Config.tintTop, Config.tintBottom,
                Shader.TileMode.CLAMP));
        c.drawBitmap(mask, 0, 0, p);
    }

    private void drawForeground(Canvas c, Bitmap mask, Rect box) {
        Drawable layer = null;
        ColorFilter filter = null;
        if (Config.mono) {
            Drawable mono = adaptive.getMonochrome();
            if (mono != null) {
                layer = mono;
                filter = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            } else {
                layer = adaptive.getForeground();
                filter = whiteGlyphFilter();
            }
        } else {
            layer = adaptive.getForeground();
        }
        if (layer == null) return;
        layer = layer.mutate();

        int save = c.saveLayer(0, 0, c.getWidth(), c.getHeight(), null);
        // Adaptive layers are 108dp for a 72dp viewport: 1.5x the visible icon.
        float half = box.width() * 0.75f * Config.fgScale;
        float halfV = box.height() * 0.75f * Config.fgScale;
        float cx = box.exactCenterX();
        float cy = box.exactCenterY();
        Rect old = new Rect(layer.getBounds());
        RectF std = new RectF(cx - half, cy - halfV, cx + half, cy + halfV);
        if (filter != null) layer.setColorFilter(filter);
        if (Config.mono) layer.setAlpha(Math.round(255 * Config.monoAlpha));

        // Measure the whole layer (it is larger than the canvas) to see where the art really is.
        int w = c.getWidth();
        int h = c.getHeight();
        Bitmap probe = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ALPHA_8);
        Canvas pc = new Canvas(probe);
        pc.translate(w / 2f, h / 2f);
        layer.setBounds(round(std));
        layer.draw(pc);
        RectF art = contentBounds(probe, w / 2f, h / 2f);
        probe.recycle();

        Matrix fit = fitInside(art, box);
        RectF bounds = new RectF(std);
        if (fit != null) fit.mapRect(bounds);
        layer.setBounds(round(bounds));
        layer.draw(c);
        if (filter != null) layer.setColorFilter(null);
        layer.setBounds(old);

        clipToShape(c, mask);
        c.restoreToCount(save);
    }

    /** Desaturates and lifts a coloured foreground towards white, keeping its shading. */
    private static ColorFilter whiteGlyphFilter() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        ColorMatrix lift = new ColorMatrix(new float[]{
                0.25f, 0, 0, 0, 192,
                0, 0.25f, 0, 0, 192,
                0, 0, 0.25f, 0, 192,
                0, 0, 0, Config.monoAlpha, 0,
        });
        m.postConcat(lift);
        return new ColorMatrixColorFilter(m);
    }

    /** Thin ring along the shape edge, bright at the top-left and bottom-right like lit glass. */
    private static void drawRim(Canvas c, Bitmap mask, Rect box) {
        float stroke = Math.max(1f, box.width() * Config.rimWidth);
        if (Config.rimAlpha <= 0f) return;
        int save = c.saveLayer(0, 0, c.getWidth(), c.getHeight(), null);

        int peak = Math.round(255 * Math.min(1f, Config.rimAlpha));
        int mid = Math.round(peak * 0.18f);
        int low = Math.round(peak * 0.55f);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ring.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                new int[]{white(peak), white(mid), white(mid), white(low)},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawBitmap(mask, 0, 0, ring);

        // Cut the inside away with the same shape shrunk by the stroke width.
        float sx = (box.width() - 2f * stroke) / box.width();
        float sy = (box.height() - 2f * stroke) / box.height();
        Matrix inner = new Matrix();
        inner.setScale(sx, sy, box.exactCenterX(), box.exactCenterY());
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        c.drawBitmap(mask, inner, cut);
        c.restoreToCount(save);
    }

    private static int white(int a) {
        return (a << 24) | 0x00FFFFFF;
    }

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha != alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bitmapPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return shape.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return shape.getIntrinsicHeight();
    }
}
