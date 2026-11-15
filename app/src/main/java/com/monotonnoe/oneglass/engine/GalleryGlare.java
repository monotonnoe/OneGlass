package com.monotonnoe.oneglass.engine;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A thin light line round photo widgets, over the photo, a little brighter along the top. Drawn
 * by the cell layout right after the widget, in the widget's own shape.
 */
final class GalleryGlare {
    private static final String[] GALLERIES = {
            "com.samsung.android.widget.pictureframe", "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
    };
    private static final int MAX_DEPTH = 3;
    private static final Map<View, Boolean> IS_GALLERY = new WeakHashMap<>();
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF RECT = new RectF();
    /** Scratch for the host's offset in its cell: draws record on one thread. */
    private static final float[] OFFSET = new float[2];
    private static final Path PATH = new Path();
    private static int shaderW = -1;
    private static int shaderH = -1;
    private static float inset;

    private GalleryGlare() {
    }

    /** After the cell layout drew {@code child}: the glare, if it holds a photo widget. */
    static void draw(Canvas canvas, ViewGroup parent, View child) {
        if ((Config.galleryGlare <= 0f && Config.galleryLook == GalleryLook.FULL
                && !Config.gallerySpatial) || !canvas.isHardwareAccelerated()
                || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
            return;
        }
        float[] offset = OFFSET;
        offset[0] = 0f;
        offset[1] = 0f;
        View host = find(child, 0, offset);
        if (host == null || !isGallery(host)) return;
        GalleryLook.drawn(host);
        if (Config.galleryGlare <= 0f) return;
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        if (w != shaderW || h != shaderH) {
            shaderW = w;
            shaderH = h;
            // A thin light line all round, right on the edge, brighter along the top.
            float d = host.getResources().getDisplayMetrics().density;
            RIM.setStyle(Paint.Style.STROKE);
            RIM.setStrokeWidth(Math.max(1.5f, d * 1.2f));
            // Half the stroke in: the line lies wholly inside the outline, touching it.
            inset = RIM.getStrokeWidth() / 2f;
            RIM.setShader(new LinearGradient(0f, 0f, 0f, h,
                    new int[]{0xFFFFFFFF, 0xB3FFFFFF, 0xB3FFFFFF},
                    new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        }
        int a = Math.round(255f * Config.galleryGlare
                * Math.max(0f, Math.min(1f, child.getAlpha() * host.getAlpha())));
        RIM.setAlpha(a);
        // The widget's outline, drawn smaller by half the stroke: the line hugs the edge.
        float i = Math.min(inset, Math.min(w, h) / 4f);
        float iw = w - 2f * i;
        float ih = h - 2f * i;
        PATH.reset();
        if (Config.iosWidgetCorners) {
            float r = WidgetCorners.radius(w, h, WidgetCorners.span(child.getLayoutParams()));
            PATH.set(WidgetCorners.cachedPath(iw, ih, Math.max(0f, r - i)));
            PATH.offset(i, i);
        } else {
            float r = Math.max(0f, Math.min(w, h) * 0.12f - i);
            RECT.set(i, i, w - i, h - i);
            PATH.addRoundRect(RECT, r, r, Path.Direction.CW);
        }
        canvas.save();
        try {
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            canvas.translate(offset[0] - host.getTranslationX(),
                    offset[1] - host.getTranslationY());
            canvas.concat(host.getMatrix());
            canvas.drawPath(PATH, RIM);
        } finally {
            canvas.restore();
        }
    }

    private static boolean isGallery(View host) {
        Boolean b = IS_GALLERY.get(host);
        if (b != null) return b;
        AppWidgetProviderInfo info = ((AppWidgetHostView) host).getAppWidgetInfo();
        if (info == null || info.provider == null) return false;
        String pkg = info.provider.getPackageName();
        b = false;
        for (String g : GALLERIES) if (g.equals(pkg)) b = true;
        IS_GALLERY.put(host, b);
        if (b) XLog.i("gallery glare on " + info.provider.flattenToShortString());
        return b;
    }

    /** The widget host in {@code v}, and its offset from the cell's origin. */
    private static View find(View v, int level, float[] offset) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof AppWidgetHostView) return v;
        if (level >= MAX_DEPTH || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            float dx = c.getLeft() + c.getTranslationX() - g.getScrollX();
            float dy = c.getTop() + c.getTranslationY() - g.getScrollY();
            offset[0] += dx;
            offset[1] += dy;
            View found = find(c, level + 1, offset);
            if (found != null) return found;
            offset[0] -= dx;
            offset[1] -= dy;
        }
        return null;
    }
}
