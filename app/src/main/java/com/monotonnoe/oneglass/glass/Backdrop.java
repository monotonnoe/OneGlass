package com.monotonnoe.oneglass.glass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.view.View;

import com.monotonnoe.oneglass.Config;

/**
 * The launcher window is transparent: the wallpaper lives in its own surface underneath, so a
 * view can not sample it. Instead the home wallpaper bitmap is loaded once, shrunk and frosted,
 * and drawn through the glass with a matrix that keeps it glued to the display (and to the
 * wallpaper scroll offset the launcher reports), exactly where the real wallpaper is.
 */
public final class Backdrop {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Bitmap blurred;
    /** Size of the original wallpaper bitmap. */
    private static volatile int fullW;
    private static volatile int fullH;
    /** small bitmap px = full px * k. */
    private static volatile float k = 1f;
    private static volatile float xOffset = 0.5f;
    private static volatile float yOffset = 0.5f;
    private static volatile int generation;

    private static BitmapShader shader;
    private static Bitmap shaderFor;
    private static BitmapShader flat;

    private static Context appContext;
    private static boolean listening;
    private static int loadedBlur = -1;
    private static int pending;

    private static final Matrix M = new Matrix();
    private static final Matrix L = new Matrix();
    private static final Matrix LINV = new Matrix();

    private Backdrop() {
    }

    public static int generation() {
        return generation;
    }

    public static float xOffset() {
        return xOffset;
    }

    public static float yOffset() {
        return yOffset;
    }

    public static void setOffsets(float x, float y) {
        if (x >= 0f && x <= 1f) {
            xOffset = x;
        }
        if (y >= 0f && y <= 1f) {
            yOffset = y;
        }
    }

