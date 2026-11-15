package com.monotonnoe.oneglass.engine;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Liquid glass lock screen clock, in System UI. One UI's clock draws each digit as its own
 * TextView ({@link #DIGIT}); each is drawn as glass in the digit's shape instead: the wallpaper
 * under it, blurred and saturated like the icons' glass, refracted inwards along the glyph's
 * edges, with a light rim and a hint of the clock's own colour.
 *
 * <p>System UI cannot read a live lock wallpaper nor see the module, so One UI Home, which has
 * the picture the icons refract, sends it here with the settings ({@link LockLink}).
 */
final class LockGlass {
    static final String DIGIT = "com.samsung.android.uniform.widget.ClockNonePaddingTextView";
    static final String ACTION_PICTURE = "com.monotonnoe.oneglass.engine.LOCK_PICTURE";
    static final String ACTION_REQUEST = "com.monotonnoe.oneglass.engine.LOCK_REQUEST";
    static final String EXTRA_JPEG = "jpeg";
    static final String EXTRA_SETTINGS = "settings";
    /** Settings sent by the launcher: on, refraction %, blur %, saturation %, tint %. */
    static final int ON = 0;
    static final int REFRACTION = 1;
    static final int BLUR = 2;
    static final int SATURATION = 3;
    static final int TINT = 4;
    /** White frost, bevel width (percent of the stroke) and highlight, percent. */
    static final int FROST = 5;
    static final int BEVEL = 6;
    static final int HIGHLIGHT = 7;
    /** iOS 27 over iOS 26: on, the frosted layer's opacity, rim light, dark hairline, %. */
    static final int IOS27 = 8;
    static final int IOS27_LAYER = 9;
    static final int RIM = 10;
    static final int HAIRLINE = 11;
    /**
     * iOS 26's lock screen clock (LockLink's [30]), measured on an iPhone screenshot. [31] was
     * the clock in a font of the module's: gone, the digits keep the font set in One UI.
     */
    static final int IOS26 = 30;
    static final int CLOCK_FONT = 31;
    /**
     * The iOS 26 clock's values, fixed (the sliders keep the other looks): measured on the
     * iPhone's lock screen and checked by simulating this shader against its pixels (fill
     * 212 over a background of 175, 222-229 near the edge, the rim 243-254 over ~3 px, the
     * ring 122-142 just outside).
     */
    private static final int IOS26_FROST = 46;
    private static final int IOS26_RIM = 95;
    private static final int IOS26_RING = 30;
    private static final int IOS26_REFRACTION = 60;
    private static final int IOS26_BLUR = 2;

    private static boolean ios26(int[] s) {
        return s.length > IOS26 && s[IOS26] != 0;
    }

    /**
     * The bevel's blur radius in the mask's pixels. iOS's rim and edge light are the same
     * width whatever the font's weight (about 3 px and 15 px on 240 px digits): a part of the
     * digit's height, not of its stroke, or a bold font got a rim twice as wide.
     */
    private static int bevelRadius(int[] s, float stroke, int height) {
        if (ios26(s)) return Math.max(2, Math.round(height * 0.022f));
        return Math.max(2, Math.round(stroke * s[BEVEL] / 100f));
    }

    /**
     * Thick milky glass in the shape of a glyph, like iOS 26 clocks. {@code mask} is the digit as
     * drawn, {@code soft} the same blurred by the bevel width: 1 inside, about 0.5 at the edge,
     * so it gives both how close a pixel is to the edge and, from its slope, which way the edge
     * faces. The picture is refracted inwards along the bevel, frosted white (more so on the
     * bevel, unless the frost is off), lit from the top left, shaded opposite, and ringed by a thin rim.
     */
    private static final String GLYPH_SHADER = ""
            + "uniform shader content;\n"
            + "uniform shader mask;\n"
            + "uniform shader soft;\n"
            + "uniform float refraction;\n"
            + "uniform float2 light;\n"
            + "uniform float frost;\n"
            + "uniform float highlight;\n"
            + "uniform float ios27;\n"
            + "uniform float layer27;\n"
            + "uniform float rim27;\n"
            + "uniform float hairline;\n"
            + "uniform float ios26;\n"
            + "uniform float ring;\n"
            + "uniform float spike;\n"
            + "layout(color) uniform half4 tint;\n"
            // How much of the glyph lies within 2 px: the thin dark ring of iOS 26's clock.
            + "float near(float2 p) {\n"
            + "    float n = max(max(mask.eval(p + float2(2.0, 0.0)).a, mask.eval(p - float2(2.0, 0.0)).a),\n"
            + "        max(mask.eval(p + float2(0.0, 2.0)).a, mask.eval(p - float2(0.0, 2.0)).a));\n"
            + "    n = max(n, max(max(mask.eval(p + float2(1.4, 1.4)).a, mask.eval(p - float2(1.4, 1.4)).a),\n"
            + "        max(mask.eval(p + float2(1.4, -1.4)).a, mask.eval(p - float2(1.4, -1.4)).a)));\n"
            + "    return n;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "    half a = mask.eval(p).a;\n"
            + "    if (a <= 0.0) {\n"
            + "        if (ios26 > 0.5) return half4(0.0, 0.0, 0.0, half(near(p) * ring));\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "    float s = soft.eval(p).a;\n"
            + "    float gx = soft.eval(p + float2(1.0, 0.0)).a - soft.eval(p - float2(1.0, 0.0)).a;\n"
            + "    float gy = soft.eval(p + float2(0.0, 1.0)).a - soft.eval(p - float2(0.0, 1.0)).a;\n"
            + "    float2 g = float2(gx, gy);\n"
            + "    float edge = clamp((1.0 - s) * 2.0, 0.0, 1.0);\n"
            + "    float e2 = edge * edge;\n"
            + "    float2 inward = length(g) > 0.0001 ? normalize(g) : float2(0.0);\n"
            + "    half4 c = content.eval(p + inward * pow(edge, 1.5) * refraction);\n"
            // iOS 26's lock clock, from the iPhone's pixels: the backdrop brightened (x1.19, its
            // hue kept: 220,210,201 over 185,176,167), more so towards the edge (x1.31 at the
            // sides, less at the top and bottom), a near-white rim of ~3 px on the edges that
            // face left and right only (the top edge barely lit, the bottom not at all), and
            // the dark ring outside the glyph.
            + "    if (ios26 > 0.5) {\n"
            + "        float nx = abs(inward.x);\n"
            + "        float k26 = 1.19 + 0.13 * edge * (0.35 + 0.65 * nx);\n"
            + "        half3 m26 = min(c.rgb * half(k26), half3(c.a));\n"
            + "        m26 = mix(m26, half3(c.a), half(smoothstep(0.55, 0.78, edge) * spike"
            + " * pow(nx, 1.5)));\n"
            + "        m26 = mix(m26, tint.rgb * c.a, tint.a);\n"
            + "        half4 glass = half4(m26, c.a) * a;\n"
            + "        return glass + half4(0.0, 0.0, 0.0, half((1.0 - a) * near(p) * ring));\n"
            + "    }\n"
            // iOS 27 on top: the same backdrop unrefracted, brighter (its brightness()), laid
            // over the liquid glass, which still shows through along the edges it bends.
            + "    if (ios27 > 0.5) {\n"
            + "        half4 f27 = content.eval(p);\n"
            + "        f27.rgb = min(f27.rgb * 1.2, half3(f27.a));\n"
            + "        c = mix(c, f27, half(layer27 * (1.0 - 0.5 * e2)));\n"
            + "    }\n"
            + "    float facing = dot(-inward, light);\n"
            + "    float spec = pow(max(facing, 0.0), 2.0) * 0.6 * e2 * highlight;\n"
            + "    float shade = pow(max(-facing, 0.0), 2.0) * 0.22 * e2 * highlight;\n"
            + "    float rimLine = pow(clamp((edge - 0.8) / 0.2, 0.0, 1.0), 2.0) * 0.5 * highlight;\n"
            // ionic-theme-ios27's rim: lit where the edge faces up or down, clear at the sides,
            // and a dark hairline at the very outline.
            + "    float band = pow(clamp((edge - 0.72) / 0.28, 0.0, 1.0), 2.0);\n"
            + "    float lit = ios27 > 0.5 ? band * pow(abs(inward.y), 1.5) * rim27 : 0.0;\n"
            + "    float dark = ios27 > 0.5 ? clamp((edge - 0.93) / 0.07, 0.0, 1.0) * hairline : 0.0;\n"
            + "    float f = clamp(frost + min(frost * 2.5, 1.0) * 0.35 * e2, 0.0, 1.0);\n"
            + "    half3 rgb = mix(c.rgb, half3(c.a), half(f));\n"
            + "    rgb = mix(rgb, tint.rgb * c.a, tint.a);\n"
            + "    rgb += half(spec + rimLine - shade + lit) * c.a;\n"
            + "    rgb *= half(1.0 - dark);\n"
            + "    rgb = clamp(rgb, half3(0.0), half3(c.a));\n"
            + "    return half4(rgb, c.a) * a;\n"
            + "}\n";

    private static final Map<View, Digit> DIGITS = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> IS_DIGIT = new WeakHashMap<>();
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Matrix PICTURE_TO_SCREEN = new Matrix();
    private static volatile Bitmap picture;

    private static long requestedAt;

    /**
     * Asks the Home screen for the picture again, at most every 5 s: after a reboot the
     * launcher may start after System UI and miss its first request.
     */
    static void requestPicture() {
        long now = android.os.SystemClock.uptimeMillis();
        if (app == null || now - requestedAt < 5000) return;
        requestedAt = now;
        try {
            app.sendBroadcast(new Intent(ACTION_REQUEST).setPackage(Settings.LAUNCHER));
        } catch (Throwable t) {
            XLog.e("lock glass request", t);
        }
    }

    /** The picture from the Home screen (for the notification cards' refraction), or null. */
    static Bitmap picture() {
        return picture;
    }

    /** The picture's place on the screen applied to {@code c} (every card draw: no copy). */
    static void concatPictureToScreen(android.graphics.Canvas c) {
        synchronized (PICTURE_TO_SCREEN) {
            c.concat(PICTURE_TO_SCREEN);
        }
    }

    static volatile int[] settings = {1, 163, 1, 95, 10, 20, 14, 5, 1, 49, 93, 58, 1, 24, 15, 6, 50, 0, 0, 60, 0, 1, 0, 1, 8, 30, 0, 3, 1, 1, 1, 0};
    private static Context app;
    /** Bumped when the picture or the settings change, so digits rebuild their glass. */
    private static int generation;
    private static boolean drawnReported;
    static volatile String state = "waiting for the picture from the Home screen";

    private LockGlass() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate",
                SafeHook.after("callApplicationOnCreate", p -> {
                    if (p.args.length > 0 && p.args[0] instanceof Application && app == null) {
                        app = (Application) p.args[0];
                        listen();
                    }
                }));
        // The digits' own onDraw: hooking View#draw would run for every view System UI draws,
        // all day. The digit class may come from another class loader (a keyguard plugin), and
        // later: it is hooked when the first digit is made, as the class it really is.
        try {
            hookDigitClass(Class.forName(DIGIT, false, lpparam.classLoader));
        } catch (Throwable t) {
            XLog.i("lock glass: " + DIGIT + " not loadable yet, waiting for the first digit");
        }
        XposedBridge.hookAllConstructors(TextView.class, SafeHook.after("digit", p -> {
            if (isDigit(p.thisObject)) hookDigitClass(p.thisObject.getClass());
        }));
    }

    /** Digit classes whose onDraw is hooked. */
    private static final java.util.Set<Class<?>> HOOKED = new java.util.HashSet<>();

    private static RuntimeShader glyphShader;

    /** The glyph shader, compiled once (main thread). */
    private static RuntimeShader glyphShader() {
        if (glyphShader == null) glyphShader = new RuntimeShader(GLYPH_SHADER);
        return glyphShader;
    }

    /** Hooks the onDraw the digit really runs: the nearest class declaring one. */
    private static synchronized void hookDigitClass(Class<?> digit) {
        if (!HOOKED.add(digit)) return;
        Class<?> target = TextView.class;
        for (Class<?> c = digit; c != null && c != TextView.class; c = c.getSuperclass()) {
            if (declaresOnDraw(c)) {
                target = c;
                break;
            }
        }
        if (target != digit && !HOOKED.add(target)) return;
        XposedBridge.hookAllMethods(target, "onDraw", SafeHook.before("onDraw", p -> {
            if (p.args.length == 1 && p.args[0] instanceof Canvas && isDigit(p.thisObject)) {
                drawDigit(p);
            }
        }));
        XLog.i("lock glass draws in " + target.getName() + "#onDraw (loader "
                + digit.getClassLoader() + ")");
    }

    private static boolean declaresOnDraw(Class<?> c) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("onDraw") && m.getParameterTypes().length == 1) return true;
        }
        return false;
    }

    private static boolean isDigit(Object v) {
        Class<?> c = v.getClass();
        Boolean b = IS_DIGIT.get(c);
        if (b == null) {
            b = DIGIT.equals(c.getName());
            IS_DIGIT.put(c, b);
        }
        return b;
    }

    /** Picture and settings from the Home screen; asks for them when System UI starts. */
    private static void listen() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    receive(intent);
                } catch (Throwable t) {
                    XLog.e("lock glass picture", t);
                }
            }
        };
        app.registerReceiver(receiver, new IntentFilter(ACTION_PICTURE),
                Context.RECEIVER_EXPORTED);
        app.sendBroadcast(new Intent(ACTION_REQUEST).setPackage(Settings.LAUNCHER));
        XLog.i("lock glass listening");
        try {
            android.app.WallpaperManager.getInstance(app).addOnColorsChangedListener(
                    (colors, which) -> loadLockPicture(), new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            XLog.e("lock wallpaper listener", t);
        }
        loadLockPicture();
    }

    /**
     * The lock screen's own wallpaper, read here (System UI may read it; the launcher may not):
     * the glass then refracts what is behind it. Only a live lock wallpaper has no picture to
     * read, and the Home screen's picture stands in. Read again when the wallpaper's id changes.
     */
    private static volatile Bitmap lockPicture;
    private static String lockSource = "";

    private static void loadLockPicture() {
        new Thread(() -> {
            Bitmap b = null;
            String source;
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(app);
                int lockId = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK);
                int which = lockId > 0 ? android.app.WallpaperManager.FLAG_LOCK
                        : android.app.WallpaperManager.FLAG_SYSTEM;
                source = which + ":" + wm.getWallpaperId(which);
                if (source.equals(lockSource) && lockPicture != null) return;
                lockSource = source;
                b = readWallpaper(wm, which);
                XLog.i("lock glass: lock wallpaper " + (b == null ? "not readable (live?)"
                        : b.getWidth() + "x" + b.getHeight()) + " from "
                        + (which == android.app.WallpaperManager.FLAG_LOCK ? "the lock screen"
                        : "the Home screen's wallpaper"));
            } catch (Throwable t) {
                XLog.e("lock wallpaper", t);
            }
            Bitmap read = b;
            // The Home screen's picture stands in only when the lock screen's cannot be read:
            // it is decoded then, here, off the main thread.
            Bitmap home = read == null ? homePicture() : null;
            new Handler(Looper.getMainLooper()).post(() -> {
                lockPicture = read;
                if (read == null) {
                    if (home != null) {
                        homePicture = home;
                        usePicture(home, "from the Home screen");
                    }
                    return;
                }
                homePicture = null;
                usePicture(read, "of the lock screen");
            });
        }, "BlurIconsLockWallpaper").start();
    }

    /** The wallpaper's file, decoded at about half the screen's size. */
    private static Bitmap readWallpaper(android.app.WallpaperManager wm, int which)
            throws Exception {
        try (android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(which)) {
            if (pfd == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.FileInputStream in = new java.io.FileInputStream(
                    pfd.getFileDescriptor())) {
                BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                Point size = new Point();
                app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                        .getRealSize(size);
                float cover = Math.max(Math.min(size.x, size.y) / (float) o.outWidth,
                        Math.max(size.x, size.y) / (float) o.outHeight);
                float keep = Math.min(1f, cover * 0.5f);
                int sample = 1;
                while (sample * 2 <= 1f / keep) sample *= 2;
                in.getChannel().position(0);
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                // Drawn only into display lists: a HARDWARE bitmap has the same pixels and
                // keeps no second copy in System UI's memory.
                d.inPreferredConfig = Bitmap.Config.HARDWARE;
                Bitmap b = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, d);
                if (b != null) return b;
                in.getChannel().position(0);
                d.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, d);
            }
        }
    }

    /**
     * The Home screen's picture, kept for when the lock screen's cannot be read: as the JPEG
     * the launcher sent (about 100 KB), decoded only while it is the picture in use.
     */
    private static volatile byte[] homeJpeg;
    private static volatile Bitmap homePicture;

    /** The Home screen's picture, decoded from its JPEG if it is not decoded yet; or null. */
    private static Bitmap homePicture() {
        Bitmap b = homePicture;
        if (b != null) return b;
        byte[] jpeg = homeJpeg;
        return jpeg != null ? decodeJpeg(jpeg) : null;
    }

    /** A JPEG as a HARDWARE bitmap (drawn only into display lists), or as ARGB if it cannot be. */
    private static Bitmap decodeJpeg(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.HARDWARE;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return b != null ? b : BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    /** Gives the clock and the cards this picture to refract. Main thread. */
    private static void usePicture(Bitmap b, String from) {
        mapPicture(b);
        picture = b;
        state = "picture " + b.getWidth() + "x" + b.getHeight() + " " + from;
        generation++;
        drawnReported = false;
        for (View v : DIGITS.keySet()) v.invalidate();
        try {
            LockNotifications.refreshAll();
        } catch (Throwable t) {
            XLog.e("lock notifications after the picture", t);
        }
        LockScreenDiag.report();
    }

    private static void receive(Intent intent) {
        int[] s = intent.getIntArrayExtra(EXTRA_SETTINGS);
        if (s != null && s.length >= 12) {
            // An older launcher side sends only the clock's: the rest keep their values.
            int[] merged = settings.clone();
            System.arraycopy(s, 0, merged, 0, Math.min(s.length, merged.length));
            settings = merged;
        }
        byte[] jpeg = intent.getByteArrayExtra(EXTRA_JPEG);
        if (jpeg != null) {
            homeJpeg = jpeg;
            homePicture = null;
            // The lock screen's own wallpaper wins when it could be read: the Home screen's
            // is then kept as its JPEG only.
            if (lockPicture == null) {
                Bitmap b = decodeJpeg(jpeg);
                if (b != null) {
                    homePicture = b;
                    mapPicture(b);
                    picture = b;
                    state = "picture " + b.getWidth() + "x" + b.getHeight()
                            + " from the Home screen";
                }
            }
        }
        generation++;
        drawnReported = false;
        for (View v : DIGITS.keySet()) v.invalidate();
        // The notification cards drawn before the picture came had no glass to draw from.
        try {
            LockNotifications.refreshAll();
        } catch (Throwable t) {
            XLog.e("lock notifications after the picture", t);
        }
        LockScreenDiag.report();
    }

    /** Centre crop to the screen, like the wallpaper. */
    private static void mapPicture(Bitmap b) {
        Point size = new Point();
        app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                .getRealSize(size);
        int sw = Math.min(size.x, size.y);
        int sh = Math.max(size.x, size.y);
        float s = Math.max(sw / (float) b.getWidth(), sh / (float) b.getHeight());
        synchronized (PICTURE_TO_SCREEN) {
            PICTURE_TO_SCREEN.setScale(s, s);
            PICTURE_TO_SCREEN.postTranslate((sw - b.getWidth() * s) / 2f,
                    (sh - b.getHeight() * s) / 2f);
        }
    }

    private static void drawDigit(MethodHookParam p) {
        View v = (View) p.thisObject;
        Canvas canvas = (Canvas) p.args[0];
        int[] s = settings;
        if (s[ON] == 0 || picture == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        Digit d = DIGITS.get(v);
        if (d == null) {
            d = new Digit();
            DIGITS.put(v, d);
        }
        if (!canvas.isHardwareAccelerated()) {
            softwareDraws++;
            hardwareLayer(v);
            if (d.onGpu) {
                // One UI also draws the digits into a bitmap in software (for gradient clock
                // colours) and lays that over them: with the glass on, that copy stays empty.
                p.setResult(null);
                softwareSkipped++;
            }
            stats();
            return;
        }
        hardwareDraws++;
        try {
            if (d.draw(v, canvas, p)) {
                p.setResult(null);
                stats();
                if (!drawnReported) {
                    drawnReported = true;
                    state = state.replace("; drawing the clock as glass", "")
                            + "; drawing the clock as glass";
                    LockScreenDiag.report();
                }
            }
        } catch (Throwable t) {
            XLog.e("lock glass digit", t);
            state = "digit failed: " + t;
            LockScreenDiag.report();
        }
    }

    private static final Map<View, Boolean> SWITCHED = new WeakHashMap<>();
    private static int softwareDraws;
    private static int softwareSkipped;
    private static int hardwareDraws;
    private static String switched = "";
    private static int reportedAt;

    /**
     * A digit drawn into a software layer (One UI does so for some clock colours) cannot get the
     * GPU glass, and shows the original digit: the nearest view with a software layer, the digit
     * or a parent, gets a hardware layer instead. Once per view.
     */
    private static void hardwareLayer(View v) {
        for (View x = v; x != null; ) {
            if (x.getLayerType() == View.LAYER_TYPE_SOFTWARE) {
                View target = x;
                if (SWITCHED.put(target, Boolean.TRUE) == null) {
                    String name = target.getClass().getSimpleName();
                    if (!switched.contains(name)) switched += " " + name;
                    target.post(() -> target.setLayerType(View.LAYER_TYPE_HARDWARE, null));
                }
                return;
            }
            x = x.getParent() instanceof View ? (View) x.getParent() : null;
        }
    }

    /** Draw counts in the status, now and then: shows which way the clock is drawn. */
    private static void stats() {
        int total = softwareDraws + hardwareDraws;
        if (total < reportedAt) return;
        reportedAt = Math.max(1, total) * 2;
        String base = state.replaceAll(" \\[draws.*", "");
        state = base + " [draws: gpu " + hardwareDraws + ", software " + softwareDraws
                + " (skipped " + softwareSkipped + ")"
                + (switched.isEmpty() ? "" : ", software layer turned off on" + switched) + "]";
        LockScreenDiag.report();
    }

    private static ColorMatrixColorFilter saturation(float s) {
        float inv = 1f - s;
        float r = 0.213f * inv;
        float g = 0.715f * inv;
        float b = 0.072f * inv;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                r + s, g, b, 0f, 0f,
                r, g + s, b, 0f, 0f,
                r, g, b + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
        }));
    }

    /** One digit's glass: its masks, its effect, and its node following it on screen. */
    private static final class Digit implements ViewTreeObserver.OnPreDrawListener {
        private final RenderNode node = new RenderNode("BlurIconsClockDigit");
        private final Matrix toWindow = new Matrix();
        private final Matrix fromWindow = new Matrix();
        private final float[] recorded = new float[9];
        private final float[] current = new float[9];
        private int width;
        private int height;
        /** Weak: the view is the key of DIGITS, and a clock One UI inflates anew must go. */
        private java.lang.ref.WeakReference<View> viewRef;
        private boolean listening;
        /** The observer this digit listens on, to leave it once the view is detached. */
        private ViewTreeObserver observer;
        /** When the masks were last built (uptime). */
        private long builtAt;
        /**
         * The digit's masks: the glyph as drawn and the same blurred by the bevel, at
         * {@code heldW}x{@code heldH} for {@code heldText}. Rasterizing and blurring them is
         * CPU work of a few milliseconds per digit, so it is done when the glyph or its size
         * really changes, not while One UI animates the clock.
         */
        private Bitmap mask;
        private Bitmap soft;
        private BitmapShader maskShader;
        private BitmapShader softShader;
        private int heldW;
        private int heldH;
        private String heldText;
        private int builtColor;
        private int builtGeneration = -1;
        /** The view's size at the last draw, and when it last changed (uptime). */
        private int seenW;
        private int seenH;
        private long sizeChangedAt;
        /** The size the effect was last made for. */
        private int effectW;
        private int effectH;
        /** The masks are stretched to another size (the clock is animating). */
        private boolean stretched;
        /** The size has stopped changing: the next draw builds the masks for it. */
        private boolean settle;
        private final Matrix stretch = new Matrix();
        /** Drawn as glass on the GPU at least once: its software copies can then stay empty. */
        boolean onGpu;
        /**
         * The digit drew nothing into its mask (its text is painted elsewhere, or not yet, as
         * right after a reboot): the glass would be empty, so the clock is left as it is.
         */
        private boolean empty;

        private View view() {
            java.lang.ref.WeakReference<View> r = viewRef;
            return r != null ? r.get() : null;
        }

        boolean draw(View v, Canvas canvas, MethodHookParam p) throws Exception {
            if (view() != v) viewRef = new java.lang.ref.WeakReference<>(v);
            width = v.getWidth();
            height = v.getHeight();
            int[] s = settings;
            String text = v instanceof TextView ? String.valueOf(((TextView) v).getText()) : "";
            int color = v instanceof TextView ? ((TextView) v).getCurrentTextColor() : Color.WHITE;
            if (width != seenW || height != seenH) {
                seenW = width;
                seenH = height;
                sizeChangedAt = android.os.SystemClock.uptimeMillis();
            }
            boolean sized = width != heldW || height != heldH || mask == null
                    || !text.equals(heldText);
            if (sized && (mask == null || settle || !text.equals(heldText))) {
                build(v, p, s, color);
            } else if (sized) {
                // The lock screen going to Always On Display (and back) resizes and recolours
                // the clock on every frame of the transition. The masks are stretched to the new
                // size meanwhile, and built again once the size has settled (onPreDraw, or
                // settleLater when no frame comes). A digit that drew nothing waits the same
                // way, without the bitmap and the scan on every frame.
                if (empty) {
                    if (!stretched) {
                        stretched = true;
                        askSettle(v);
                    }
                } else if (width != effectW || height != effectH || color != builtColor
                        || generation != builtGeneration) {
                    stretchMasks(s, color);
                }
            } else if (generation != builtGeneration) {
                build(v, p, s, color);
            } else if (!empty && (width != effectW || height != effectH)) {
                // Back at the masks' own size (the transition undone): unstretched.
                maskShader.setLocalMatrix(null);
                softShader.setLocalMatrix(null);
                stretched = false;
                effect(s, color);
            } else if (color != builtColor) {
                if (!empty) {
                    // Only the colour: the same masks, the tint set again (no CPU work).
                    effect(s, color);
                } else {
                    // A digit first drawn in a colour too faint to shape a mask (One UI starts
                    // the clock transparent): tried again in the new colour, twice a second at
                    // most while the colour keeps changing. One UI may draw nothing more after
                    // the colour has settled, so the retry is asked for when it is too soon.
                    long left = 500 - (android.os.SystemClock.uptimeMillis() - builtAt);
                    if (left <= 0) {
                        build(v, p, s, color);
                    } else if (!retryPosted) {
                        retryPosted = true;
                        v.postDelayed(this::retryLater, left);
                    }
                }
            }
            settle = false;
            if (empty) {
                // Its software copies are the clock now: they must be drawn again.
                if (onGpu) {
                    onGpu = false;
                    state = state.replace("; drawing the clock as glass", "")
                            + "; a digit drew nothing to shape the glass with, clock left as is";
                    LockScreenDiag.report();
                }
                return false;
            }
            if (!record()) return false;
            onGpu = true;
            if (canvas instanceof RecordingCanvas) {
                ((RecordingCanvas) canvas).drawRenderNode(node);
            } else {
                // A hardware canvas of another class: same method.
                canvas.getClass().getMethod("drawRenderNode", RenderNode.class)
                        .invoke(canvas, node);
            }
            if (!listening) {
                listening = true;
                observer = v.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
            return true;
        }

        /** The digit's masks (it is drawn as usual into a bitmap) and its glass effect. */
        private void build(View v, MethodHookParam p, int[] s, int color) throws Exception {
            // Noted first: a digit that draws nothing is not built again until something
            // changes (its masks are empty, there is no effect to make).
            builtColor = color;
            builtGeneration = generation;
            builtAt = android.os.SystemClock.uptimeMillis();
            heldW = width;
            heldH = height;
            heldText = v instanceof TextView ? String.valueOf(((TextView) v).getText()) : "";
            stretched = false;
            try {
                Bitmap drawn = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                XposedBridge.invokeOriginalMethod(p.method, v, new Object[]{new Canvas(drawn)});
                // The digit as drawn is the mask: its alpha is the glyph, and the glass reads
                // only that, so the mask keeps one byte a pixel instead of four.
                mask = drawn.extractAlpha();
                drawn.recycle();
                // The bevel follows the font: a share of the stroke width (twice the area over
                // the outline length), so thin and bold clock fonts both get a proper rim.
                float stroke = strokeWidth(mask);
                empty = stroke < 0f;
                if (empty) {
                    soft = null;
                    maskShader = null;
                    softShader = null;
                    return;
                }
                soft = blurAlpha(mask, bevelRadius(s, stroke, height));
                maskShader = new BitmapShader(mask, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                softShader = new BitmapShader(soft, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                effect(s, color);
            } catch (Throwable t) {
                // Nothing half built: the next draw starts over.
                heldW = 0;
                heldH = 0;
                mask = null;
                soft = null;
                maskShader = null;
                softShader = null;
                empty = true;
                throw t;
            }
        }

        /** The masks of {@code heldW}x{@code heldH} stretched to the view's size for now. */
        private void stretchMasks(int[] s, int color) {
            stretch.setScale(width / (float) heldW, height / (float) heldH);
            maskShader.setLocalMatrix(stretch);
            softShader.setLocalMatrix(stretch);
            stretched = true;
            effect(s, color);
            askSettle(view());
        }

        private boolean settlePosted;
        private boolean retryPosted;

        /** Draws an empty digit again, so a colour it gained meanwhile shapes its glass. */
        private void retryLater() {
            retryPosted = false;
            View v = view();
            if (v != null && v.isAttachedToWindow() && empty) v.invalidate();
        }

        /**
         * The lock screen may draw no frame at all once the clock has settled (Always On
         * Display stays still until the minute changes): the settle is asked for.
         */
        private void askSettle(View v) {
            if (!settlePosted && v != null) {
                settlePosted = true;
                v.postDelayed(this::settleLater, 160);
            }
        }

        /** Builds the masks for the settled size, or waits more while the size still moves. */
        private void settleLater() {
            settlePosted = false;
            View v = view();
            if (!stretched || v == null || !v.isAttachedToWindow()) return;
            if (android.os.SystemClock.uptimeMillis() - sizeChangedAt >= 150) {
                stretched = false;
                settle = true;
                v.invalidate();
            } else {
                settlePosted = true;
                v.postDelayed(this::settleLater, 160);
            }
        }

        /**
         * The glass effect for the masks as they are, in {@code color}. The glyph shader is
         * compiled once for the process: a RenderEffect takes a copy of its inputs and
         * uniforms when it is made, so the one shader serves every digit in turn.
         */
        private void effect(int[] s, int color) {
            builtColor = color;
            builtGeneration = generation;
            effectW = width;
            effectH = height;
            RuntimeShader glyph = glyphShader();
            glyph.setInputShader("mask", maskShader);
            glyph.setInputShader("soft", softShader);
            boolean exact = ios26(s);
            glyph.setFloatUniform("refraction", height * 0.12f
                    * (exact ? IOS26_REFRACTION : s[REFRACTION]) / 100f);
            glyph.setFloatUniform("frost", (exact ? IOS26_FROST : s[FROST]) / 100f);
            glyph.setFloatUniform("highlight", s[HIGHLIGHT] / 100f);
            glyph.setFloatUniform("light", -0.7071f, -0.7071f);
            glyph.setFloatUniform("ios27", s[IOS27]);
            glyph.setFloatUniform("layer27", s[IOS27_LAYER] / 100f);
            glyph.setFloatUniform("rim27", s[RIM] / 100f);
            glyph.setFloatUniform("hairline", s[HAIRLINE] / 100f);
            glyph.setFloatUniform("ios26", exact ? 1f : 0f);
            // In the iOS 26 look: the rim's whiteness and the dark ring's, as measured.
            glyph.setFloatUniform("spike", (exact ? IOS26_RIM : s[RIM]) / 100f);
            glyph.setFloatUniform("ring", (exact ? IOS26_RING : s[HAIRLINE]) / 100f);
            glyph.setColorUniform("tint", Color.argb((exact ? 0 : s[TINT]) / 100f,
                    Color.red(color) / 255f,
                    Color.green(color) / 255f, Color.blue(color) / 255f));

            RenderEffect vibrancy = RenderEffect.createColorFilterEffect(
                    saturation((exact ? 100 : s[SATURATION]) / 100f));
            float blur = Math.min(width, height) * (exact ? IOS26_BLUR : s[BLUR]) / 100f;
            RenderEffect frosted = blur > 0f ? RenderEffect.createBlurEffect(blur, blur,
                    vibrancy, Shader.TileMode.CLAMP) : vibrancy;
            node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(glyph, "content"), frosted));
            node.setPosition(0, 0, width, height);
        }

        /** Records the picture under the digit, for where it is now on screen. */
        private boolean record() {
            Bitmap pic = picture;
            View view = view();
            if (pic == null || view == null) return false;
            toWindow.reset();
            view.transformMatrixToGlobal(toWindow);
            toWindow.getValues(recorded);
            if (!toWindow.invert(fromWindow)) return false;
            RecordingCanvas rc = node.beginRecording(width, height);
            try {
                rc.concat(fromWindow);
                synchronized (PICTURE_TO_SCREEN) {
                    rc.concat(PICTURE_TO_SCREEN);
                }
                rc.drawBitmap(pic, 0f, 0f, BITMAP_PAINT);
            } finally {
                node.endRecording();
            }
            return true;
        }

        /** Follows the digit as the lock screen animates, re-recording only the glass node. */
        @Override
        public boolean onPreDraw() {
            try {
                View v = view();
                if (v == null || !v.isAttachedToWindow()) {
                    // Gone from the window: this digit's glass goes with it.
                    ViewTreeObserver o = observer;
                    observer = null;
                    listening = false;
                    if (o != null) {
                        try {
                            o.removeOnPreDrawListener(this);
                        } catch (IllegalStateException dead) {
                            // The observer is gone too.
                        }
                    }
                    return true;
                }
                if (mask == null) return true;
                if (stretched && !settlePosted
                        && android.os.SystemClock.uptimeMillis() - sizeChangedAt > 150) {
                    // The clock has kept its size for a while: masks for it, in the next draw.
                    stretched = false;
                    settle = true;
                    v.invalidate();
                }
                toWindow.reset();
                v.transformMatrixToGlobal(toWindow);
                toWindow.getValues(current);
                for (int i = 0; i < 9; i++) {
                    float limit = i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y ? 0.5f : 0.002f;
                    if (Math.abs(current[i] - recorded[i]) > limit) {
                        record();
                        break;
                    }
                }
            } catch (Throwable t) {
                XLog.e("lock glass follow", t);
            }
            return true;
        }
    }

    /** Average stroke width of the glyph in {@code mask}: twice its area over its outline. */
    private static float strokeWidth(Bitmap mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int[] px = new int[w * h];
        mask.getPixels(px, 0, w, 0, 0, w, h);
        int area = 0;
        int outline = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((px[i] >>> 24) < 128) continue;
                area++;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || (px[i - 1] >>> 24) < 128 || (px[i + 1] >>> 24) < 128
                        || (px[i - w] >>> 24) < 128 || (px[i + w] >>> 24) < 128) {
                    outline++;
                }
            }
        }
        // A glyph needs a few pixels; fewer is a digit drawn empty (-1).
        if (area < 8) return -1f;
        return outline == 0 ? h * 0.1f : 2f * area / outline;
    }

    /** The alpha of {@code mask}, box-blurred three times (close to a gaussian), as ARGB. */
    private static Bitmap blurAlpha(Bitmap mask, int r) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        // Only its alpha is read by the glass: one byte a pixel.
        Bitmap argb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int[] px = new int[w * h];
        mask.getPixels(px, 0, w, 0, 0, w, h);
        int[] a = new int[w * h];
        for (int i = 0; i < px.length; i++) a[i] = px[i] >>> 24;
        int[] tmp = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            box(a, w, h, r, true, tmp);
            box(a, w, h, r, false, tmp);
        }
        for (int i = 0; i < px.length; i++) px[i] = a[i] << 24;
        argb.setPixels(px, 0, w, 0, 0, w, h);
        return argb;
    }

    private static void box(int[] a, int w, int h, int r, boolean rows, int[] out) {
        int lines = rows ? h : w;
        int len = rows ? w : h;
        int step = rows ? 1 : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = rows ? line * w : line;
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += a[base + Math.max(0, Math.min(len - 1, i)) * step];
            for (int i = 0; i < len; i++) {
                out[i] = sum / div;
                sum += a[base + Math.min(len - 1, i + r + 1) * step]
                        - a[base + Math.max(0, i - r) * step];
            }
            for (int i = 0; i < len; i++) a[base + i * step] = out[i];
        }
    }
}
