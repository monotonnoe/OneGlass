package com.monotonnoe.oneglass.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The outline of an icon as drawn by the icon pack, measured from its pixels: the edges of its
 * visible area and the radius of its corners, as fractions of the icon size. The live blur
 * behind the icon is shaped to it, so any pack's shape and padding fit without settings.
 *
 * <p>The edges come from the middle row and column and the corner from the top-left one, so a
 * badge in another corner (Knox, work profile) does not count.
 */
final class IconOutline {
    /** Alpha from which a pixel belongs to the icon; see-through glass icons are still above it. */
    private static final int VISIBLE = 20;
    private static final Map<Bitmap, IconOutline> CACHE = new WeakHashMap<>();
    /** The last outline measured: folders take the shape of the pack's app icons. */
    static volatile IconOutline last;

    final float left;
    final float top;
    final float right;
    final float bottom;
    /** Corner radius as a fraction of the icon width. */
    final float corner;

    private IconOutline(float left, float top, float right, float bottom, float corner) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.corner = corner;
    }

    /** OneGlass: the iOS 27 icon squircle covering the whole icon (Figma: 15.5 / 60pt). */
    private static final IconOutline IOS = new IconOutline(0f, 0f, 1f, 1f, IosLook.CORNER);

    static IconOutline ios() {
        last = IOS; // folders take the app icons' shape
        return IOS;
    }

    /** The outline of {@code icon}, or null if it has no measurable one. Main thread only. */
    static IconOutline of(Drawable icon) {
        try {
            if (icon instanceof BitmapDrawable) {
                Bitmap bmp = ((BitmapDrawable) icon).getBitmap();
                if (bmp == null) return null;
                if (CACHE.containsKey(bmp)) return CACHE.get(bmp);
                IconOutline o = measure(bmp);
                CACHE.put(bmp, o);
                if (o != null) last = o;
                return o;
            }
            int w = icon.getIntrinsicWidth();
            int h = icon.getIntrinsicHeight();
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Rect old = new Rect(icon.getBounds());
            icon.setBounds(0, 0, w, h);
            icon.draw(new Canvas(bmp));
            icon.setBounds(old);
            IconOutline o = measure(bmp);
            bmp.recycle();
            return o;
        } catch (Throwable t) {
            XLog.e("icon outline", t);
            return null;
        }
    }

    private static IconOutline measure(Bitmap bmp) {
        Bitmap src = bmp;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) {
            src = bmp.copy(Bitmap.Config.ARGB_8888, false);
            if (src == null) return null;
        }
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] row = new int[w];
            int[] col = new int[h];
            src.getPixels(row, 0, w, 0, h / 2, w, 1);
            src.getPixels(col, 0, 1, w / 2, 0, 1, h);
            int left = first(row);
            int right = last(row);
            int top = first(col);
            int bottom = last(col);
            if (left < 0 || top < 0 || right - left < w / 3 || bottom - top < h / 3) return null;

            // Diagonal from the top-left corner of the box to the first visible pixel: for a
            // circular corner of radius r it is r * (1 - 1/sqrt 2) along each axis.
            int max = Math.min(right - left, bottom - top) / 2;
            int d = 0;
            while (d < max && (src.getPixel(left + d, top + d) >>> 24) < VISIBLE) d++;
            float radius = Math.min(max, d / (1f - (float) Math.sqrt(0.5)));
            return new IconOutline(left / (float) w, top / (float) h, (right + 1) / (float) w,
                    (bottom + 1) / (float) h, radius / w);
        } finally {
            if (src != bmp) src.recycle();
        }
    }

    private static int first(int[] px) {
        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) >= VISIBLE) return i;
        return -1;
    }

    private static int last(int[] px) {
        for (int i = px.length - 1; i >= 0; i--) if ((px[i] >>> 24) >= VISIBLE) return i;
        return -1;
    }
}
