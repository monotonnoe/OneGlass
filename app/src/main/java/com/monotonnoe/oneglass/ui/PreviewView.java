package com.monotonnoe.oneglass.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.BoxBlur;
import com.monotonnoe.oneglass.glass.ContinuousPath;
import com.monotonnoe.oneglass.glass.GlassShader;
import com.monotonnoe.oneglass.glass.GlassSpec;

/**
 * A miniature iOS 27 home screen laid out from the Figma frame "Home Screen - iPhone"
 * (402x874pt): 4x4 icon grid at x=30/123/215/308, rows every ~100pt, the 77x30 "Search" pill
 * at y=704 and the 368x103 dock at (17, 754). Rendered with the same glass code as the launcher.
 */
public final class PreviewView extends View {

    private static final float FW = 402f;
    private static final float FH = 874f;
    private static final float[] COLS = {30f, 123f, 215f, 308f};
    private static final float[] ROWS = {90f, 190f, 291f, 391f};
    private static final int[] ICON_COLORS = {
            0xFF34C759, 0xFFFFFFFF, 0xFFFF9F0A, 0xFF8E8E93,
            0xFF0A84FF, 0xFF1C1C1E, 0xFFFFFFFF, 0xFF30D158,
            0xFF000000, 0xFFFF3B30, 0xFF007AFF, 0xFF5E5CE6,
            0xFFFF2D55, 0xFF1C1C1E, 0xFF6E6E73, 0xFF8E8E93,
    };
    private static final int[] DOCK_COLORS = {0xFF34C759, 0xFF0A84FF, 0xFF30D158, 0xFFFF2D55};

    private Config config = new Config();
    private Bitmap wallpaper;
    private Bitmap blurred;
    private BitmapShader blurShader;
    private int blurFor = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();

    public PreviewView(Context context) {
        super(context);
        rim.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setConfig(Config c) {
        config = c;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(w * FH / FW);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0 && h > 0) {
            wallpaper = makeWallpaper(w, h);
            blurFor = -1;
        }
    }

    private float u() {
        return getWidth() / FW;
    }

    /** Pixels per "dp" of the miniature: 1pt of the Figma frame. */
    private float previewDp() {
        return u();
    }