    /** Starts loading (once) and keeps following wallpaper changes. Main thread. */
    public static void ensure(Context context) {
        if (appContext != null) {
            return;
        }
        appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        reload();
        try {
            // pictures in credential-encrypted storage (ours, BlurIcons') appear after the unlock
            appContext.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    attempts = 0;
                    reload();
                }
            }, new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.e("unlock receiver", t);
        }
        if (!listening) {
            listening = true;
            try {
                WallpaperManager.getInstance(appContext).addOnColorsChangedListener(
                        (colors, which) -> {
                            if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
                                reload();
                            }
                        }, MAIN);
            } catch (Throwable t) {
                Log.e("no wallpaper change listener", t);
            }
        }
    }

    /** Reloads if the frost amount changed. Main thread. */
    public static void onConfigChanged() {
        if (appContext != null && loadedBlur != Config.current.blur) {
            reload();
        }
    }

    /** Debounced reload on a worker thread. */
    public static void reload() {
        if (appContext == null) {
            return;
        }
        final int ticket = ++pending;
        MAIN.postDelayed(() -> {
            if (ticket != pending) {
                return;
            }
            final int blurKey = Config.current.blur;
            final float frostDp = GlassSpec.frost(Config.current);
            new Thread(() -> load(blurKey, frostDp), "OneGlass-backdrop").start();
        }, 300);
    }

    /** Picture chosen in OneGlass for live wallpapers (served by its PictureProvider). */
    public static final android.net.Uri PICTURE_URI =
            android.net.Uri.parse("content://com.monotonnoe.oneglass.picture/picture");
    /** BlurIcons keeps the same kind of picture; reused when OneGlass has none. */
    private static final android.net.Uri BLURICONS_PICTURE_URI =
            android.net.Uri.parse("content://dev.flexy.bluricons.settings/picture");

    private static int attempts;

    private static void load(int blurKey, float frostDp) {
        Context ctx = appContext;
        Bitmap full = null;
        boolean live = false;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            live = wm.getWallpaperInfo() != null;
            if (!live) {
                full = readWallpaper(wm);
            }
        } catch (Throwable t) {
            Log.e("wallpaper read failed", t);
        }
        if (full == null) {
            // live wallpaper (or no access to the image): use the picture the user picked
            full = readPicture(ctx, PICTURE_URI, "OneGlass");
            if (full == null) {
                full = readPicture(ctx, BLURICONS_PICTURE_URI, "BlurIcons");
            }
            if (full == null) {
                Log.i(live ? "live wallpaper and no picture chosen in OneGlass yet: glass uses a neutral fill"
                        : "wallpaper unreadable and no picture chosen: glass uses a neutral fill");
                if (attempts++ < 6) {
                    MAIN.postDelayed(Backdrop::reload, 5000);
                }
            }
        }
        Bitmap small = null;
        int fw = 0, fh = 0;
        float kk = 1f;
        if (full != null) {
            try {
                if (full.getConfig() == Bitmap.Config.HARDWARE) {
                    full = full.copy(Bitmap.Config.ARGB_8888, false);
                }
                fw = full.getWidth();
                fh = full.getHeight();
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int sw = Math.min(dm.widthPixels, dm.heightPixels);
                int sh = Math.max(dm.widthPixels, dm.heightPixels);
                float s = Math.max(sw / (float) fw, sh / (float) fh);
                // Figma's GLASS frost is light: keep enough resolution for crisp refraction,
                // shrink only when the (gaussian sigma) blur is large.
                float sigmaPx = frostDp * dm.density;
                float screenToSmall = Math.max(0.2f, Math.min(0.5f, 3f / Math.max(0.001f, sigmaPx)));
                kk = s * screenToSmall;
                int w = Math.max(16, Math.round(fw * kk));
                int h = Math.max(16, Math.round(fh * kk));
                kk = w / (float) fw;
                Bitmap scaled = Bitmap.createScaledBitmap(full, w, h, true);
                // 3 box passes of width w = 2r+1: sigma^2 = 3 * (w^2 - 1) / 12  =>  w = sqrt(4 sigma^2 + 1)
                float sigmaSmall = sigmaPx * screenToSmall;
                int r = Math.round((float) (Math.sqrt(4f * sigmaSmall * sigmaSmall + 1f) - 1f) / 2f);
                small = BoxBlur.blur(scaled, Math.min(r, 24));
            } catch (Throwable t) {
                Log.e("wallpaper blur failed", t);
                small = null;
            }
        }
        final Bitmap result = small;
        final int rw = fw, rh = fh;
        final float rk = kk;
        MAIN.post(() -> {
            blurred = result;
            fullW = rw;
            fullH = rh;
            k = rk;
            loadedBlur = blurKey;
            generation++;
            Log.i(result != null
                    ? "backdrop ready: wallpaper " + rw + "x" + rh + " -> " + result.getWidth() + "x" + result.getHeight()
                    : "backdrop unavailable, glass uses a neutral frosted fill");
            GlassTracker.refreshAll();
        });
    }

    private static Bitmap readPicture(Context ctx, android.net.Uri uri, String from) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target / 2) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                Bitmap b = in == null ? null : BitmapFactory.decodeStream(in, null, o2);
                if (b != null) {
                    Log.i("backdrop picture from " + from + ": " + b.getWidth() + "x" + b.getHeight());
                }
                return b;
            }
        } catch (Throwable t) {
            Log.i("no picture from " + from + ": " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static Bitmap readWallpaper(WallpaperManager wm) {
        try {
            Drawable d = wm.getDrawable(WallpaperManager.FLAG_SYSTEM);
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null) {
                    return b;
                }
            }
        } catch (Throwable t) {
            Log.e("getDrawable(FLAG_SYSTEM)", t);
        }
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null) {
                    return b;
                }
            }
        } catch (Throwable t) {
            Log.e("getDrawable()", t);
        }
        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
            if (pfd != null) {
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            }
        } catch (Throwable t) {
            Log.e("getWallpaperFile", t);
        }
        try {
            // hidden WallpaperManager#getBitmap(), as BlurIcons' last resort
            java.lang.reflect.Method m = WallpaperManager.class.getDeclaredMethod("getBitmap");
            m.setAccessible(true);
            Object b = m.invoke(wm);
            if (b instanceof Bitmap) {
                return (Bitmap) b;
            }
        } catch (Throwable t) {
            Log.i("getBitmap(): " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * A shader that paints the frosted wallpaper in {@code host}'s local coordinates.
     * Main thread only; the returned object is shared and reconfigured on every call.
     */
    public static Shader shaderFor(View host) {
        return shaderFor(host, null);
    }

    /**
     * @param canvasToHost when drawing in a canvas that is not in {@code host}'s own coordinates
     *                     (e.g. inside a drawable), maps that canvas to the host's coordinates
     */
    public static Shader shaderFor(View host, Matrix canvasToHost) {
        Bitmap b = blurred;
        if (b == null || fullW <= 0 || fullH <= 0) {
            return flat();
        }
        if (shader == null || shaderFor != b) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shaderFor = b;
        }
        View root = host.getRootView();
        int sw = root.getWidth();
        int sh = root.getHeight();
        if (sw <= 0 || sh <= 0) {
            DisplayMetrics dm = host.getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = dm.heightPixels;
        }
        float s = Math.max(sw / (float) fullW, sh / (float) fullH);
        float dx = (sw - fullW * s) * xOffset;
        float dy = (sh - fullH * s) * yOffset;
        // small bitmap -> display
        M.setScale(s / k, s / k);
        M.postTranslate(dx, dy);
        // display -> host local
        MatrixUtil.localToScreen(host, L);
        if (L.invert(LINV)) {
            M.postConcat(LINV);
        }
        if (canvasToHost != null && canvasToHost.invert(LINV)) {
            M.postConcat(LINV);
        }
        shader.setLocalMatrix(M);
        return shader;
    }

    private static Shader flat() {
        if (flat == null) {
            Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            one.eraseColor(0x8C8C8C8C);
            flat = new BitmapShader(one, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return flat;
    }
}
