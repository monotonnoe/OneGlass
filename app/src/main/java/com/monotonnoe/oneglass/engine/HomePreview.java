package com.monotonnoe.oneglass.engine;

import com.monotonnoe.oneglass.R;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlendMode;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Home Screen page's live preview, as iOS 26's Liquid Glass setting shows one: a large
 * rounded card with a piece of a Home screen (a 2 x 2 widget, app icons with their names, a
 * folder and the search pill) over the picture the glass refracts: the one chosen for the glass,
 * else the wallpaper when the module may read it, else a picture made from the wallpaper's
 * colours.
 *
 * <p>Each glass is drawn by the Home screen's own renderer ({@link LiquidGlass.Sample}: its
 * shader, its look and the values in {@link Config}, which {@link Settings#preview} sets as the
 * launcher would) over a copy of the picture made as the launcher makes its own
 * ({@link LiquidGlass#previewCopy}); the tint, the widget's corners and lens, and the search
 * pill's grey follow the launcher's code. Redrawn whenever a setting changes.
 */
final class HomePreview extends FrameLayout {
    /** Apps that most phones have, for the icons; any others after them. */
    private static final String[] PREFERRED = {
            "com.sec.android.app.camera", "com.sec.android.gallery3d", "com.android.chrome",
            "com.samsung.android.messaging", "com.samsung.android.dialer",
            "com.google.android.youtube", "com.google.android.gm", "com.google.android.apps.maps",
            "com.android.vending", "com.sec.android.app.clockpackage",
            "com.samsung.android.calendar", "com.sec.android.app.popupcalculator",
            "com.samsung.android.app.notes", "com.spotify.music", "org.telegram.messenger",
            "com.whatsapp", "com.android.settings",
    };
    private static final int ICONS = 7;
    private static final int FOLDER_ICONS = 4;
    private static final int MAX_SIDE = 1440;

    private final Ui ui;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix srcToView = new Matrix();
    private final Matrix copyToView = new Matrix();
    private final RectF widget = new RectF();
    private final RectF folder = new RectF();
    private final RectF pill = new RectF();
    private final RectF[] icons = new RectF[ICONS];
    private final RectF tmp = new RectF();
    private final LiquidGlass.Sample[] samples = new LiquidGlass.Sample[ICONS + 3];
    private final GlassButton pictureButton;

    private Bitmap source;
    /**
     * The launcher's copies of the picture for {@link #copiesSat}, by blur bucket: each glass
     * blurs by its own size (icons, the widget and the pill each their own few pixels).
     */
    private final java.util.Map<Integer, Bitmap> copies = new java.util.HashMap<>();
    private final java.util.Set<Integer> pending = new java.util.HashSet<>();
    private final java.util.Set<Integer> needed = new java.util.HashSet<>();
    private int copiesSat = -1;
    private Bitmap blurCopy;
    private float blurWanted = Float.NaN;
    private final List<Drawable> appIcons = new ArrayList<>();
    private final List<String> appLabels = new ArrayList<>();
    private float iconSize;
    private float labelGap;
    private boolean released;

    HomePreview(Ui ui, Runnable onPicture) {
        super(ui.context);
        this.ui = ui;
        setWillNotDraw(false);
        setClipChildren(false);
        for (int i = 0; i < icons.length; i++) icons[i] = new RectF();
        for (int i = 0; i < samples.length; i++) samples[i] = new LiquidGlass.Sample();
        hue.setBlendMode(BlendMode.HUE);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(ui.medium);
        label.setTextSize(ui.sp(11.5f));
        label.setShadowLayer(ui.dp(2f), 0f, ui.dp(0.5f), 0x66000000);
        big.setTextAlign(Paint.Align.LEFT);
        big.setColor(0xFFFFFFFF);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ui.dp(30));
            }
        });
        setClipToOutline(true);
        setContentDescription(ui.context.getString(R.string.preview_description));

        pictureButton = new GlassButton(ui, null, GlassButton.GLYPH_PHOTO, true);
        pictureButton.setColors(ui.night ? 0x33000000 : 0x33FFFFFF, 0xFFFFFFFF);
        pictureButton.setContentDescription(ui.context.getString(R.string.picture_choose));
        pictureButton.setOnClickListener(v -> onPicture.run());
        pictureButton.setBackdrop(c -> {
            c.save();
            c.translate(-pictureButton.getLeft(), -pictureButton.getTop());
            drawBackground(c);
            c.restore();
        });
        // In the corner, level with the search pill (the card's 18 dp margin, less the
        // button's own 4 dp around its circle).
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM | android.view.Gravity.END);
        bp.rightMargin = ui.px(14);
        bp.bottomMargin = ui.px(13);
        addView(pictureButton, bp);
        load();
    }

    /** The picture or the icons may have changed (a picture chosen or removed). */
    void reloadPicture() {
        load();
    }

    /** A setting changed: the copy for the new blur and saturation, and a redraw. */
    void refresh() {
        updateCopy();
        invalidate();
        pictureButton.invalidate();
    }

    void release() {
        released = true;
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ the picture

    private void load() {
        android.content.Context ctx = ui.context.getApplicationContext();
        worker.execute(() -> {
            Bitmap pic = readPicture(ctx);
            List<Drawable> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            readApps(ctx, found, names);
            Bitmap result = pic;
            main.post(() -> {
                if (released) return;
                source = result;
                copies.clear();
                pending.clear();
                copiesSat = -1;
                blurCopy = null;
                blurWanted = Float.NaN;
                appIcons.clear();
                appIcons.addAll(found);
                appLabels.clear();
                appLabels.addAll(names);
                mapPicture();
                refresh();
            });
        });
    }

    private static Bitmap readPicture(android.content.Context ctx) {
        File f = new File(ctx.getFilesDir(), SettingsProvider.PICTURE_FILE);
        if (f.isFile()) {
            Bitmap b = decode(f);
            if (b != null) return b;
        }
        WallpaperManager wm = null;
        try {
            wm = WallpaperManager.getInstance(ctx);
            if (wm.getWallpaperInfo() == null) {
                // Most phones refuse this without a permission the module does not ask for.
                Drawable d = wm.getDrawable();
                if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                    Bitmap b = soft(((BitmapDrawable) d).getBitmap());
                    try {
                        wm.forgetLoadedWallpaper();
                    } catch (Throwable ignored) {
                    }
                    return b;
                }
            }
        } catch (Throwable ignored) {
            // Not readable: the colours below.
        }
        WallpaperColors colors = null;
        try {
            if (wm != null) colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable ignored) {
        }
        return generated(colors);
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int side = Math.max(o.outWidth, o.outHeight);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (side / (o.inSampleSize * 2) >= MAX_SIDE) o.inSampleSize *= 2;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A software copy no larger than {@link #MAX_SIDE}. */
    private static Bitmap soft(Bitmap b) {
        Bitmap s = b.getConfig() == Bitmap.Config.HARDWARE
                ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
        float k = Math.min(1f, MAX_SIDE / (float) Math.max(s.getWidth(), s.getHeight()));
        if (k >= 1f) return s == b ? s.copy(Bitmap.Config.ARGB_8888, false) : s;
        return Bitmap.createScaledBitmap(s, Math.max(1, Math.round(s.getWidth() * k)),
                Math.max(1, Math.round(s.getHeight() * k)), true);
    }

    /**
     * A wallpaper-like picture in the wallpaper's colours (or a dusk of blue, violet and
     * orange): soft colour fields and a few bright flowing bands, so the lens has edges to bend.
     */
    static Bitmap generated(WallpaperColors colors) {
        int a = 0xFF1E3A8A;
        int b = 0xFF7C3AED;
        int c = 0xFFF97316;
        if (colors != null) {
            a = 0xFF000000 | colors.getPrimaryColor().toArgb();
            if (colors.getSecondaryColor() != null) b = 0xFF000000 | colors.getSecondaryColor().toArgb();
            if (colors.getTertiaryColor() != null) c = 0xFF000000 | colors.getTertiaryColor().toArgb();
            a = vivid(a, 0.42f);
            b = vivid(b, 0.6f);
            c = vivid(c, 0.78f);
        }
        int w = 720;
        int h = 1440;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.3f, h, new int[]{a, b, c},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        float[][] blobs = {{0.15f, 0.2f, 0.55f}, {0.9f, 0.45f, 0.5f}, {0.3f, 0.8f, 0.6f},
                {0.75f, 0.05f, 0.35f}};
        int[] blobColors = {lighter(c, 0.25f), lighter(a, 0.35f), lighter(b, 0.3f),
                lighter(c, 0.5f)};
        for (int i = 0; i < blobs.length; i++) {
            float cx = blobs[i][0] * w;
            float cy = blobs[i][1] * h;
            float r = blobs[i][2] * w;
            p.setShader(new RadialGradient(cx, cy, r, blobColors[i] & 0xD0FFFFFF | 0xB0000000,
                    blobColors[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        Path band = new Path();
        for (int i = 0; i < 5; i++) {
            band.reset();
            float y0 = h * (0.12f + i * 0.19f);
            band.moveTo(-w * 0.1f, y0);
            band.cubicTo(w * 0.3f, y0 - h * 0.12f, w * 0.65f, y0 + h * 0.14f, w * 1.1f,
                    y0 - h * 0.05f);
            p.setStrokeWidth(w * (0.05f + 0.02f * (i % 3)));
            p.setColor(Ui.alpha(lighter(i % 2 == 0 ? b : c, 0.55f), 0.55f));
            cv.drawPath(band, p);
            p.setStrokeWidth(w * 0.008f);
            p.setColor(0x73FFFFFF);
            cv.drawPath(band, p);
        }
        return bmp;
    }

    private static int vivid(int color, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    private static int lighter(int color, float t) {
        return Ui.mix(color, 0xFFFFFFFF, t);
    }

    /** Launcher apps' icons and names, the common ones first. */
    private static void readApps(android.content.Context ctx, List<Drawable> icons,
                                 List<String> names) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER), 0);
            List<ResolveInfo> picked = new ArrayList<>();
            for (String pkg : PREFERRED) {
                for (ResolveInfo ri : all) {
                    if (pkg.equals(ri.activityInfo.packageName) && !picked.contains(ri)) {
                        picked.add(ri);
                        break;
                    }
                }
            }
            for (ResolveInfo ri : all) {
                if (picked.size() >= ICONS + FOLDER_ICONS) break;
                if (picked.contains(ri) || ctx.getPackageName().equals(ri.activityInfo.packageName)) {
                    continue;
                }
                picked.add(ri);
            }
            for (int i = 0; i < picked.size() && i < ICONS + FOLDER_ICONS; i++) {
                ResolveInfo ri = picked.get(i);
                icons.add(ri.loadIcon(pm));
                names.add(String.valueOf(ri.loadLabel(pm)));
            }
        } catch (Throwable ignored) {
            // No icons: the glass alone.
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float pad = ui.dp(18);
        float cell = (w - 2f * pad) / 4f;
        float icon = Math.min(cell * 0.72f, ui.dp(60));
        float row = icon + ui.dp(30);
        int h = Math.round(pad + ui.dp(4) + 3f * row + ui.dp(8) + ui.dp(44) + pad);
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float pad = ui.dp(18);
        float cell = (w - 2f * pad) / 4f;
        iconSize = Math.min(cell * 0.72f, ui.dp(60));
        float row = iconSize + ui.dp(30);
        labelGap = ui.dp(6);
        float top = pad + ui.dp(4);
        float inset = (cell - iconSize) / 2f;
        // The widget over the first two columns of the first two rows, as One UI's 2 x 2.
        widget.set(pad + inset, top, pad + cell + inset + iconSize, top + row + iconSize);
        int k = 0;
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 4; col++) {
                if (r < 2 && col < 2) continue;
                if (k >= ICONS + 1) break;
                float x = pad + col * cell + inset;
                float y = top + r * row;
                if (r == 1 && col == 3) {
                    folder.set(x, y, x + iconSize, y + iconSize);
                } else {
                    icons[k].set(x, y, x + iconSize, y + iconSize);
                    k++;
                }
            }
        }
        float pillH = ui.dp(40);
        float pillW = Math.min(w * 0.42f, ui.dp(150));
        float py = h - pad - ui.dp(2) - pillH;
        pill.set(w / 2f - pillW / 2f, py, w / 2f + pillW / 2f, py + pillH);
        mapPicture();
        updateCopy();
    }

    /** The picture centre-cropped into the card. */
    private void mapPicture() {
        srcToView.reset();
        if (source == null || getWidth() == 0) return;
        float s = Math.max(getWidth() / (float) source.getWidth(),
                getHeight() / (float) source.getHeight());
        srcToView.setScale(s, s);
        srcToView.postTranslate((getWidth() - source.getWidth() * s) / 2f,
                (getHeight() - source.getHeight() * s) / 2f);
    }

    private float viewPerSource() {
        float[] v = new float[9];
        srcToView.getValues(v);
        return v[Matrix.MSCALE_X] > 0f ? v[Matrix.MSCALE_X] : 1f;
    }

    /** The glass region of an icon, as the launcher measures one without its outline. */
    private void iconRegion(RectF icon, RectF out) {
        out.set(icon);
        float inset = icon.width() * Config.blurInset;
        out.inset(inset, inset);
    }

    /**
     * The launcher's blur bucket for a glass over {@code r}: the blur setting is a share of
     * the glass's short side, in steps of {@link LiquidGlass#BLUR_STEP} px (LiquidGlass.keyOf).
     */
    private static int bucketOf(RectF r) {
        float radius = Config.glassBlur * Math.min(r.width(), r.height());
        return Math.max(0, Math.round(radius / LiquidGlass.BLUR_STEP));
    }

    /**
     * The launcher's copies for this blur and saturation: one per blur bucket the icons, the
     * widget and the search pill take (each glass blurs by its own size, as on the Home
     * screen), made in the background.
     */
    private void updateCopy() {
        Bitmap src = source;
        if (src == null || getWidth() == 0) return;
        int sat = Math.round(Config.glassSaturation * 100f);
        if (sat != copiesSat) {
            copies.clear();
            pending.clear();
            copiesSat = sat;
        }
        float scale = viewPerSource();
        needed.clear();
        iconRegion(icons[0], tmp);
        needed.add(bucketOf(tmp));
        needed.add(bucketOf(widget));
        needed.add(bucketOf(pill));
        for (Integer bucket : needed) {
            if (copies.containsKey(bucket) || pending.contains(bucket)) continue;
            pending.add(bucket);
            float radiusSrc = bucket * LiquidGlass.BLUR_STEP / scale;
            worker.execute(() -> {
                Bitmap c;
                try {
                    c = LiquidGlass.previewCopy(src, radiusSrc, sat / 100f);
                } catch (Throwable t) {
                    XLog.e("preview copy", t);
                    return;
                }
                main.post(() -> {
                    if (released || source != src || sat != copiesSat) return;
                    pending.remove(bucket);
                    if (!needed.contains(bucket)) return;
                    copies.put(bucket, c);
                    // The ones no glass takes any more go once every glass has its own.
                    if (copies.keySet().containsAll(needed)) copies.keySet().retainAll(needed);
                    invalidate();
                    pictureButton.invalidate();
                });
            });
        }
        // The Blur effect is the compositor's blur (One UI's own radius) and no lens.
        float heavy = Config.blurRadius / 3f / scale;
        if (Settings.style == Settings.STYLE_BLUR && heavy != blurWanted) {
            blurWanted = heavy;
            worker.execute(() -> {
                Bitmap b;
                try {
                    b = LiquidGlass.previewCopy(src, heavy, 1f);
                } catch (Throwable t) {
                    XLog.e("preview copy", t);
                    return;
                }
                main.post(() -> {
                    if (released || source != src || heavy != blurWanted) return;
                    blurCopy = b;
                    invalidate();
                    pictureButton.invalidate();
                });
            });
        }
    }

    /** The copy for a glass over {@code r}: its own bucket's, else the nearest made yet. */
    private Bitmap copyFor(RectF r) {
        int bucket = bucketOf(r);
        Bitmap own = copies.get(bucket);
        if (own != null) return own;
        Bitmap near = null;
        int best = Integer.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Bitmap> e : copies.entrySet()) {
            int d = Math.abs(e.getKey() - bucket);
            if (d < best) {
                best = d;
                near = e.getValue();
            }
        }
        return near;
    }

    // ------------------------------------------------------------------ drawing

    private void drawBackground(Canvas c) {
        if (source == null) {
            c.drawColor(ui.night ? 0xFF1C1C1E : 0xFFD1D1D6);
            return;
        }
        c.drawBitmap(source, srcToView, bitmapPaint);
    }

    @Override
    protected void onDraw(Canvas c) {
        drawBackground(c);
        if (source == null) return;
        boolean night = ui.night;
        int alpha = Settings.backgroundAlpha;
        // With the Blur effect only the icons have it (CellGlass, SearchGlass: liquid only).
        boolean liquid = Settings.style == Settings.STYLE_LIQUID;
        // The widget.
        drawGlass(c, 0, widget, widgetCorner(), Config.glassWidgets && liquid,
                Config.lensHeight, Config.lensAmount, alpha, night, false);
        drawWidgetContent(c);
        // The icons.
        for (int i = 0; i < ICONS; i++) {
            iconRegion(icons[i], tmp);
            float corner = Config.corner * tmp.width();
            drawGlass(c, 1 + i, tmp, corner, true, Config.lensHeight, Config.lensAmount, alpha,
                    night, false);
            drawIcon(c, i, icons[i]);
            drawLabel(c, i < appLabels.size() ? appLabels.get(i) : null, icons[i]);
        }
        // The folder.
        iconRegion(folder, tmp);
        float fc = Config.corner * tmp.width();
        drawGlass(c, 1 + ICONS, tmp, fc, Config.glassFolders && liquid, Config.lensHeight,
                Config.lensAmount, alpha, night, false);
        drawFolderContent(c);
        drawLabel(c, ui.context.getString(R.string.preview_folder), folder);
        // The search pill.
        drawGlass(c, 2 + ICONS, pill, pill.height() / 2f, Config.glassSearch && liquid,
                SearchGlass.HOME_PILL_LENS_HEIGHT, SearchGlass.HOME_PILL_LENS_AMOUNT, alpha,
                night, true);
        drawPillContent(c);
    }

    private float widgetCorner() {
        if (Config.iosWidgetCorners) {
            return Math.min(Math.min(widget.width(), widget.height()) / 2f,
                    WidgetCorners.radius(widget.width(), widget.height(), new int[]{2, 2})
                            * 1.24f);
        }
        return ui.dp(26);
    }

    /**
     * One glass. With the liquid glass: the launcher's lens pass over its copy and its tint;
     * with the Blur effect (or without the shaders): the blurred picture and the tint; with the
     * glass off for this kind: One UI's own plain background.
     */
    private void drawGlass(Canvas c, int slot, RectF r, float corner, boolean on, float lh,
                           float la, int alpha, boolean night, boolean search) {
        if (!on) {
            fill.setShader(null);
            fill.setColor(search ? 0x59808080 : night ? 0x8C2C2C2E : 0x73FFFFFF);
            c.drawRoundRect(r, corner, corner, fill);
            return;
        }
        int tint;
        int flatAlpha = alpha;
        if (search) {
            // SearchGlass's home pill: grey, half the search grey setting, whatever the tone.
            int a = Math.max(0, Math.min(255, Math.round(Config.searchGray * 0.5f * 255f)));
            tint = a > 0 ? (a << 24) | SearchGlass.GRAY : 0;
            flatAlpha = 0;
        } else {
            // IconBlur.autoTint: the automatic veil goes into the lens pass.
            tint = alpha > 0 && Settings.tintColor == 0
                    ? (Math.min(255, alpha) << 24) | (night ? 0x101010 : 0xFFFFFF) : 0;
        }
        Bitmap copy = copyFor(r);
        boolean liquid = Settings.style == Settings.STYLE_LIQUID && copy != null
                && Build.VERSION.SDK_INT >= 33 && c.isHardwareAccelerated();
        if (liquid) {
            try {
                copyToView.setScale(source.getWidth() / (float) copy.getWidth(),
                        source.getHeight() / (float) copy.getHeight());
                copyToView.postConcat(srcToView);
                samples[slot].draw(c, copy, copyToView, r, corner, lh, la, tint, ui.density,
                        night);
                if (!search && tint == 0) drawTint(c, r, corner, flatAlpha, night);
                return;
            } catch (Throwable t) {
                XLog.e("preview glass", t);
            }
        }
        Bitmap b = blurCopy != null && Settings.style == Settings.STYLE_BLUR ? blurCopy : copy;
        if (b != null) {
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(source.getWidth() / (float) b.getWidth(),
                    source.getHeight() / (float) b.getHeight());
            m.postConcat(srcToView);
            sh.setLocalMatrix(m);
            fill.setShader(sh);
            c.drawRoundRect(r, corner, corner, fill);
            fill.setShader(null);
        }
        if (search) {
            if (tint != 0) {
                fill.setColor(tint);
                c.drawRoundRect(r, corner, corner, fill);
            }
        } else {
            drawTint(c, r, corner, flatAlpha, night);
        }
    }

    /** IconBlur.drawTint: the automatic tone, or the chosen colour's hue and tone. */
    private void drawTint(Canvas c, RectF r, float corner, int alpha, boolean night) {
        if (alpha <= 0) return;
        int color = Settings.tintColor & 0xFFFFFF;
        if (Settings.tintColor == 0) {
            color = night ? 0x101010 : 0xFFFFFF;
        } else {
            hue.setColor((Math.min(255, alpha * 2) << 24) | color);
            c.drawRoundRect(r, corner, corner, hue);
        }
        fill.setShader(null);
        fill.setColor((Math.min(255, alpha) << 24) | color);
        c.drawRoundRect(r, corner, corner, fill);
    }

    private void drawIcon(Canvas c, int i, RectF r) {
        if (i >= appIcons.size()) return;
        drawAppGlyph(c, appIcons.get(i), r);
    }

    /**
     * An app's glyph over its glass, as an icon pack of clear icons shows it: an adaptive
     * icon's foreground layer (its own background left out), else the icon a little smaller.
     */
    private void drawAppGlyph(Canvas c, Drawable d, RectF r) {
        if (d instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) d).getForeground();
            if (fg != null) {
                // The layer is 108 units with the icon's 72 in the middle.
                float grow = r.width() * 0.25f;
                fg.setBounds(Math.round(r.left - grow), Math.round(r.top - grow),
                        Math.round(r.right + grow), Math.round(r.bottom + grow));
                c.save();
                c.clipRect(r);
                fg.draw(c);
                c.restore();
                return;
            }
        }
        float in = r.width() * 0.14f;
        d.setBounds(Math.round(r.left + in), Math.round(r.top + in), Math.round(r.right - in),
                Math.round(r.bottom - in));
        d.draw(c);
    }

    private void drawLabel(Canvas c, String text, RectF icon) {
        if (text == null) return;
        float max = icon.width() + ui.dp(18);
        String s = text;
        if (label.measureText(s) > max) {
            int n = label.breakText(s, true, max - label.measureText("…"), null);
            s = s.substring(0, Math.max(0, n)) + "…";
        }
        c.drawText(s, icon.centerX(), icon.bottom + labelGap - label.ascent(), label);
    }

    private void drawFolderContent(Canvas c) {
        iconRegion(folder, tmp);
        float g = tmp.width() * 0.3f;
        float gap = tmp.width() * 0.07f;
        float x0 = tmp.centerX() - gap / 2f - g;
        float y0 = tmp.centerY() - gap / 2f - g;
        for (int i = 0; i < FOLDER_ICONS; i++) {
            float x = x0 + (i % 2) * (g + gap);
            float y = y0 + (i / 2) * (g + gap);
            RectF r = new RectF(x, y, x + g, y + g);
            int idx = ICONS + i;
            if (idx < appIcons.size()) {
                Drawable d = appIcons.get(idx);
                float in = -g * 0.04f;
                d.setBounds(Math.round(r.left + in), Math.round(r.top + in),
                        Math.round(r.right - in), Math.round(r.bottom - in));
                c.save();
                path.reset();
                path.addRoundRect(r, g * 0.24f, g * 0.24f, Path.Direction.CW);
                c.clipPath(path);
                d.draw(c);
                c.restore();
            } else {
                fill.setColor(0x80FFFFFF);
                c.drawRoundRect(r, g * 0.24f, g * 0.24f, fill);
            }
        }
    }

    private void drawWidgetContent(Canvas c) {
        boolean glass = Config.glassWidgets && Settings.style == Settings.STYLE_LIQUID;
        int color = glass ? 0xFFFFFFFF : ui.label;
        int soft = Ui.alpha(color, 0.75f);
        float x = widget.left + widget.width() * 0.12f;
        float y = widget.top + widget.height() * 0.12f;
        big.setShadowLayer(glass ? ui.dp(2f) : 0f, 0f, ui.dp(0.5f), 0x40000000);
        big.setTypeface(ui.semibold);
        big.setTextSize(ui.sp(13f));
        big.setColor(soft);
        String day = ui.context.getString(R.string.preview_widget_place);
        c.drawText(day, x, y - big.ascent(), big);
        y += big.getFontSpacing();
        big.setTypeface(ui.regular);
        big.setTextSize(widget.height() * 0.3f);
        big.setColor(color);
        c.drawText("21°", x - big.getTextSize() * 0.04f, y - big.ascent() * 0.92f, big);
        // A sun.
        fill.setShader(null);
        fill.setColor(0xFFFFD60A);
        float sr = widget.width() * 0.07f;
        float sx = widget.left + widget.width() * 0.17f;
        float sy = widget.bottom - widget.height() * 0.3f;
        c.drawCircle(sx, sy, sr, fill);
        big.setTypeface(ui.semibold);
        big.setTextSize(ui.sp(12f));
        big.setColor(soft);
        float ly = widget.bottom - widget.height() * 0.1f;
        c.drawText(ui.context.getString(R.string.preview_widget_sunny), x, ly, big);
    }

    private void drawPillContent(Canvas c) {
        int color = 0xFFFFFFFF;
        String s = ui.context.getString(R.string.preview_search);
        big.setTypeface(ui.semibold);
        big.setTextSize(ui.sp(15f));
        big.setColor(color);
        big.setShadowLayer(ui.dp(2f), 0f, ui.dp(0.5f), 0x33000000);
        float glass = ui.dp(15);
        float gap = ui.dp(6);
        float tw = big.measureText(s);
        float x = pill.centerX() - (glass + gap + tw) / 2f;
        float cy = pill.centerY();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(ui.dp(2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        float r = glass * 0.34f;
        float gx = x + r + ui.dp(1);
        float gy = cy - ui.dp(1.5f);
        c.drawCircle(gx, gy, r, p);
        c.drawLine(gx + r * 0.72f, gy + r * 0.72f, gx + r * 1.55f, gy + r * 1.55f, p);
        Paint.FontMetrics fm = big.getFontMetrics();
        c.drawText(s, x + glass + gap, cy - (fm.ascent + fm.descent) / 2f, big);
    }
}