    private void ensureBlur() {
        if (wallpaper == null || blurFor == config.blur) {
            return;
        }
        float sigma = GlassSpec.frost(config) * previewDp();
        float k = Math.max(0.2f, Math.min(0.5f, 3f / Math.max(0.001f, sigma)));
        int w = Math.max(16, Math.round(wallpaper.getWidth() * k));
        int h = Math.max(16, Math.round(wallpaper.getHeight() * k));
        Bitmap small = Bitmap.createScaledBitmap(wallpaper, w, h, true);
        float sk = sigma * k;
        int r = Math.round((float) (Math.sqrt(4f * sk * sk + 1f) - 1f) / 2f);
        blurred = BoxBlur.blur(small, Math.min(24, r));
        blurShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        blurShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        m.setScale(getWidth() / (float) w, getHeight() / (float) h);
        blurShader.setLocalMatrix(m);
        blurFor = config.blur;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (wallpaper == null) {
            return;
        }
        ensureBlur();
        float u = u();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        r.set(0, 0, getWidth(), getHeight());
        ContinuousPath.build(path, r, 32f * u, ContinuousPath.IOS_SMOOTHING);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawBitmap(wallpaper, 0, 0, paint);

        // Widget (2x2) in the bottom-left of the grid
        r.set(COLS[0] * u, ROWS[2] * u, (COLS[1] + 64f) * u, (ROWS[3] + 64f) * u);
        if (config.widgets) {
            glass(canvas, GlassSpec.ROLE_WIDGET, r, night);
        }
        text.setTextSize(13f * u);
        text.setAlpha(230);
        canvas.drawText("OneGlass", r.left + 14f * u, r.top + 26f * u, text);
        text.setTextSize(30f * u);
        canvas.drawText("27°", r.left + 14f * u, r.bottom - 18f * u, text);

        // Icons (two rows) + a folder
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                r.set(COLS[col] * u, ROWS[row] * u, (COLS[col] + 64f) * u, (ROWS[row] + 64f) * u);
                if (row == 1 && col == 3) {
                    folder(canvas, r, night);
                } else {
                    icon(canvas, r, ICON_COLORS[row * 4 + col]);
                }
            }
        }
        // Two more icons beside the widget
        for (int i = 0; i < 2; i++) {
            r.set(COLS[2 + i] * u, ROWS[2] * u, (COLS[2 + i] + 64f) * u, (ROWS[2] + 64f) * u);
            icon(canvas, r, ICON_COLORS[8 + i * 3]);
        }

        // Search pill
        r.set(162f * u, 704f * u, 239f * u, 734f * u);
        if (config.pill) {
            glass(canvas, GlassSpec.ROLE_PILL, r, night);
        }
        text.setTextSize(11.5f * u);
        text.setAlpha(255);
        canvas.drawText("⌕ Search", r.left + 11f * u, r.centerY() + 4f * u, text);

        // Dock
        r.set(17f * u, 754f * u, 385f * u, 857f * u);
        if (config.dock) {
            glass(canvas, GlassSpec.ROLE_DOCK, r, night);
        }
        float[] dockX = {36f, 125f, 213f, 302f};
        for (int i = 0; i < 4; i++) {
            r.set(dockX[i] * u, 774f * u, (dockX[i] + 64f) * u, 838f * u);
            icon(canvas, r, DOCK_COLORS[i]);
        }
        canvas.restore();

        String err = GlassShader.error();
        if (err != null) {
            text.setTextSize(12f * getResources().getDisplayMetrics().density);
            text.setColor(0xFFFF453A);
            canvas.drawText("AGSL: " + err, 8f, getHeight() - 8f, text);
            text.setColor(Color.WHITE);
        }
    }

    private void glass(Canvas canvas, int role, RectF rect, boolean night) {
        boolean dark = config.tintMode == Config.TINT_DARK || (config.tintMode == Config.TINT_AUTO && night);
        GlassSpec spec = GlassSpec.resolve(role, config, previewDp(), dark, rect);
        GlassShader.draw(canvas, rect, spec, blurShader, 1f);
    }

    private void icon(Canvas canvas, RectF rect, int color) {
        float rad = rect.width() * GlassSpec.ICON_RADIUS_RATIO;
        ContinuousPath.build(path, rect, rad, ContinuousPath.IOS_SMOOTHING);
        int light = blend(color, 0xFFFFFFFF, 0.25f);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, light, color, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
        rimOn(canvas, rect);
    }

    private void folder(Canvas canvas, RectF rect, boolean night) {
        if (config.folders) {
            glass(canvas, GlassSpec.ROLE_FOLDER, rect, night);
        }
        float pad = rect.width() * 0.16f;
        float gap = rect.width() * 0.08f;
        float side = (rect.width() - 2 * pad - gap) / 2f;
        RectF mini = new RectF();
        for (int i = 0; i < 4; i++) {
            float x = rect.left + pad + (i % 2) * (side + gap);
            float y = rect.top + pad + (i / 2) * (side + gap);
            mini.set(x, y, x + side, y + side);
            ContinuousPath.build(path, mini, side * GlassSpec.ICON_RADIUS_RATIO, ContinuousPath.IOS_SMOOTHING);
            paint.setColor(ICON_COLORS[(i * 5) % ICON_COLORS.length]);
            canvas.drawPath(path, paint);
        }
        paint.setColor(Color.BLACK);
        rimOn(canvas, rect);
    }

    private void rimOn(Canvas canvas, RectF rect) {
        if (!config.iconRim || config.iconRimAlpha <= 0) {
            return;
        }
        float stroke = Math.max(1f, 1.1f * previewDp());
        RectF rr = new RectF(rect);
        rr.inset(stroke / 2f, stroke / 2f);
        ContinuousPath.build(path, rr, rr.width() * GlassSpec.ICON_RADIUS_RATIO, ContinuousPath.IOS_SMOOTHING);
        int a = Math.round(255f * config.iconRimAlpha / 100f);
        rim.setShader(new LinearGradient(rr.left, rr.top, rr.right, rr.bottom,
                new int[]{(a << 24) | 0xFFFFFF, ((a / 8) << 24) | 0xFFFFFF, ((a / 8) << 24) | 0xFFFFFF, ((a / 2) << 24) | 0xFFFFFF},
                new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        rim.setStrokeWidth(stroke);
        canvas.drawPath(path, rim);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    /** A colourful, detailed backdrop so blur, refraction and dispersion are easy to judge. */
    private static Bitmap makeWallpaper(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.3f, h,
                new int[]{0xFF14245E, 0xFF5B3BB0, 0xFFE0607E, 0xFFFFA25C},
                new float[]{0f, 0.45f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float[][] blobs = {
                {0.15f, 0.22f, 0.45f},
                {0.85f, 0.35f, 0.40f},
                {0.30f, 0.62f, 0.35f},
                {0.75f, 0.90f, 0.45f},
        };
        int[] blobColors = {0xFF2EC5FF, 0xFFFF5FA2, 0xFFFFD54A, 0xFF45E39A};
        for (int i = 0; i < blobs.length; i++) {
            float[] o = blobs[i];
            int col = blobColors[i];
            p.setShader(new RadialGradient(o[0] * w, o[1] * h, o[2] * w,
                    new int[]{col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(o[0] * w, o[1] * h, o[2] * w, p);
        }
        // Fine diagonal lines: refraction and dispersion show up best on detail.
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(Math.max(1f, w / 300f));
        float step = w / 22f;
        for (float x = -h; x < w; x += step) {
            c.drawLine(x, h, x + h * 0.6f, 0, p);
        }
        return b;
    }
}
