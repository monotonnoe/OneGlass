package com.monotonnoe.oneglass.glass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.monotonnoe.oneglass.Config;

import java.lang.ref.WeakReference;

/**
 * Overlay on an icon view: the specular glass edge iOS 26/27 draws around app icons
 * (bright on the top-left, fading, catching light again bottom-right).
 */
public final class IconRimDrawable extends Drawable {

    private final WeakReference<View> host;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF lastRect = new RectF();
    private int lastAlpha = -1;

    public IconRimDrawable(View host) {
        this.host = new WeakReference<>(host);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(Canvas canvas) {
        View v = host.get();
        Config cfg = Config.current;
        if (v == null || !cfg.iconRim || cfg.iconRimAlpha <= 0) {
            return;
        }
        if (!IconGeometry.find(v, rect)) {
            return;
        }
        float dp = v.getResources().getDisplayMetrics().density;
        float stroke = Math.max(1f, 1.1f * dp);
        float half = stroke / 2f;
        rect.inset(half, half);
        int a = Math.round(255f * cfg.iconRimAlpha / 100f);
        if (!rect.equals(lastRect) || a != lastAlpha) {
            lastRect.set(rect);
            lastAlpha = a;
            ContinuousPath.build(path, rect, rect.width() * GlassSpec.ICON_RADIUS_RATIO, ContinuousPath.IOS_SMOOTHING);
            int hi = (a << 24) | 0xFFFFFF;
            int lo = ((a / 8) << 24) | 0xFFFFFF;
            int mid = ((a / 2) << 24) | 0xFFFFFF;
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{hi, lo, lo, mid}, new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
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
