package com.monotonnoe.oneglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import com.monotonnoe.oneglass.Config;

/**
 * Figma "Default App Icon": a GLASS layer lies over the icon. The icon's own pixels are captured
 * and drawn back through the glass shader with only the refracting rim and the light visible.
 */
final class EdgeGlass {

    private static final long RECAPTURE_MS = 1000;

    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap capture;
    private BitmapShader shader;
    private final Rect capturedBounds = new Rect();
    private long capturedAt;
    private int capturedGen = -1;

    /** Call after the icon itself was drawn, in the same canvas, with the icon's bounds. */
    void draw(Canvas canvas, View host, Drawable icon, Rect bounds, float alpha) {
        if (!canvas.isHardwareAccelerated() || bounds.isEmpty() || !capture(icon, bounds)) {
            return;
        }
        Config cfg = Config.current;
        rect.set(bounds);
        matrix.setTranslate(rect.left, rect.top);
        matrix.preScale(rect.width() / capture.getWidth(), rect.height() / capture.getHeight());
        shader.setLocalMatrix(matrix);
        GlassSpec spec = GlassSpec.resolve(GlassSpec.ROLE_ICON, cfg, Units.pt(host),
                GlassSpec.isDark(cfg, host.getResources()), rect);
        GlassShader.draw(canvas, rect, spec, shader, alpha * cfg.iconRimAlpha / 100f);
    }

    void invalidate() {
        capturedGen = -1;
    }

    private boolean capture(Drawable icon, Rect b) {
        long now = SystemClock.uptimeMillis();
        if (capture != null && capturedBounds.equals(b) && capturedGen == 0 && now - capturedAt < RECAPTURE_MS) {
            return true;
        }
        try {
            Picture picture = new Picture();
            Canvas c = picture.beginRecording(b.width(), b.height());
            c.translate(-b.left, -b.top);
            icon.draw(c);
            picture.endRecording();
            capture = Bitmap.createBitmap(picture);
            shader = new BitmapShader(capture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            capturedBounds.set(b);
            capturedAt = now;
            capturedGen = 0;
            return true;
        } catch (Throwable t) {
            Log.e("icon capture", t);
            capture = null;
            return false;
        }
    }
}
