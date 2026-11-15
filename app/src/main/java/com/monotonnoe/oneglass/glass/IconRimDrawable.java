package com.monotonnoe.oneglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import com.monotonnoe.oneglass.Config;

import java.lang.ref.WeakReference;

/**
 * Overlay on an app icon: the GLASS layer Figma puts over every "Default App Icon". The icon's
 * own pixels are captured and drawn back through the glass shader with only the refracting rim
 * and the light visible, so the live icon stays untouched inside.
 * Falls back to a plain specular stroke when the icon drawable can not be located.
 */
public final class IconRimDrawable extends Drawable {

    private static final long RECAPTURE_MS = 1000;

    private final WeakReference<View> host;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private Bitmap capture;
    private BitmapShader captureShader;
    private WeakReference<Drawable> capturedFrom = new WeakReference<>(null);
    private final Rect capturedBounds = new Rect();
    private long capturedAt;

    public IconRimDrawable(View host) {
        this.host = new WeakReference<>(host);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(Canvas canvas) {
        View v = host.get();
        Config cfg = Config.current;
        if (v == null || !cfg.iconRim || cfg.style != Config.STYLE_DEFAULT) {
            // Clear / Tinted icons get their glass from the tile under them
            return;
        }
        if (!IconGeometry.find(v, rect)) {
            return;
        }
        Drawable icon = IconGeometry.lastDrawable;
        if (icon != null && canvas.isHardwareAccelerated() && capture(icon)) {
            matrix.setTranslate(rect.left, rect.top);
            matrix.preScale(rect.width() / capture.getWidth(), rect.height() / capture.getHeight());
            captureShader.setLocalMatrix(matrix);
            boolean dark = GlassSpec.isDark(cfg, v.getResources());
            GlassSpec spec = GlassSpec.resolve(GlassSpec.ROLE_ICON, cfg, Units.pt(v), dark, rect);
            GlassShader.draw(canvas, rect, spec, captureShader, cfg.iconRimAlpha / 100f);
            return;
        }
        drawStroke(canvas, v, cfg);
    }

    /** Snapshot of the icon drawable, refreshed when it changes (or every second for live icons). */
    private boolean capture(Drawable icon) {
        Rect b = icon.getBounds();
        if (b.isEmpty()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (capture != null && capturedFrom.get() == icon && capturedBounds.equals(b)
                && now - capturedAt < RECAPTURE_MS) {
            return true;
        }
        try {
            Picture picture = new Picture();
            Canvas c = picture.beginRecording(b.width(), b.height());
            c.translate(-b.left, -b.top);
            icon.draw(c);
            picture.endRecording();
            // Works with hardware bitmaps inside the icon (rendered on the GPU when needed).
            capture = Bitmap.createBitmap(picture);
            captureShader = new BitmapShader(capture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            captureShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            capturedFrom = new WeakReference<>(icon);
            capturedBounds.set(b);
            capturedAt = now;
            return true;
        } catch (Throwable t) {
            Log.e("icon capture", t);
            capture = null;
            return false;
        }
    }

    private void drawStroke(Canvas canvas, View v, Config cfg) {
        float dp = Units.pt(v);
        float stroke = Math.max(1f, GlassSpec.LIGHT_WIDTH * 2f * dp);
        RectF r = new RectF(rect);
        r.inset(stroke / 2f, stroke / 2f);
        ContinuousPath.build(path, r, r.width() * GlassSpec.ICON_RADIUS_RATIO, ContinuousPath.IOS_SMOOTHING);
        int a = Math.round(255f * Math.min(1f, GlassSpec.LIGHT * 2f) * cfg.iconRimAlpha / 100f);
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{(a << 24) | 0xFFFFFF, ((a / 8) << 24) | 0xFFFFFF, ((a / 8) << 24) | 0xFFFFFF,
                        ((a / 2) << 24) | 0xFFFFFF},
                new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        paint.setStrokeWidth(stroke);
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
