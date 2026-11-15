package com.monotonnoe.oneglass.engine;

import android.app.WallpaperManager;
import android.content.res.Configuration;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.ViewParent;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Liquid glass behind the app icons, after Kyant's AndroidLiquidGlass (Apache 2.0): the wallpaper
 * under the icon, made more vibrant, lightly blurred and refracted by a lens at the glass edge.
 *
 * <p>The wallpaper is not part of the launcher's window, so the module keeps its own copy of the
 * wallpaper bitmap and maps it onto the screen the way the system draws it (centre crop, scrolled
 * by the wallpaper offsets). Each icon records the part under it into a {@link RenderNode} that
 * carries the effects; it is re-recorded whenever the icon moves on screen ({@link #moved}).
 * Live wallpapers have no bitmap: the glass then refracts the picture chosen for them in the
 * module's settings ({@link Settings#PICTURE_URI}), or falls back to the compositor blur.
 */
final class LiquidGlass {
    /**
     * Lens refraction over a rounded rect, from AndroidLiquidGlass (RoundedRectRefractionShader).
     * Copyright 2025 Kyant, Apache License 2.0.
     */
    /**
     * The glass in one pass: the lens of AndroidLiquidGlass (RoundedRectRefractionShader,
     * Copyright 2025 Kyant, Apache License 2.0: a circular profile over the band at the edge,
     * the full depth effect), as up to 6.88, then the tint and iOS 27's static light, as
     * measured on an iPhone's screenshot in 6.89: a bright line just inside the top and bottom
     * edges (a brighter copy of the glass's colour), a soft glow under them, and a dark
     * hairline along the sides. One textured shape per glass: what the render thread spends on
     * a frame is mostly per draw, and a Home screen has some forty glasses.
     */
    private static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            // Glass coordinates to the content's own: the wallpaper copy is sampled where the
            // glass is on screen through these, so one shader instance, with only its uniforms
            // changed, serves every glass, and the copy's shader never changes.
            + "uniform float4 toContent;\n"
            + "uniform float2 toContentT;\n"
            // x: the bright line's middle, px inside the edge; y: its half width; z: the dark
            // hairline's middle, px outside the edge; w: its half width.
            + "uniform float4 edge;\n"
            // x: the bright line's share on the sides, y/z: its colour (the glass's own times
            // y, plus z: a brighter copy, not flat white), w: the rim's strength.
            + "uniform float4 light;\n"
            // x: the inner glow under the lit edges, y: how fast it fades (px), z: how dark the
            // hairline makes what lies under it on the sides, w: past this depth (px) only the
            // veiled wallpaper is left.
            + "uniform float4 glow;\n"
            // xy: the lit axis (a unit vector in the glass's coordinates; (0, 1) at rest: the
            // top and bottom edges lit), zw: unused.
            + "uniform float4 dir;\n"
            // x: the whole glass's magnification; y/z: gain and offset of the wallpaper (a
            // darker platter: the dock's).
            + "uniform float3 face;\n"
            + "layout(color) uniform half4 tint;\n"
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y;\n"
            + "        else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x;\n"
            + "        else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            // The outward normal of the light, rounder toward the middle (Apple's ovalization:
            // half the lens's depth effect).
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 sdGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 toMiddle = normalize(centeredCoord + float2(0.0001, 0.0001));\n"
            + "    float2 n = normalize(sdGrad + 0.5 * depthEffect * toMiddle);\n"
            // iOS 27's static light: bright lines along the lit edges, a dark hairline on the
            // others; each fades into the corners.
            + "    float lit = pow(abs(dot(n, dir.xy)), 4.0);\n"
            + "    float side = pow(abs(n.x * dir.y - n.y * dir.x), 4.0);\n"
            + "    float dl = glow.z * side * clamp(1.0 - abs(sd - edge.z) / max(edge.w, 0.001),"
            + " 0.0, 1.0);\n"
            // Past the edge there is only the hairline's outer part.
            + "    if (sd > 0.5) return half4(0.0, 0.0, 0.0, half(dl));\n"
            + "    float t = max(-sd, 0.0);\n"
            // What is behind (as it is with face.x 1, the only magnification used), pulled in
            // from further inside in a band along the edge: AndroidLiquidGlass's lens.
            + "    float2 p = halfSize - offset + centeredCoord / face.x;\n"
            + "    if (t < refractionHeight) {\n"
            + "        float d = circleMap(1.0 - t / refractionHeight) * refractionAmount;\n"
            + "        p += d * normalize(sdGrad + depthEffect * toMiddle);\n"
            + "    }\n"
            + "    half4 c = content.eval(float2(toContent.x * p.x + toContent.y * p.y + toContentT.x,"
            + " toContent.z * p.x + toContent.w * p.y + toContentT.y));\n"
            + "    c.rgb = half3(clamp(float3(c.rgb) * face.y + face.z * float(c.a), 0.0, float(c.a)));\n"
            + "    c.rgb = mix(c.rgb, tint.rgb * c.a, tint.a);\n"
            + "    float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
            // Inside every edge band (most of the glass): nothing below changes the colour.
            + "    if (t > glow.w) return c;\n"
            // A soft glow under the lit edges (a screen blend with white).
            + "    float g = glow.x * lit * exp(-t / max(glow.y, 0.001)) * light.w;\n"
            + "    c.rgb = c.a - (c.a - c.rgb) * half(1.0 - g);\n"
            // The bright line: a brighter copy of the glass's colour, strongest on the lit
            // edges, faint on the others.
            + "    float w = mix(light.x, 1.0, lit) * clamp(1.0 - abs(t - edge.x) / max(edge.y, 0.001),"
            + " 0.0, 1.0) * light.w;\n"
            + "    half3 bright = half3(clamp(float3(c.rgb) * light.y + light.z * float(c.a), 0.0,"
            + " float(c.a)));\n"
            + "    c.rgb = mix(c.rgb, bright, half(w));\n"
            // The hairline's inner part.
            + "    c.rgb *= half(1.0 - dl);\n"
            + "    return mix(half4(0.0, 0.0, 0.0, half(dl)), c, half(inside));\n"
            + "}\n";

    /** The prepared copies' shaders, never given a local matrix (the lens maps into them). */
    /**
     * Held strongly and dropped by hand (a shader holds its bitmap, so weak keys would never
     * go): with the copies they were made for, or all with a new picture.
     */
    private static final Map<Bitmap, BitmapShader> CONTENT_SHADERS = new HashMap<>();
    private static boolean hardwareReported;


    /**
     * Lens shaders by look class: everything but where the glass is (its size, corner, lens,
     * rim, tint and the copy it samples). Icons of a page share one, folders another, each
     * widget size one. A record then sets two uniforms instead of thirteen, each of which
     * dropped the shader's native instance. A new class compiles the shader once.
     */
    private static final class LensKey {
        /** Depth, edge (4), light (4), glow (4), face (3): what never changes between glasses. */
        final float[] v = new float[16];

        @Override
        public boolean equals(Object o) {
            return o instanceof LensKey && java.util.Arrays.equals(((LensKey) o).v, v);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(v);
        }

        LensKey copy() {
            LensKey k = new LensKey();
            System.arraycopy(v, 0, k.v, 0, v.length);
            return k;
        }
    }

    private static final LensKey LOOKUP = new LensKey();
    /** A class's shader, and the geometry last set on it (set again only when it differs). */
    private static final class Lens {
        final RuntimeShader s = new RuntimeShader(LENS_SHADER);
        /** Size (2), corner, lens band and pull, glow band, the light's way (2) and ends (2). */
        final float[] geo = {Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                0f, 1f, 1f, 1f};
        /** The copy and tint last set: a stand-in giving way to its copy sets one input. */
        Bitmap content;
        int tint;
        boolean tintSet;
    }

    /**
     * The lens's depth effect (Kyant's, in full, as up to 6.88); the light's normals take half
     * of it in the shader (Apple's ovalization, 6.89's light).
     */
    static final float DEPTH = 1f;

    private static final Map<LensKey, Lens> LENSES =
            new java.util.LinkedHashMap<LensKey, Lens>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<LensKey, Lens> e) {
                    return size() > 32;
                }
            };
    /** Lens shaders compiled since the launcher started, for the load readout. */
    static int lensCompiles;

    /** The class in {@link #LOOKUP}, its constant uniforms set once. Main thread. */
    private static Lens lensFor(LensKey k) {
        Lens l = LENSES.get(k);
        if (l != null) return l;
        l = new Lens();
        lensCompiles++;
        RuntimeShader s = l.s;
        float[] v = k.v;
        s.setFloatUniform("offset", 0f, 0f);
        s.setFloatUniform("depthEffect", v[0]);
        s.setFloatUniform("edge", v[1], v[2], v[3], v[4]);
        s.setFloatUniform("light", v[5], v[6], v[7], v[8]);
        s.setFloatUniform("glow", v[9], v[10], v[11], v[12]);
        // An unset magnification would divide by 0.
        s.setFloatUniform("face", v[13] > 0f ? v[13] : 1f, v[14], v[15]);
        // An unset uniform is 0, which would take the rim away: the light at rest.
        s.setFloatUniform("dir", 0f, 1f, 1f, 1f);
        LENSES.put(k.copy(), l);
        return l;
    }

    /**
     * What a lens samples once its copy is dropped, so the shader does not keep the copy
     * alive. Never drawn: a lens without its content gets it again before its next record.
     */
    private static BitmapShader noContent;

    private static void dropContent(Lens l) {
        if (noContent == null) {
            noContent = new BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        l.s.setInputShader("content", noContent);
        l.content = null;
    }

    private static BitmapShader contentShader(Bitmap b) {
        BitmapShader sh = CONTENT_SHADERS.get(b);
        if (sh == null) {
            sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            CONTENT_SHADERS.put(b, sh);
        }
        return sh;
    }

    private static final Map<String, RenderEffect> EFFECTS = new HashMap<>();
    /** Pre-blurred, saturated copies of the wallpaper by blur and saturation (main thread). */
    private static final Map<String, Prepared> PREPARED =
            new java.util.LinkedHashMap<String, Prepared>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Prepared> e) {
                    if (size() <= 10) return false;
                    // A copy no glass asked for in a while: its shaders go with it.
                    Prepared gone = e.getValue();
                    if (gone != null) {
                        CONTENT_SHADERS.remove(gone.bitmap);
                        // A lens still pointing at it takes its next copy on its next record.
                        for (Lens l : LENSES.values()) {
                            if (l.content == gone.bitmap) dropContent(l);
                        }
                    }
                    return true;
                }
            };
    /** Bumped when the picture changes: copies made from an older one are dropped. */
    private static int pictureGeneration;
    /** Views drawn over a stand-in, and the copy each waits for. */
    private static final Map<View, String> WAITING = new java.util.WeakHashMap<>();

    /** Blur radii in steps of this many screen px share a copy. */
    static final float BLUR_STEP = 4f;

    private static String keyOf(float radius, float saturation) {
        // Radii in steps of 4 px share a copy: icons, folders and search bars all fit a few.
        int bucket = Math.max(0, Math.round(radius / BLUR_STEP));
        return bucket + "s" + Math.round(saturation * 100f);
    }

    /** A copy is ready: the views drawn over a stand-in for it are drawn again. Main thread. */
    private static void invalidateWaiting(String key) {
        java.util.Iterator<Map.Entry<View, String>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<View, String> e = it.next();
            if (!key.equals(e.getValue())) continue;
            View v = e.getKey();
            it.remove();
            if (v != null) v.invalidate();
        }
    }
    private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor();
    /** Bumped when the wallpaper or the tuning changes, so late copies are dropped. */
    private static int generation;
    private static final ExecutorService REPORTS = Executors.newSingleThreadExecutor();
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** The home wallpaper, downscaled, and its mapping onto the screen. */
    private static volatile Bitmap wallpaper;
    private static Bitmap toneOf;
    private static int tone;

    /**
     * The wallpaper's overall colour, made a little more saturated and mid-bright, as iOS
     * tints with the wallpaper's hue; a blue-grey until the wallpaper is read.
     */
    static synchronized int wallpaperTone() {
        Bitmap w = wallpaper;
        if (w == null) return 0xFF8E9BB0;
        if (w == toneOf) return tone;
        try {
            Bitmap b = w.getConfig() == Bitmap.Config.HARDWARE
                    ? w.copy(Bitmap.Config.ARGB_8888, false) : w;
            Bitmap small = Bitmap.createScaledBitmap(b, 8, 8, true);
            long r = 0;
            long g = 0;
            long bl = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = small.getPixel(x, y);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    bl += c & 0xFF;
                }
            }
            float[] hsv = new float[3];
            android.graphics.Color.RGBToHSV((int) (r / 64), (int) (g / 64), (int) (bl / 64), hsv);
            hsv[1] = Math.max(hsv[1], 0.45f);
            hsv[2] = 0.72f;
            tone = android.graphics.Color.HSVToColor(hsv);
        } catch (Throwable t) {
            tone = 0xFF8E9BB0;
        }
        toneOf = w;
        return tone;
    }
    /** Screen pixels per wallpaper bitmap pixel. */
    private static float wallpaperScale;
    private static final Matrix WALLPAPER_TO_SCREEN = new Matrix();
    private static int screenWidth;
    private static int screenHeight;
    private static float xOffset = 0.5f;
    private static float yOffset = 0.5f;
    private static boolean installed;
    private static Context appContext;
    private static Handler mainHandler;
    /** The last load result, and whether the first drawing since then was reported. */
    private static String loadStatus = "";
    private static boolean drawReported;

    private LiquidGlass() {
    }

    static boolean available() {
        return wallpaper != null;
    }

    /** The picture the glass refracts (downscaled), for the lock screen clock. */
    static Bitmap picture() {
        return wallpaper;
    }

    /** Loads the wallpaper and follows its changes. Launcher side, once the Application exists. */
    /** How long the glass takes to come in once it is drawn again after frames without it. */
    static final long RETURN_FADE_MS = 320;

    /** When the launcher's activity last came back in front (uptime), for the log. */
    static volatile long resumedAt;

    /** Notes when the launcher's activity comes back in front (an app closed). */
    private static void watchHome(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity a) {
                resumedAt = android.os.SystemClock.uptimeMillis();
                if (GlassLight.isHome(a)) NavHandle.send(a.getApplicationContext(), true);
                try {
                    if (GlassLight.isHome(a)) GalleryLook.onHomeResumed();
                } catch (Throwable t) {
                    XLog.e("gallery look resume", t);
                }
                try {
                    GlassLight.onResumed(a);
                } catch (Throwable t) {
                    XLog.e("rim light resume", t);
                }
            }

            @Override
            public void onActivityPaused(android.app.Activity a) {
                if (GlassLight.isHome(a)) NavHandle.send(a.getApplicationContext(), false);
                try {
                    if (GlassLight.isHome(a)) GalleryLook.onHomePaused();
                } catch (Throwable t) {
                    XLog.e("gallery look pause", t);
                }
                try {
                    GlassLight.onPaused(a);
                } catch (Throwable t) {
                    XLog.e("rim light pause", t);
                }
            }

            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityStarted(android.app.Activity a) {
            }

            @Override
            public void onActivityStopped(android.app.Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity a) {
                try {
                    GlassLight.onDestroyed(a);
                } catch (Throwable t) {
                    XLog.e("rim light destroyed", t);
                }
            }
        });
    }

    static void install(Context app) {
        if (installed) return;
        installed = true;
        Handler main = new Handler(Looper.getMainLooper());
        appContext = app;
        mainHandler = main;
        if (app instanceof android.app.Application) {
            try {
                watchHome((android.app.Application) app);
            } catch (Throwable t) {
                XLog.e("home watcher", t);
            }
        }
        try {
            watchTheme(app);
        } catch (Throwable t) {
            XLog.e("theme watcher", t);
        }
        try {
            GlassLight.install(app);
        } catch (Throwable t) {
            XLog.e("rim light", t);
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(app);
            wm.addOnColorsChangedListener((colors, which) -> {
                if ((which & WallpaperManager.FLAG_SYSTEM) != 0) load(app, main, false);
            }, main);
        } catch (Throwable t) {
            XLog.e("wallpaper listener", t);
        }
        load(app, main, true);
    }

    /**
     * Light and dark glass differ (tone, rim): One UI Home handles a theme switch in place,
     * without making its views again, and the glass drawn by views it does not redraw (folder
     * and widget glass in the cell layouts, the search bars, folder panels) would keep the
     * old tone. The draw reads the theme, so a redraw is all it takes.
     */
    private static void watchTheme(Context app) {
        final int[] night = {app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK};
        app.registerComponentCallbacks(new android.content.ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(android.content.res.Configuration c) {
                int n = c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (n == night[0]) return;
                night[0] = n;
                XLog.i("theme switched: glass drawn again");
                IconBlur.redrawAll();
                CellGlass.invalidateAll();
                SearchGlass.invalidateAll();
                FolderGlass.invalidateAll();
            }

            @Override
            public void onLowMemory() {
            }
        });
    }

    /** The picture for live wallpapers changed in the module's settings. */
    static void reload() {
        if (appContext != null) load(appContext, mainHandler, true);
    }

    /** What the last load gave (its picture's fingerprint and status), to skip a repeat. */
    private static volatile long loadedFingerprint;
    private static volatile String loadedStatus = "";

    /** A cheap fingerprint of the picture: its size and a grid of its pixels. */
    private static long fingerprint(Bitmap b) {
        if (b == null) return 0L;
        int w = b.getWidth();
        int h = b.getHeight();
        long f = 31L * w + h;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                f = f * 1000003L + b.getPixel(x * (w - 1) / 15, y * (h - 1) / 15);
            }
        }
        return f == 0L ? 1L : f;
    }

    /**
     * Bumped when the wallpaper really moves on screen. Each glass remembers the value it was
     * recorded with and records again, in the pre-draw pass, only if it is on screen.
     */
    static volatile int mapGeneration;
    /** The smaller side of a Home screen icon's glass, once one was drawn (for the copy). */
    static float iconMin;
    /** A Home screen icon's glass corner, once one was drawn (a folder's glass grows from it). */
    static float iconCorner;
    /** Glasses that took a new rim light, outside a move (for the readout). */
    static int relights;
    /** In the pass: glasses that took the light's step, and those that will after a zoom. */
    static int lightTakenPass;
    static int deferredPass;
    private static final float[] DIRV = new float[2];
    private static final float[] MAP_NOW = new float[9];
    private static final float[] MAP_LAST = new float[9];

    /** The launcher scrolls the wallpaper with these (WallpaperManager#setWallpaperOffsets). */
    static void onWallpaperOffsets(float x, float y) {
        if (x == xOffset && y == yOffset) return;
        xOffset = Float.isNaN(x) ? 0.5f : x;
        yOffset = Float.isNaN(y) ? 0.5f : y;
        Bitmap w = wallpaper;
        if (w == null) return;
        synchronized (WALLPAPER_TO_SCREEN) {
            mapWallpaper(w);
            WALLPAPER_TO_SCREEN.getValues(MAP_NOW);
        }
        // A wallpaper as wide as the screen (most photos) does not move as the pages scroll:
        // the offsets change, the mapping does not, and no glass needs recording again.
        boolean same = true;
        for (int i = 0; i < 9 && same; i++) same = Math.abs(MAP_NOW[i] - MAP_LAST[i]) < 0.01f;
        if (same) return;
        System.arraycopy(MAP_NOW, 0, MAP_LAST, 0, 9);
        mapGeneration++;
        IconBlur.onWallpaperMoved();
    }

    /** One load at a time, in order: an older, slower load never publishes over a newer one. */
    private static final ExecutorService LOADS = Executors.newSingleThreadExecutor();
    private static final long SOURCE_LIVE = Long.MIN_VALUE;
    private static final long SOURCE_UNKNOWN = Long.MAX_VALUE;
    /** What the last load read: a static wallpaper's id, or SOURCE_LIVE. Loader thread only. */
    private static long loadedSource = SOURCE_UNKNOWN;

    /**
     * Reads the wallpaper (or the picture chosen for a live one) and gives every glass a copy
     * of it. The wallpaper's colours are reported changed far more often than the picture
     * changes (a live wallpaper reports them on every lock and unlock, and as the launcher
     * comes back from an app). What can change is checked first, before any decode: a live
     * wallpaper's picture changes only through the module (a forced reload), a static
     * wallpaper only with its id. A load that still finds the same picture as the last one
     * changes nothing, or every glass would be drawn over a stand-in for a moment and then
     * switch to its new copy, as a glass appearing out of nowhere. {@code force} loads
     * whatever the picture.
     */
    private static void load(Context app, Handler main, boolean force) {
        LOADS.execute(() -> {
            Bitmap scaled = null;
            String status;
            boolean missing = false;
            try {
                WallpaperManager wm = WallpaperManager.getInstance(app);
                StringBuilder why = new StringBuilder();
                boolean live = wm.getWallpaperInfo() != null;
                long source = SOURCE_UNKNOWN;
                try {
                    source = live ? SOURCE_LIVE : wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                } catch (Throwable ignored) {
                }
                if (!force && source != SOURCE_UNKNOWN && source == loadedSource
                        && wallpaper != null) {
                    return;
                }
                loadedSource = source;
                Bitmap full = live ? null : wallpaperBitmap(wm, why);
                if (full != null) {
                    status = "on: refracting the wallpaper";
                    scaled = downscale(app, full);
                    full = null;
                    // getDrawable()/getBitmap() keep their full-size decode in
                    // WallpaperManager's process-wide cache for as long as this wallpaper
                    // is set; the glass keeps only the scaled copy.
                    try {
                        wm.forgetLoadedWallpaper();
                    } catch (Throwable ignored) {
                    }
                } else {
                    String cause = live ? "the wallpaper is live"
                            : "the wallpaper cannot be read (" + why + ")";
                    PictureRead read = picture(app);
                    missing = read.providerMissing;
                    full = read.bitmap;
                    status = full != null ? "on: refracting your picture, as " + cause
                            : missing ? "off, plain blur: " + cause
                                    + ", waiting for the module (the user is not unlocked yet)"
                            : "off, plain blur: " + cause + " and no picture is chosen";
                    if (full != null) {
                        scaled = downscale(app, full);
                        // Decoded here only: its pixels are in the scaled copy now.
                        if (scaled != full) full.recycle();
                    }
                }
            } catch (Throwable t) {
                XLog.e("wallpaper unavailable, liquid glass falls back to the blur", t);
                status = "off, plain blur: " + t;
            }
            long fp = fingerprint(scaled);
            if (!force && fp == loadedFingerprint && status.equals(loadedStatus)) {
                XLog.i("liquid glass: the same picture as before, nothing to do");
                return;
            }
            loadedFingerprint = fp;
            loadedStatus = status;
            XLog.i("liquid glass " + status);
            String loaded = status;
            main.post(() -> {
                loadStatus = loaded;
                drawReported = false;
            });
            // Nothing to tell while the module's provider cannot be reached: the report
            // would only fail. The load after the unlock reports.
            if (!missing) report(app, status);
            // The icons' copy made here, before the picture is handed to the glass, so the
            // first frame is drawn over it, not over the raw picture for a few hundred ms.
            Prepared first = null;
            String firstKey = null;
            if (scaled != null) {
                try {
                    float sc = Math.max(screenWidth / (float) scaled.getWidth(),
                            screenHeight / (float) scaled.getHeight());
                    float min = iconMin > 0f ? iconMin
                            : 60f * app.getResources().getDisplayMetrics().density;
                    float radius = Config.glassBlur * min;
                    float saturation = Config.glassSaturation;
                    firstKey = keyOf(radius, saturation);
                    int bucket = Math.max(0, Math.round(radius / BLUR_STEP));
                    first = prepare(scaled, bucket * BLUR_STEP / sc,
                            Math.round(saturation * 100f) / 100f);
                } catch (Throwable t) {
                    XLog.e("glass copy", t);
                    first = null;
                }
            }
            Bitmap result = scaled;
            Prepared firstCopy = first;
            String firstCopyKey = firstKey;
            main.post(() -> {
                wallpaper = result;
                clearEffects();
                LockLink.send(true);
                if (result != null) {
                    if (firstCopy != null) PREPARED.put(firstCopyKey, firstCopy);
                    synchronized (WALLPAPER_TO_SCREEN) {
                        mapWallpaper(result);
                        WALLPAPER_TO_SCREEN.getValues(MAP_LAST);
                    }
                    mapGeneration++;
                    // The icons' copy: ready now (made above), or made now if the settings
                    // changed meanwhile (an icon is about 60 dp until one has been measured).
                    float min = iconMin > 0f ? iconMin
                            : 60f * app.getResources().getDisplayMetrics().density;
                    prepared(Config.glassBlur * min, Config.glassSaturation);
                    XLog.i("wallpaper " + result.getWidth() + "x" + result.getHeight()
                            + " for liquid glass");
                }
                IconBlur.onSettingsChanged();
                SearchGlass.invalidateAll();
                // Widgets and folder icons draw their glass in the cell layouts: drawn again
                // too, or they keep the old wallpaper's copy.
                CellGlass.invalidateAll();
                FolderGlass.invalidateAll();
                GlassLight.reconsider("picture");
            });
        });
    }

    /** Loads again unless the last load gave this same picture (after the user unlock). */
    static void retry() {
        if (appContext != null) load(appContext, mainHandler, false);
    }

    /** What reading the chosen picture gave, and whether the module could not be reached. */
    private static final class PictureRead {
        final Bitmap bitmap;
        final boolean providerMissing;

        PictureRead(Bitmap bitmap, boolean providerMissing) {
            this.bitmap = bitmap;
            this.providerMissing = providerMissing;
        }
    }

    /**
     * The picture chosen in the module's settings for live wallpapers, if any. Decoded at
     * about the size the glass keeps (half the screen), not at its full size: the stored
     * picture is up to 2400 px.
     */
    private static PictureRead picture(Context app) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = app.getContentResolver().openInputStream(Settings.PICTURE_URI)) {
                if (in == null) return new PictureRead(null, false);
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            if (o.outWidth > 0 && o.outHeight > 0) {
                screenSize(app);
                float cover = Math.max(screenWidth / (float) o.outWidth,
                        screenHeight / (float) o.outHeight);
                float keep = Math.min(1f, cover * 0.5f);
                while (sample * 2 <= 1f / keep) sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = app.getContentResolver().openInputStream(Settings.PICTURE_URI)) {
                return new PictureRead(in == null ? null : BitmapFactory.decodeStream(in, null, d),
                        false);
            }
        } catch (FileNotFoundException e) {
            // "No content provider": the module's provider is not there before the user
            // unlock; any other message: no picture is chosen.
            String m = String.valueOf(e.getMessage());
            return new PictureRead(null, m.startsWith("No content provider"));
        } catch (Throwable t) {
            XLog.e("picture for live wallpapers", t);
            return new PictureRead(null, false);
        }
    }

    /** The first icon drawn with the glass since the wallpaper was loaded. Main thread. */
    static void onDrawn(Context ctx) {
        if (drawReported) return;
        drawReported = true;
        report(ctx, loadStatus + "; icons drawn with the glass");
    }

    /** The first icon that could not be drawn with the glass, and why. Main thread. */
    static void onDrawFailed(Context ctx, String reason) {
        if (drawReported) return;
        drawReported = true;
        XLog.i("liquid glass not drawn: " + reason);
        report(ctx, loadStatus + "; BUT icons are not drawn with it: " + reason);
    }

    /** Tells the module's settings screen what the glass is doing (and why). */
    static void report(Context app, String status) {
        // One thread, in order: a later report must never be overtaken by an earlier one.
        REPORTS.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(Settings.KEY_STATUS, "[" + Config.VERSION + "] " + status);
                app.getContentResolver().update(Settings.URI, v, null, null);
            } catch (Throwable t) {
                XLog.e("status report", t);
            }
        });
    }

    /** The home wallpaper bitmap, by every way the launcher may be allowed to read it. */
    private static Bitmap wallpaperBitmap(WallpaperManager wm, StringBuilder why) {
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            why.append("getDrawable: ").append(d == null ? "null" : d.getClass().getSimpleName());
        } catch (Throwable t) {
            why.append("getDrawable: ").append(t.getClass().getSimpleName());
        }
        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
            if (pfd != null) {
                Bitmap b = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                if (b != null) return b;
            }
            why.append("; getWallpaperFile: ").append(pfd == null ? "null" : "undecodable");
        } catch (Throwable t) {
            why.append("; getWallpaperFile: ").append(t.getClass().getSimpleName());
        }
        try {
            Method m = WallpaperManager.class.getDeclaredMethod("getBitmap");
            m.setAccessible(true);
            Bitmap b = (Bitmap) m.invoke(wm);
            if (b != null) return b;
            why.append("; getBitmap: null");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            why.append("; getBitmap: ").append(c.getClass().getSimpleName());
        }
        return null;
    }

    /** The screen's size in portrait. */
    private static void screenSize(Context app) {
        Point screen = new Point();
        app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                .getRealSize(screen);
        screenWidth = Math.min(screen.x, screen.y);
        screenHeight = Math.max(screen.x, screen.y);
    }

    /** Half the screen resolution is plenty under the blur and keeps the texture small. */
    private static Bitmap downscale(Context app, Bitmap full) {
        screenSize(app);
        float cover = Math.max(screenWidth / (float) full.getWidth(),
                screenHeight / (float) full.getHeight());
        float scale = Math.min(1f, cover * 0.5f);
        Bitmap src = full.getConfig() == Bitmap.Config.HARDWARE
                ? full.copy(Bitmap.Config.ARGB_8888, false) : full;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(full.getWidth() * scale)),
                Math.max(1, Math.round(full.getHeight() * scale)), true);
    }

    /** Centre crop to the screen, scrolled by the wallpaper offsets, like ImageWallpaper. */
    private static void mapWallpaper(Bitmap w) {
        float s = Math.max(screenWidth / (float) w.getWidth(),
                screenHeight / (float) w.getHeight());
        float dx = (screenWidth - w.getWidth() * s) * xOffset;
        float dy = (screenHeight - w.getHeight() * s) * yOffset;
        WALLPAPER_TO_SCREEN.setScale(s, s);
        wallpaperScale = s;
        WALLPAPER_TO_SCREEN.postTranslate(dx, dy);
        // One UI's zoom of the wallpaper's surface as an app opens or closes (see
        // onWallpaperZoom). The blur radius (wallpaperScale) stays: the copies keep theirs.
        if (zoom != 1f) WALLPAPER_TO_SCREEN.postScale(zoom, zoom, zoomCx, zoomCy);
    }

    /** One UI's wallpaper zoom and its pivot on screen. Guarded by WALLPAPER_TO_SCREEN. */
    private static float zoom = 1f;
    private static float zoomCx;
    private static float zoomCy;
    /**
     * Bumped for each step of a wallpaper zoom. Unlike {@link #mapGeneration} it does not make
     * every glass record on every frame: a glass records it through the zoom's every-other-
     * frame throttle. The end of the zoom bumps mapGeneration, for one exact last record.
     */
    static volatile int zoomGeneration;

    /**
     * One UI scaled the wallpaper's surface by {@code z} about ({@code cx}, {@code cy}) for this
     * frame (WallpaperAnimator#setWallpaperScale), or ended its zoom (1). Any thread.
     */
    static void onWallpaperZoom(float z, float cx, float cy) {
        if (!(z > 1f)) z = 1f;
        Bitmap w = wallpaper;
        boolean ended;
        synchronized (WALLPAPER_TO_SCREEN) {
            if (z == zoom) return;
            // Steps that move no pixel of the screen by half a pixel wait for the next.
            if (z != 1f && zoom != 1f
                    && Math.abs(z - zoom) * Math.max(Math.abs(cx), Math.abs(cy)) < 0.5f) {
                return;
            }
            zoom = z;
            if (z != 1f) {
                zoomCx = cx;
                zoomCy = cy;
            }
            if (w == null) return;
            mapWallpaper(w);
            WALLPAPER_TO_SCREEN.getValues(MAP_LAST);
            ended = z == 1f;
        }
        if (ended) {
            mapGeneration++;
        } else {
            zoomGeneration++;
        }
        IconBlur.onWallpaperMoved();
    }

    /** Drops the cached effects after the glass tuning changed. Main thread. */
    static void clearEffects() {
        EFFECTS.clear();
        for (Lens l : LENSES.values()) dropContent(l);
        LENSES.clear();
        PREPARED.clear();
        WAITING.clear();
        CONTENT_SHADERS.clear();
        generation++;
        pictureGeneration++;
    }

    /**
     * The glass tuning changed. The copies depend only on the picture, the blur and the
     * saturation, which their keys hold, so they stay: a lens, rim or tint change makes none.
     */
    static void clearTuning() {
        EFFECTS.clear();
        generation++;
    }

    /**
     * The wallpaper blurred by {@code radius} screen pixels and saturated, made once in the
     * background (a downscale and a small box blur), so the glass only runs its lens on the GPU
     * every frame. Null until it is ready.
     */
    private static Prepared prepared(float radius, float saturation) {
        Bitmap w = wallpaper;
        if (w == null || wallpaperScale <= 0f) return null;
        int bucket = Math.max(0, Math.round(radius / BLUR_STEP));
        int sat = Math.round(saturation * 100f);
        String key = keyOf(radius, saturation);
        if (PREPARED.containsKey(key)) return PREPARED.get(key);
        PREPARED.put(key, null);
        int gen = pictureGeneration;
        float radiusPx = bucket * BLUR_STEP / wallpaperScale;
        PREPARE.execute(() -> {
            Prepared p;
            try {
                p = prepare(w, radiusPx, sat / 100f);
            } catch (Throwable t) {
                XLog.e("glass copy", t);
                return;
            }
            Prepared ready = p;
            mainHandler.post(() -> {
                if (gen != pictureGeneration || wallpaper != w) return;
                PREPARED.put(key, ready);
                invalidateWaiting(key);
            });
        });
        return null;
    }

    /** Any ready copy, one of the same saturation first: a stand-in while the right one is made. */
    private static Prepared nearestPrepared(float saturation) {
        String sat = "s" + Math.round(saturation * 100f);
        Prepared any = null;
        for (Map.Entry<String, Prepared> e : PREPARED.entrySet()) {
            Prepared p = e.getValue();
            if (p == null) continue;
            if (e.getKey().endsWith(sat)) return p;
            if (any == null) any = p;
        }
        return any;
    }

    /** A wallpaper copy and the scale from it back to the wallpaper bitmap. */
    private static final class Prepared {
        final Bitmap bitmap;
        final float scaleX;
        final float scaleY;

        Prepared(Bitmap bitmap, Bitmap of) {
            this.bitmap = bitmap;
            this.scaleX = of.getWidth() / (float) bitmap.getWidth();
            this.scaleY = of.getHeight() / (float) bitmap.getHeight();
        }
    }

    private static Prepared prepare(Bitmap src, float radius, float saturation) {
        // The sigma RenderEffect#createBlurEffect uses for this radius; three box blurs of
        // radius r come close to a gaussian of sigma r.
        float sigma = radius > 0f ? 0.57735f * radius + 0.5f : 0f;
        // Downscale until the blur is about 2.5 px, which a few box blurs do well and cheaply.
        int k = Math.max(1, Math.round(sigma / 2.5f));
        int w = Math.max(1, src.getWidth() / k);
        int h = Math.max(1, src.getHeight() / k);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(saturation(saturation));
        new Canvas(small).drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), p);
        int r = Math.round(sigma / k);
        if (r > 0) {
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(px, w, h, r, true);
                boxBlur(px, w, h, r, false);
            }
            small.setPixels(px, 0, w, 0, 0, w, h);
        }
        // On the GPU for good: a texture that is never uploaded again, nor dropped and
        // re-uploaded under memory pressure in the middle of an animation.
        Bitmap hw = null;
        try {
            hw = small.copy(Bitmap.Config.HARDWARE, false);
        } catch (Throwable t) {
            if (!hardwareReported) XLog.e("glass copy stays in software", t);
        }
        if (!hardwareReported) {
            hardwareReported = true;
            XLog.i("glass copies as " + (hw != null ? "hardware" : "software") + " bitmaps");
        }
        if (hw != null) {
            Prepared ready = new Prepared(hw, src);
            small.recycle();
            return ready;
        }
        return new Prepared(small, src);
    }

    /** One box blur pass along rows or columns, edges clamped. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w;
        int len = rows ? w : h;
        int step = rows ? 1 : w;
        int[] out = new int[len];
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = rows ? line * w : line;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[base + Math.max(0, Math.min(len - 1, i)) * step];
                sa += c >>> 24;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[i] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = px[base + Math.min(len - 1, i + r + 1) * step];
                int sub = px[base + Math.max(0, i - r) * step];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
            for (int i = 0; i < len; i++) px[base + i * step] = out[i];
        }
    }

    /** The lens alone, for glass that refracts a prepared wallpaper copy. */
    private static RenderEffect lens(int w, int h, float corner, float height, float amount) {
        String key = "lens" + w + "x" + h + "r" + Math.round(corner) + "h"
                + Math.round(height * 1000f) + "a" + Math.round(amount * 1000f);
        RenderEffect e = EFFECTS.get(key);
        if (e == null) {
            e = RenderEffect.createRuntimeShaderEffect(lensShader(w, h, corner, height, amount),
                    "content");
            EFFECTS.put(key, e);
        }
        return e;
    }

    private static RuntimeShader lensShader(int w, int h, float corner, float height,
                                            float amount) {
        float min = Math.min(w, h);
        RuntimeShader lens = new RuntimeShader(LENS_SHADER);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("offset", 0f, 0f);
        lens.setFloatUniform("cornerRadii", new float[]{corner, corner, corner, corner});
        lens.setFloatUniform("refractionHeight", min * height);
        lens.setFloatUniform("refractionAmount", -min * amount);
        lens.setFloatUniform("depthEffect", Config.lensDepth ? DEPTH : 0f);
        // As an effect its content is the glass's own recording: sampled where it is.
        lens.setFloatUniform("toContent", 1f, 0f, 0f, 1f);
        lens.setFloatUniform("toContentT", 0f, 0f);
        lens.setFloatUniform("edge", 0f, 1f, 0f, 1f);
        lens.setFloatUniform("light", 0f, 1f, 0f, 0f);
        lens.setFloatUniform("glow", 0f, 1f, 0f, 0f);
        lens.setFloatUniform("face", 1f, 1f, 0f);
        lens.setFloatUniform("dir", 0f, 1f, 1f, 1f);
        lens.setColorUniform("tint", 0);
        return lens;
    }

    /** Lens, blur and vibrancy for a glass of this size, shared by same-sized icons. */
    private static RenderEffect effect(int w, int h, float corner, float blur, float sat,
                                       float height, float amount) {
        String key = w + "x" + h + "r" + Math.round(corner) + "b" + Math.round(blur * 1000f)
                + "s" + Math.round(sat * 100f) + "h" + Math.round(height * 1000f) + "a"
                + Math.round(amount * 1000f);
        RenderEffect e = EFFECTS.get(key);
        if (e != null) return e;
        float min = Math.min(w, h);
        RuntimeShader lens = lensShader(w, h, corner, height, amount);
        RenderEffect vibrancy = RenderEffect.createColorFilterEffect(saturation(sat));
        RenderEffect blurred = blur > 0f
                ? RenderEffect.createBlurEffect(min * blur, min * blur,
                vibrancy, Shader.TileMode.CLAMP)
                : vibrancy;
        e = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(lens, "content"), blurred);
        EFFECTS.put(key, e);
        return e;
    }

    /** colorControls(saturation) from AndroidLiquidGlass: vibrancy() is saturation 1.5. */
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

    /**
     * Draws a frame with the glass nodes recorded outside a draw: the window's root view is
     * invalidated, whose own display list is small (its children's are kept), and the frame's
     * sync picks the nodes up.
     */
    static void scheduleFrame(View v) {
        View root = v.getRootView();
        (root != null ? root : v).invalidate();
    }

    private static boolean stillFramePending;

    /** A frame shortly after now, so glass frozen during an animation catches up. */
    static void requestStillFrame(View v) {
        if (stillFramePending) return;
        View root = v.getRootView();
        if (root == null) return;
        stillFramePending = true;
        root.postOnAnimationDelayed(() -> {
            stillFramePending = false;
            scheduleFrame(root);
        }, 48);
    }

    private static Class<?> drawCanvasClass;
    private static Method drawMethod;

    /** Bumped by each pre-draw pass: an anchor's matrix is found once per pass. */
    private static int frameStamp;

    static void nextFrame() {
        frameStamp++;
        long t = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        if (t != zoomFrameTime) {
            zoomFrameTime = t;
            zoomFrame++;
            // Last frame's zoom, if any, holds the throttle on for a few frames more: a zoom's
            // ease-out tail changes the scale by less than the test sees, and that is where
            // the launcher's own animation has the most to do.
            if (zoomThisFrame) {
                zoomHold = 6;
            } else if (zoomHold > 0) {
                zoomHold--;
            }
            zoomLastPass = zoomThisFrame;
            zoomThisFrame = false;
        }
    }

    /** Whether the Home screen is taken to be zooming for this pass (see nextFrame). */
    static boolean zooming() {
        return zoomThisFrame || zoomHold > 0;
    }

    /** Set by any glass that saw its scale change in this pass: the Home screen zooms. */
    static boolean zoomThisFrame;
    private static int nextParity;
    /**
     * Counts frames, not passes: a pass from the pager's computeScroll or a second window in
     * the same frame must not flip which glasses record (the frame clock is the same for every
     * pass of one frame).
     */
    private static int zoomFrame;
    private static long zoomFrameTime;
    /** Passes since a glass last saw its scale change: the zoom's slow tail keeps the throttle. */
    private static int zoomHold;
    private static boolean zoomLastPass;
    private static int zoomSyncs;
    private static int zoomRecords;
    private static long zoomNanos;
    private static int recordsAtSyncStart;
    private static long zoomReportedAt;
    /** Glasses looked at and matrices found in the pass, for the zoom line. */
    static int visited;
    static int composes;
    private static int zoomVisited;
    private static int zoomComposes;

    /** Before each pre-draw pass: the counters as they stand. */
    static void syncStarting() {
        recordsAtSyncStart = records;
        visited = 0;
        composes = 0;
        lightTakenPass = 0;
        deferredPass = 0;
    }

    /**
     * After each pre-draw pass, always on: the passes of a zoom are added up, and when the
     * zoom ends its load goes to the log (with {@link Config#DEBUG}) if it was heavy (over
     * 2 ms a frame), at most once in five minutes. Shows in a log what an app opening costs
     * in the glass.
     */
    static void syncDone(long nanos) {
        if (zoomThisFrame || zoomHold > 0) {
            zoomSyncs++;
            zoomRecords += records - recordsAtSyncStart;
            zoomVisited += visited;
            zoomComposes += composes;
            zoomNanos += nanos;
            return;
        }
        if (zoomSyncs == 0) return;
        if (Config.DEBUG && zoomSyncs >= 4 && zoomNanos / zoomSyncs > 1_500_000L) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - zoomReportedAt > 300_000L) {
                zoomReportedAt = now;
                XLog.debug(String.format(java.util.Locale.ROOT,
                        "glass during a zoom: %d frames, a frame: %.0f glasses looked at,"
                                + " %.0f matrices, %.1f records, %.2f ms in the sync",
                        zoomSyncs, zoomVisited / (float) zoomSyncs,
                        zoomComposes / (float) zoomSyncs, zoomRecords / (float) zoomSyncs,
                        zoomNanos / 1e6f / zoomSyncs));
            }
        }
        zoomSyncs = 0;
        zoomRecords = 0;
        zoomVisited = 0;
        zoomComposes = 0;
        zoomNanos = 0;
    }

    /** The anchored walk is checked against transformMatrixToGlobal for its first uses. */
    private static int verifyLeft = 80;
    private static boolean anchoredOk = true;
    private static final Matrix VERIFY = new Matrix();
    private static final float[] VA = new float[9];
    private static final float[] VB = new float[9];

    /** Load counters, shown in the module's settings: records and pre-draw time. */
    static int records;
    /** Syncs run from the pager's computeScroll (flings), see {@link PagerScroll}. */
    static int scrollSyncs;
    private static int syncs;
    private static long syncNanos;
    private static long statsSince;

    /** Frame times from the launcher window while the readout is on (FrameMetrics). */
    private static final long[] FRAME_TIMES = new long[600];
    private static int frameCount;
    private static int framesLate;
    /** The window whose frames are counted (the launcher's, again after it is recreated). */
    private static java.lang.ref.WeakReference<android.view.Window> watched;
    private static Handler frameHandler;

    static void watchFrames(View v) {
        if (!Config.loadReadout) return;
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) {
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        if (!(c instanceof android.app.Activity)) return;
        android.view.Window win = ((android.app.Activity) c).getWindow();
        if (watched != null && watched.get() == win) return;
        watched = new java.lang.ref.WeakReference<>(win);
        if (frameHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("BlurIconsFrames");
            t.start();
            frameHandler = new Handler(t.getLooper());
        }
        win.addOnFrameMetricsAvailableListener(
                (window, metrics, dropped) -> {
                    if (!Config.loadReadout
                            || metrics.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 1) {
                        return;
                    }
                    long total = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION);
                    long deadline = metrics.getMetric(android.view.FrameMetrics.DEADLINE);
                    synchronized (FRAME_TIMES) {
                        if (frameCount < FRAME_TIMES.length) {
                            FRAME_TIMES[frameCount++] = total;
                            if (deadline > 0 && total > deadline) framesLate++;
                        }
                    }
                }, frameHandler);
    }

    /** ", frames p50 x p95 y ms, z% late" for the frames since the last call, then reset. */
    private static String frameLine() {
        long[] copy;
        int late;
        synchronized (FRAME_TIMES) {
            if (frameCount == 0) return "";
            copy = java.util.Arrays.copyOf(FRAME_TIMES, frameCount);
            late = framesLate;
            frameCount = 0;
            framesLate = 0;
        }
        java.util.Arrays.sort(copy);
        return String.format(java.util.Locale.ROOT, ", frames p50 %.1f ms p95 %.1f ms, %.0f%% late",
                copy[copy.length / 2] / 1e6f, copy[(int) (copy.length * 0.95f)] / 1e6f,
                100f * late / copy.length);
    }

    static void onSync(long nanos) {
        if (!Config.loadReadout) return;
        syncs++;
        syncNanos += nanos;
        long now = android.os.SystemClock.uptimeMillis();
        if (statsSince == 0 || now - statsSince > 15000) {
            // The first frame after a rest: count from here, not from before the rest.
            statsSince = now;
            synchronized (FRAME_TIMES) {
                frameCount = 0;
                framesLate = 0;
            }
            syncs = 1;
            records = 0;
            scrollSyncs = 0;
            syncNanos = nanos;
            return;
        }
        if (now - statsSince < 5000) return;
        float secs = (now - statsSince) / 1000f;
        String s = String.format(java.util.Locale.ROOT,
                "%s; load: %.0f frames/s, %.0f glass records/s, %.2f ms per frame for the sync,"
                        + " %d of the syncs in flings, %d lens shaders compiled,"
                        + " %.0f light steps/s, %.0f relights/s",
                loadStatus, syncs / secs, records / secs,
                syncs > 0 ? syncNanos / 1e6f / syncs : 0f, scrollSyncs, lensCompiles,
                GlassLight.steps / secs, relights / secs)
                + frameLine();
        statsSince = now;
        syncs = 0;
        records = 0;
        relights = 0;
        GlassLight.steps = 0;
        scrollSyncs = 0;
        syncNanos = 0;
        if (appContext != null) report(appContext, s);
    }

    private static final class Anchor {
        final Matrix global = new Matrix();
        int stamp = -1;
        boolean shown;
        int shownStamp = -1;
        float alpha = 1f;
        int alphaStamp = -1;
        /** The page's matrix of the pass before, and whether it moved or scaled since. */
        final float[] last = new float[9];
        boolean known;
        boolean moved;
        boolean scaled;
    }

    private static final float[] A9 = new float[9];

    /**
     * The page's matrix for this pass, found once for all its icons, and whether the page
     * moved or changed scale since the pass before: on a still page an icon that sits as it
     * did needs no matrix of its own, which is most icons on most frames. Main thread.
     */
    private static Anchor anchorFor(View anchor) {
        Anchor a = ANCHORS.get(anchor);
        if (a == null) {
            a = new Anchor();
            ANCHORS.put(anchor, a);
        }
        if (a.stamp != frameStamp) {
            a.global.reset();
            anchor.transformMatrixToGlobal(a.global);
            a.stamp = frameStamp;
            float[] now = A9;
            a.global.getValues(now);
            float[] last = a.last;
            if (!a.known) {
                a.known = true;
                a.moved = true;
                a.scaled = false;
                System.arraycopy(now, 0, last, 0, 9);
            } else {
                // Against the matrix of the last pass that counted as a move, not the pass
                // before: a zoom's tail moves the page by less than the tolerance on every
                // pass and by several pixels over all of them, and that must count once it
                // adds up, or the glass stays recorded for where the tail began. The scale
                // tolerance is half a pixel at the page's far edge, where a scale step moves
                // the outer icons the most.
                float size = Math.max(1f, Math.max(anchor.getWidth(), anchor.getHeight()));
                float tolScale = Math.min(0.002f, 0.5f / size);
                boolean moved = false;
                for (int i = 0; i < 9 && !moved; i++) {
                    float tol = i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y ? 0.5f : tolScale;
                    if (Math.abs(now[i] - last[i]) > tol) moved = true;
                }
                a.moved = moved;
                a.scaled = Math.abs(now[Matrix.MSCALE_X] - last[Matrix.MSCALE_X]) > 0.002f
                        || Math.abs(now[Matrix.MSCALE_Y] - last[Matrix.MSCALE_Y]) > 0.002f;
                if (moved) System.arraycopy(now, 0, last, 0, 9);
            }
        }
        return a;
    }

    /** {@link #localSignature}'s value for a chain with a transform of its own. */
    static final long LOCAL_ANIMATING = Long.MIN_VALUE;

    /**
     * Where {@code v} sits in its page, as a number: the position and the parents' scroll of
     * every view from it up to {@code anchor}. The same number on the next pass, with the
     * page itself still, means the icon is where it was. {@link #LOCAL_ANIMATING} when a view
     * on the way has a transform of its own (a press, a drag), which must be followed in full.
     */
    static long localSignature(View v, View anchor) {
        long sig = 17;
        int levels = 0;
        for (View x = v; x != anchor; ) {
            ViewParent p = x.getParent();
            if (!(p instanceof View) || ++levels > 12 || !x.getMatrix().isIdentity()) {
                return LOCAL_ANIMATING;
            }
            View parent = (View) p;
            sig = sig * 1000003L + x.getLeft();
            sig = sig * 1000003L + x.getTop();
            sig = sig * 1000003L + parent.getScrollX();
            sig = sig * 1000003L + parent.getScrollY();
            x = parent;
        }
        return sig == LOCAL_ANIMATING ? sig + 1 : sig;
    }

    /** Whether a page is on screen at all this pass (one isShown() per page, not per icon). */
    static boolean shown(View anchor) {
        Anchor a = ANCHORS.get(anchor);
        if (a == null) {
            a = new Anchor();
            ANCHORS.put(anchor, a);
        }
        if (a.shownStamp != frameStamp) {
            a.shown = anchor.isShown();
            a.shownStamp = frameStamp;
        }
        return a.shown;
    }

    /** The page's alpha with its parents', once per pass (a faded page takes no light). */
    static float pageAlpha(View page) {
        Anchor a = ANCHORS.get(page);
        if (a == null) {
            a = new Anchor();
            ANCHORS.put(page, a);
        }
        if (a.alphaStamp != frameStamp) {
            float al = 1f;
            for (Object v = page; v instanceof View && al > 0.01f; v = ((View) v).getParent()) {
                al *= ((View) v).getAlpha();
            }
            a.alpha = al;
            a.alphaStamp = frameStamp;
        }
        return a.alpha;
    }

    private static final Map<View, Anchor> ANCHORS = new java.util.WeakHashMap<>();
    private static final java.util.ArrayList<View> CHAIN = new java.util.ArrayList<>();

    /**
     * View#transformMatrixToGlobal of {@code v}, walking the window's tree only once per pass
     * for every icon of a page: the page's own matrix is shared, and only the few views from
     * it down to the icon are added (the same steps transformMatrixToGlobal takes). Main thread.
     */
    static void toGlobal(View v, View anchor, Matrix out) {
        out.reset();
        if (anchor == null || !anchor.isAttachedToWindow()) {
            v.transformMatrixToGlobal(out);
            return;
        }
        CHAIN.clear();
        View x = v;
        while (x != anchor) {
            CHAIN.add(x);
            ViewParent p = x.getParent();
            if (!(p instanceof View) || CHAIN.size() > 12) {
                CHAIN.clear();
                v.transformMatrixToGlobal(out);
                return;
            }
            x = (View) p;
        }
        if (!anchoredOk) {
            CHAIN.clear();
            v.transformMatrixToGlobal(out);
            return;
        }
        Anchor a = anchorFor(anchor);
        out.set(a.global);
        for (int i = CHAIN.size() - 1; i >= 0; i--) {
            View c = CHAIN.get(i);
            View parent = (View) c.getParent();
            out.preTranslate(-parent.getScrollX(), -parent.getScrollY());
            out.preTranslate(c.getLeft(), c.getTop());
            Matrix m = c.getMatrix();
            if (!m.isIdentity()) out.preConcat(m);
        }
        CHAIN.clear();
        if (verifyLeft > 0) {
            verifyLeft--;
            VERIFY.reset();
            v.transformMatrixToGlobal(VERIFY);
            out.getValues(VA);
            VERIFY.getValues(VB);
            for (int i = 0; i < 9; i++) {
                float tol = i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y ? 0.5f : 0.001f;
                if (Math.abs(VA[i] - VB[i]) > tol) {
                    anchoredOk = false;
                    XLog.i("page matrix differs from the view's own, full walks from now on");
                    out.set(VERIFY);
                    return;
                }
            }
        }
    }

    /** Per icon: the glass node and where the icon was on screen when it was recorded. */
    /**
     * A glass's look as the lens shader's constant uniforms (see {@link LensKey}): the depth,
     * the bright line and the hairline along the edge, the glow under the lit edges and the
     * face. Returns how far the hairline reaches past the outline (px), 0 without one. Shared
     * by the Home screen's glass and the settings screen's preview ({@link Sample}).
     */
    static float lookOf(float[] v, boolean rimInShader, float rimAlpha, float hairAlpha,
                        float density, boolean night, float zoom, float faceGain,
                        float faceOffset) {
        v[0] = Config.lensDepth ? DEPTH : 0f;
        float hz = 0f;
        if (rimInShader && (rimAlpha > 0f || hairAlpha > 0f)) {
            // As measured on the iPhone's screenshot (1 pt = 2.75 px there), in dp: a bright
            // line 1 px inside the edge, 1.5 px either side of it; a dark hairline 0.75 px
            // outside it, 1.25 px either side; the glow under the lit edges fading over 6 px.
            float pt = density / 2.75f;
            float rimMid = Math.max(0.5f, 1.0f * pt);
            float rimHalf = Math.max(1f, 1.5f * pt);
            float hairMid = 0.75f * pt;
            float hairHalf = Math.max(1f, 1.25f * pt);
            float glowFade = 6f * pt;
            hz = hairAlpha > 0f ? hairMid + hairHalf + 0.5f : 0f;
            v[1] = rimMid;
            v[2] = rimHalf;
            v[3] = hairMid;
            v[4] = hairHalf;
            // The line on the unlit edges, its colour (a brighter copy of the glass's), and
            // its strength: at night a softer one.
            v[5] = 0.10f;
            v[6] = 1.2f;
            v[7] = night ? 0.12f : 0.22f;
            v[8] = rimAlpha * (night ? 0.6f : 1f);
            v[9] = night ? 0.18f : 0.30f;
            v[10] = glowFade;
            v[11] = hairAlpha;
            // Past the glow's tail (5 % of it), the line and the hairline.
            v[12] = Math.max(glowFade * 3f, rimMid + rimHalf) + 1f;
        } else {
            for (int i = 1; i <= 12; i++) v[i] = 0f;
            v[2] = 1f;
            v[4] = 1f;
            v[6] = 1f;
            v[10] = 1f;
        }
        v[13] = zoom > 0f ? zoom : 1f;
        v[14] = faceGain;
        v[15] = faceOffset;
        return hz;
    }

    /**
     * The settings screen's copy of a picture, made as the Home screen makes its own
     * ({@link #prepare}: downscaled, saturated, three box blurs of {@code radius} picture px).
     */
    static Bitmap previewCopy(Bitmap src, float radius, float saturation) {
        return prepare(src, radius, saturation).bitmap;
    }

    /**
     * One glass of the settings screen's Home screen preview, drawn as the Home screen draws
     * its glass on Android 13+: the same shader ({@link #LENS_SHADER}), the same look
     * ({@link #lookOf}) and the same settings ({@link Config}), the light at rest. The picture
     * is a {@link #previewCopy} placed in the canvas by {@code copyToCanvas}. Hardware canvas,
     * main thread.
     */
    static final class Sample {
        private RuntimeShader shader;
        private final float[] look = new float[16];
        private final float[] shaderLook = new float[16];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix toGlass = new Matrix();
        private final Matrix toCopy = new Matrix();
        private final float[] m = new float[9];
        private Bitmap content;

        /**
         * Draws the glass over {@code r} (canvas coordinates) with this corner, lens band and
         * pull (fractions of its short side) and tint (straight ARGB, 0 for none).
         */
        void draw(Canvas canvas, Bitmap copy, Matrix copyToCanvas, RectF r, float corner,
                  float lensHeight, float lensAmount, int tint, float density, boolean night) {
            float w = r.width();
            float h = r.height();
            if (w <= 0f || h <= 0f) return;
            float hz = lookOf(look, true, Config.glassRim, Config.glassHairline, density, night,
                    1f, 1f, 0f);
            if (shader == null) {
                shader = new RuntimeShader(LENS_SHADER);
                java.util.Arrays.fill(shaderLook, Float.NaN);
            }
            if (!java.util.Arrays.equals(look, shaderLook)) {
                // A slider moved: the same shader, its constant uniforms set again.
                System.arraycopy(look, 0, shaderLook, 0, look.length);
                shader.setFloatUniform("offset", 0f, 0f);
                shader.setFloatUniform("depthEffect", look[0]);
                shader.setFloatUniform("edge", look[1], look[2], look[3], look[4]);
                shader.setFloatUniform("light", look[5], look[6], look[7], look[8]);
                shader.setFloatUniform("glow", look[9], look[10], look[11], look[12]);
                shader.setFloatUniform("face", look[13], look[14], look[15]);
                // The light at rest (GlassLight.REST): the top and bottom edges lit.
                shader.setFloatUniform("dir", 0f, 1f, 1f, 1f);
            }
            if (content != copy) {
                BitmapShader b = new BitmapShader(copy, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                b.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                shader.setInputShader("content", b);
                content = copy;
            }
            toGlass.set(copyToCanvas);
            toGlass.postTranslate(-r.left, -r.top);
            if (!toGlass.invert(toCopy)) return;
            toCopy.getValues(m);
            float min = Math.min(w, h);
            corner = Math.min(corner, min / 2f);
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("cornerRadii", corner, corner, corner, corner);
            shader.setFloatUniform("refractionHeight", min * lensHeight);
            shader.setFloatUniform("refractionAmount", -min * lensAmount);
            shader.setFloatUniform("toContent", m[Matrix.MSCALE_X], m[Matrix.MSKEW_X],
                    m[Matrix.MSKEW_Y], m[Matrix.MSCALE_Y]);
            shader.setFloatUniform("toContentT", m[Matrix.MTRANS_X], m[Matrix.MTRANS_Y]);
            shader.setColorUniform("tint", tint);
            paint.setShader(shader);
            canvas.save();
            canvas.translate(r.left, r.top);
            canvas.drawRoundRect(-hz, -hz, w + hz, h + hz, corner + hz, corner + hz, paint);
            canvas.restore();
        }
    }

    static final class Glass {
        private final RenderNode node = new RenderNode("BlurIconsGlass");
        private final Matrix canvasMatrix = new Matrix();
        private final Matrix toWindow = new Matrix();
        private final Matrix fromWindow = new Matrix();
        private final Matrix source = new Matrix();
        private final float[] recorded = new float[9];
        private final float[] current = new float[9];
        /** The scale seen last frame, and whether a record waits for the zoom to end. */
        private float scaleSeen = Float.NaN;
        private boolean recordWhenStill;
        /**
         * Which frames this glass records on while the Home screen zooms (see update): every
         * other glass made gets the other half of the frames. (An identity hash would not do:
         * ART hands them out from one generator whose low bit alternates, so glasses made in
         * step with some other object could all land on the same half.)
         */
        private final int parity = (nextParity++) & 1;
        /** Where the icon sat in its page at the last pass (see localSignature). */
        private long lastSig = LOCAL_ANIMATING;
        /**
         * The glass fades in over {@link #FADE_MS} after its icon was hidden and shown again
         * (the launch animation shows a plain copy of the icon and drops it when the app has
         * closed: the icon is the same, only the glass appears, so it appears softly).
         * {@code fadePending} until the first record while fading, which starts the clock:
         * nothing is lost while a zoom still holds records back.
         */
        private boolean fadePending;
        private long fadeStart;
        private long fadeMs = FADE_MS;
        private float fade = 1f;
        /** The alpha the caller last drew the glass with, that the fade scales. */
        private float baseAlpha = 1f;
        private static final long FADE_MS = 220;
        private static final float CONDENSE_FROM = 0.25f;
        private static final float CONDENSE_ZETA = 0.55f;
        /** sqrt(1 - zeta^2). */
        private static final float CONDENSE_SQ = 0.83516f;

        /**
         * Its view was drawn without this glass (into a software canvas, as One UI does while
         * the Home screen comes back from an app, or after a failed draw): the next draw with
         * the glass fades it in instead of showing it at once.
         */
        private boolean bareSeen;
        /** When the view was first drawn without the glass since it last had it (uptime). */
        private long bareSince;

        void markBare() {
            if (!bareSeen) {
                bareSeen = true;
                bareSince = android.os.SystemClock.uptimeMillis();
            }
        }

        /** Fades the glass in from nothing, from the next record on. */
        void fadeIn() {
            fadeIn(FADE_MS);
        }

        /** As above, over {@code ms}. */
        void fadeIn(long ms) {
            fadePending = true;
            fadeStart = 0;
            fadeMs = Math.max(16, ms);
            fade = 0f;
        }

        /** The share of the glass shown now by the fade (1 when none runs). */
        float fadeAlpha() {
            return fadePending ? 0f : fade;
        }

        /** Whether a fade is running or about to. */
        boolean fading() {
            return fadePending || fadeStart != 0;
        }

        /** Advances the fade to this frame; true while it is running (a record is due). */
        private boolean stepFade() {
            if (fadePending) {
                fadeStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
                fadePending = false;
                fade = 0f;
                condense = Config.glassCondense ? CONDENSE_FROM : 1f;
                return true;
            }
            if (fadeStart == 0) return false;
            long t = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
                    - fadeStart;
            if (t >= fadeMs) {
                fadeStart = 0;
                fade = 1f;
                condense = 1f;
                return true;
            }
            float x = t / (float) fadeMs;
            // Ease out: quick at first, settling at the end.
            fade = 1f - (1f - x) * (1f - x);
            condense = Config.glassCondense ? condenseAt(t / 1000f, fadeMs / 1000f) : 1f;
            return true;
        }

        /**
         * Liquid glass materialises by its light bending, not by fading: the lens grows from a
         * quarter, swells about a tenth past full and settles as the fade ends (a damped step,
         * 1% left at its end, so the last frame's snap to 1 does not show).
         */
        private static float condenseAt(float ts, float total) {
            float w = 4.6f / (CONDENSE_ZETA * total);
            float wd = w * CONDENSE_SQ;
            float env = (float) Math.exp(-CONDENSE_ZETA * w * ts);
            return 1f - (1f - CONDENSE_FROM) * env * ((float) Math.cos(wd * ts)
                    + (CONDENSE_ZETA / CONDENSE_SQ) * (float) Math.sin(wd * ts));
        }

        /** The node's alpha for {@link #baseAlpha} under the current fade. */
        private void applyFade() {
            float a = Math.max(0f, Math.min(1f, baseAlpha * fade));
            if (lensPaint) {
                int pa = Math.round(255f * a);
                if (pa != lensPaintObj.getAlpha()) lensPaintObj.setAlpha(pa);
            } else {
                node.setAlpha(a);
            }
        }
        private final Outline outline = new Outline();
        private final RectF region = new RectF();
        private final RectF onScreen = new RectF();
        private RenderEffect effect;
        /** What the effect was made for, to skip finding it again when nothing changed. */
        private int keyW = -1;
        private int keyH;
        private float keyCorner;
        private float keyBlur;
        private float keySat;
        private float keyLh;
        private float keyLa;
        private int keyGeneration;
        private boolean keyPrepared;
        /** The key of the copy this glass samples (see {@link #keyOf}). */
        private String preparedKey = "";
        /** The rim and hairline, rebuilt when the height or theme changes. */
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path rimPath = new Path();
        private int rimHeight = -1;
        private float rimWidth;
        private float innerWidth;
        private boolean rimNight;
        private Bitmap sourceBitmap;
        private int width;
        private int height;
        private boolean drawn;
        /** Saturation for this glass, or negative for the setting (search bars are grayer). */
        float saturation = -1f;
        /** Lens band and pull for this glass, or negative for the settings. */
        float lensHeight = -1f;
        float lensAmount = -1f;
        /**
         * The whole glass's magnification (iOS's icons: a little; widgets, the dock's platter
         * and bars: none), and the wallpaper's gain and offset under it (the dock's platter is
         * a little darker than what is behind it, the rest are not).
         */
        float zoom = 1f;
        float faceGain = 1f;
        float faceOffset = 0f;
        /** An outline of its own (in the canvas's coordinates), else the rounded rect. */
        android.graphics.Path shape;
        private final android.graphics.Path shapeHere = new android.graphics.Path();
        /** Why the last {@link #draw} returned false. */
        String failure;
        /**
         * A tone over the glass (straight ARGB, 0 for none), set by the caller before each
         * {@link #draw}: folded into the lens pass when it can be, which {@link #tintDrawn} says
         * after the draw, else the caller paints it as before.
         */
        int tint;
        boolean tintDrawn;
        private boolean night;
        private float density = 1f;
        /** The rim drawn by the lens pass (rounded rects), else as strokes after it. */
        private boolean rimInShader;
        private final Matrix toContent = new Matrix();
        private final float[] m9 = new float[9];
        /** The rim light's step this glass was recorded with (GlassLight.generation). */
        private int lightGen = -1;
        /** Its icon is hidden (set by IconBlur before each update): it takes no light. */
        boolean hidden;
        /** Off the window at its last record: it takes the light when it comes back. */
        private boolean offScreen;
        /** The light's way across this glass, as last recorded (for the strokes in the node). */
        private float litX = 0f;
        private float litY = 1f;
        private int winW;
        private int winH;
        /** What the stroked rim was built for beyond its height and theme (buildRim). */
        private int rimW = -1;
        private int rimAngle;
        private float rimNear = 1f;
        private float rimFar = 1f;
        /** The lit rim's and the dark hairline's strength (the settings). */
        private float rimAlpha() {
            return Config.glassRim;
        }

        private float hairAlpha() {
            return Config.glassHairline;
        }

        /**
         * Grows out of an icon's glass while its view is scaled (an open folder's panel, which
         * One UI scales from the folder icon's size): drawn screen-aligned, with the icon's
         * corner and lens at the start and its own at the end, instead of scaled with the view
         * (a rim a third of a pixel wide, elliptic corners).
         */
        boolean morphFromIcon;
        private float morphSx = 1f;
        private float morphSy = 1f;
        /** The lens's share while the glass condenses in (1 when it does not). */
        private float condense = 1f;
        private float lastCondense = 1f;

        private boolean morphActive() {
            return morphFromIcon && lensPaint && shape == null && morphSx > 0.01f
                    && morphSy > 0.01f
                    && (Math.abs(morphSx - 1f) > 0.003f || Math.abs(morphSy - 1f) > 0.003f);
        }

        private void readMorph(View view) {
            if (morphFromIcon) {
                morphSx = view.getScaleX();
                morphSy = view.getScaleY();
            } else {
                morphSx = 1f;
                morphSy = 1f;
            }
        }
        /**
         * Draws the glass for {@code region} (in the coordinates of {@code canvas}, which draws
         * inside {@code view}). Returns false when it cannot (no wallpaper, software canvas), so
         * the caller uses the blur instead.
         */
        boolean draw(Canvas canvas, View view, RectF region, float corner) {
            return draw(canvas, view, region, corner, 1f);
        }

        /** As above, faded by {@code alpha} (cells fading in and out). */
        boolean draw(Canvas canvas, View view, RectF region, float corner, float alpha) {
            return draw(canvas, view, region, corner, alpha, Config.glassBlur);
        }

        /** As above, with its own blur (the Apps screen sits over a blurred wallpaper). */
        boolean draw(Canvas canvas, View view, RectF region, float corner, float alpha,
                     float blur) {
            Bitmap w = wallpaper;
            if (w == null) {
                failure = "no picture";
                return false;
            }
            width = (int) Math.ceil(region.width());
            height = (int) Math.ceil(region.height());
            if (width <= 0 || height <= 0) {
                failure = "empty icon " + region;
                return false;
            }
            if (bareSeen) {
                // Back after frames without the glass: it comes in gradually from this frame,
                // over what was shown meanwhile, instead of all at once.
                bareSeen = false;
                IconBlur.noteGlassBack(view, android.os.SystemClock.uptimeMillis() - bareSince);
                fadeIn(RETURN_FADE_MS);
            }
            this.region.set(region);
            readMorph(view);
            // TextView draws its compound drawable translated within the view.
            canvas.getMatrix(canvasMatrix);

            float sat = saturation >= 0f ? saturation : Config.glassSaturation;
            float lh = lensHeight >= 0f ? lensHeight : Config.lensHeight;
            float la = lensAmount >= 0f ? lensAmount : Config.lensAmount;
            float min = Math.min(width, height);
            // The same glass as last time (most redraws): its effect and copy as they are.
            boolean same = keyPrepared && keyW == width && keyH == height
                    && keyCorner == corner && keyBlur == blur && keySat == sat && keyLh == lh
                    && keyLa == la && keyGeneration == generation;
            Prepared p = same ? null : prepared(blur * min, sat);
            if (same) {
                // Touched so the copy in use never becomes the eldest of the cache.
                PREPARED.get(preparedKey);
            } else {
                preparedKey = keyOf(blur * min, sat);
            }
            RenderEffect e;
            if (same) {
                e = effect;
            } else if (p != null) {
                sourceBitmap = p.bitmap;
                source.setScale(p.scaleX, p.scaleY);
                // With the copy ready the lens is the paint's shader (no effect, no layer).
                e = android.os.Build.VERSION.SDK_INT >= 33 ? null
                        : lens(width, height, corner, lh, la);
            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Until the prepared copy is ready (launch, a wallpaper or tuning change): the
                // nearest copy there is, else the wallpaper itself, through the same lens paint.
                // A RenderEffect here would give every glass a layer of its own, the most a
                // frame can cost, exactly while the launcher animates in.
                WAITING.put(view, keyOf(blur * min, sat));
                Prepared near = nearestPrepared(sat);
                if (near != null) {
                    sourceBitmap = near.bitmap;
                    source.setScale(near.scaleX, near.scaleY);
                } else {
                    sourceBitmap = w;
                    source.reset();
                }
                e = null;
            } else {
                // Until the prepared copy is ready: blur and saturate on the GPU.
                WAITING.put(view, keyOf(blur * min, sat));
                sourceBitmap = w;
                source.reset();
                e = effect(width, height, corner, blur, sat, lh, la);
            }
            if (e != effect) {
                effect = e;
                node.setRenderEffect(e);
            }
            if (!same) {
                keyPrepared = p != null;
                keyW = width;
                keyH = height;
                keyCorner = corner;
                keyBlur = blur;
                keySat = sat;
                keyLh = lh;
                keyLa = la;
                keyGeneration = generation;
            }
            if (width != posW || height != posH) {
                node.setPosition(0, 0, width, height);
                posW = width;
                posH = height;
            }
            if (shape != null && android.os.Build.VERSION.SDK_INT >= 33) {
                shapeHere.set(shape);
                shapeHere.offset(-region.left, -region.top);
            }
            night = (view.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            android.util.DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            density = metrics.density;
            winW = metrics.widthPixels;
            winH = metrics.heightPixels;
            baseAlpha = alpha;
            boolean fading = stepFade();
            alpha = alpha * fade;
            if (fading) scheduleFrame(view);
            // On Android 13+ always the lens paint: over the prepared copy, or a stand-in.
            lensPaint = android.os.Build.VERSION.SDK_INT >= 33;
            rimInShader = lensPaint && shape == null;
            tintDrawn = lensPaint;
            if (lensPaint) {
                // The node holds one shape drawn with the lens as its paint's shader: a RenderNode
                // without an effect, outline clip or alpha of its own is played back inline, no
                // layer for the GPU to render to and switch between (dozens of them a frame, and
                // all again on every frame of the Home screen's zoom as apps open and close).
                // Moves re-record just this one draw, not the icon view. The hairline's outer
                // half lies past the node's bounds.
                if (nodeMode != 1) {
                    node.setClipToOutline(false);
                    node.setClipToBounds(false);
                    node.setAlpha(1f);
                    nodeMode = 1;
                }
                int pa = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
                if (pa != lensPaintObj.getAlpha()) lensPaintObj.setAlpha(pa);
            } else {
                nodeMode = 2;
                node.setClipToBounds(true);
                if (shape != null && android.os.Build.VERSION.SDK_INT >= 33) {
                    outline.setPath(shapeHere);
                } else {
                    outline.setRoundRect(0, 0, width, height, corner);
                }
                node.setOutline(outline);
                node.setClipToOutline(true);
                node.setAlpha(Math.max(0f, Math.min(1f, alpha)));
            }
            if (!recordIfChanged(view, alpha)) {
                failure = "icon not invertible on screen";
                return false;
            }

            canvas.save();
            canvas.translate(region.left, region.top);
            try {
                if (canvas instanceof RecordingCanvas) {
                    ((RecordingCanvas) canvas).drawRenderNode(node);
                } else {
                    // A hardware canvas of another class (vendor or wrapper): same method,
                    // looked up once per class.
                    Class<?> cc = canvas.getClass();
                    if (cc != drawCanvasClass) {
                        drawMethod = cc.getMethod("drawRenderNode", RenderNode.class);
                        drawCanvasClass = cc;
                    }
                    drawMethod.invoke(canvas, node);
                }
            } catch (ReflectiveOperationException ex) {
                failure = "canvas " + canvas.getClass().getName() + " cannot draw a RenderNode";
                return false;
            } finally {
                canvas.restore();
            }
            // With the lens as the paint, a rim of its own shape is stroked inside the node
            // (recordPlacedTraced), lit as the glass's other rims.
            if (!lensPaint && !rimInShader && (rimAlpha() > 0f || hairAlpha() > 0f)) {
                drawRim(canvas, view, corner, alpha);
            }
            drawn = true;
            GlassLight.wake();
            return true;
        }

        /** The node's position and mode as last set (1 lens paint, 2 effect), to set on change. */
        private int posW = -1;
        private int posH = -1;
        private int nodeMode;

        /** The lens as the paint's shader over the prepared copy, instead of an effect. */
        private boolean lensPaint;
        private final Paint lensPaintObj = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix contentMatrix = new Matrix();

        /** The one draw of the lens pass: the shared shader set up for this glass. */
        private boolean recordLens(RecordingCanvas rc) {
            // The copy mapped to where the glass is on screen now, inverted: glass to copy.
            contentMatrix.set(source);
            synchronized (WALLPAPER_TO_SCREEN) {
                contentMatrix.postConcat(WALLPAPER_TO_SCREEN);
            }
            contentMatrix.postConcat(fromWindow);
            contentMatrix.postTranslate(-region.left, -region.top);
            // Growing out of an icon (morphFromIcon): drawn in the view's coordinates times
            // its scale, which is the screen's, so the rim, corners and lens are in screen
            // pixels whatever the scale.
            boolean morph = morphActive();
            float sx = morph ? morphSx : 1f;
            float sy = morph ? morphSy : 1f;
            if (morph) contentMatrix.postScale(sx, sy);
            if (!contentMatrix.invert(toContent)) return false;
            toContent.getValues(m9);
            float min = Math.min(width, height);
            float gw = width * sx;
            float gh = height * sy;
            float[] v = LOOKUP.v;
            float hz = lookOf(v, rimInShader, rimAlpha(), hairAlpha(), density, night, zoom,
                    faceGain, faceOffset);
            Lens lens = lensFor(LOOKUP);
            RuntimeShader s = lens.s;
            if (lens.content != sourceBitmap) {
                s.setInputShader("content", contentShader(sourceBitmap));
                lens.content = sourceBitmap;
            }
            if (!lens.tintSet || lens.tint != tint) {
                s.setColorUniform("tint", tint);
                lens.tint = tint;
                lens.tintSet = true;
            }
            // The glass's own geometry, set when it differs from the last glass of the class
            // (icons of a page are all the same, so it is set once).
            float[] g = lens.geo;
            float corner = keyCorner;
            float rh = min * keyLh;
            float ra = -min * keyLa;
            if (morph) {
                // From the icon's glass (its corner and lens) to the panel's, as the panel
                // grows; the pull swells a quarter past both half way, the platter of iOS.
                float icon = iconMin > 0f ? iconMin : 60f * density;
                float small = Math.min(gw, gh);
                float t = Math.max(0f, Math.min(1f, (small - icon) / Math.max(1f, min - icon)));
                float ic = iconCorner > 0f ? iconCorner : icon * 0.24f;
                corner = Math.min(small / 2f, ic + (keyCorner - ic) * t);
                rh = Math.min(small / 2f, icon * Config.lensHeight
                        + (min * keyLh - icon * Config.lensHeight) * t);
                ra = -(icon * Config.lensAmount + (min * keyLa - icon * Config.lensAmount) * t)
                        * (1f + t * (1f - t));
            }
            if (condense != 1f) {
                rh = Math.min(Math.min(gw, gh) * 0.5f, rh * condense);
                ra *= condense;
            }
            if (g[0] != gw || g[1] != gh) {
                s.setFloatUniform("size", gw, gh);
                g[0] = gw;
                g[1] = gh;
            }
            if (g[2] != corner) {
                s.setFloatUniform("cornerRadii", corner, corner, corner, corner);
                g[2] = corner;
            }
            if (g[3] != rh || g[4] != ra) {
                s.setFloatUniform("refractionHeight", rh);
                s.setFloatUniform("refractionAmount", ra);
                g[3] = rh;
                g[4] = ra;
            }
            // The rim light's way, from the screen's into this glass's coordinates (one light
            // for all; a glass turned or scaled with its page takes it as it sits).
            DIRV[0] = GlassLight.dirX;
            DIRV[1] = GlassLight.dirY;
            fromWindow.mapVectors(DIRV);
            DIRV[0] *= sx;
            DIRV[1] *= sy;
            float len = (float) Math.hypot(DIRV[0], DIRV[1]);
            float ux = 0f;
            float uy = 1f;
            if (len > 1e-6f) {
                ux = DIRV[0] / len;
                uy = DIRV[1] / len;
            }
            // Square to the glass: exactly the look at rest.
            if (Math.abs(ux) < 1e-4f) {
                ux = 0f;
                uy = uy < 0f ? -1f : 1f;
            }
            litX = ux;
            litY = uy;
            float fz = GlassLight.far;
            float fw = GlassLight.near;
            if (g[6] != ux || g[7] != uy || g[8] != fz || g[9] != fw) {
                s.setFloatUniform("dir", ux, uy, fz, fw);
                g[6] = ux;
                g[7] = uy;
                g[8] = fz;
                g[9] = fw;
            }
            // Only where the glass is changes from one record to the next.
            s.setFloatUniform("toContent", m9[Matrix.MSCALE_X], m9[Matrix.MSKEW_X],
                    m9[Matrix.MSKEW_Y], m9[Matrix.MSCALE_Y]);
            s.setFloatUniform("toContentT", m9[Matrix.MTRANS_X], m9[Matrix.MTRANS_Y]);
            lensPaintObj.setShader(s);
            if (shape != null) {
                rc.drawPath(shapeHere, lensPaintObj);
            } else {
                if (morph) {
                    rc.save();
                    rc.scale(1f / sx, 1f / sy);
                }
                rc.drawRoundRect(-hz, -hz, gw + hz, gh + hz, corner + hz, corner + hz,
                        lensPaintObj);
                if (morph) rc.restore();
            }
            return true;
        }

        /**
         * The light-rim / dark-rim of rdlabo's ionic-theme-ios27 (_glass.scss) over the lens: a
         * 0.5 dp border lit at the top and bottom and clear at the sides, a soft inner glow
         * along both, and a thin dark hairline around the outline.
         */
        private void drawRim(Canvas canvas, View view, float corner, float alpha) {
            canvas.save();
            canvas.translate(region.left, region.top);
            try {
                drawRimShapes(canvas, corner, alpha, 0f, 1f, 1f, 1f);
            } finally {
                canvas.restore();
            }
        }

        /**
         * The rim's strokes in the glass's own coordinates, lit along ({@code ux}, {@code uy})
         * with these shares at the near and far ends: into the canvas (the effect path), or
         * into the node after the lens (a widget's own shape).
         */
        private void drawRimShapes(Canvas canvas, float corner, float alpha, float ux, float uy,
                                   float nearF, float farF) {
            int ai = Math.floorMod(Math.round((float) Math.atan2(ux, uy) * 96f
                    / (float) (2 * Math.PI)), 96);
            if (rimHeight != height || rimW != width || rimNight != night || rimAngle != ai
                    || rimNear != nearF || rimFar != farF) {
                rimHeight = height;
                rimW = width;
                rimNight = night;
                rimAngle = ai;
                rimNear = nearF;
                rimFar = farF;
                buildRim(density, night, ux, uy, nearF, farF);
            }
            float a = 255f * Math.max(0f, Math.min(1f, alpha));
            rim.setAlpha(Math.round(a * rimAlpha()));
            inner.setAlpha(Math.round(a * rimAlpha()));
            hairline.setAlpha(Math.round(a * hairAlpha()));
            rimPath.reset();
            if (shape != null && android.os.Build.VERSION.SDK_INT >= 33) {
                // An outline of its own (widgets, few): the strokes clipped to it.
                rimPath.set(shapeHere);
                if (hairAlpha() > 0f) canvas.drawPath(rimPath, hairline);
                if (rimAlpha() > 0f) {
                    int save = canvas.save();
                    canvas.clipPath(rimPath);
                    inner.setStrokeWidth(innerWidth * 2f);
                    rim.setStrokeWidth(rimWidth * 2f);
                    canvas.drawPath(rimPath, inner);
                    canvas.drawPath(rimPath, rim);
                    inner.setStrokeWidth(innerWidth);
                    rim.setStrokeWidth(rimWidth);
                    canvas.restoreToCount(save);
                }
            } else {
                // Rounded rects (icons, many): each stroke inset to lie inside the outline,
                // the same band a stroke on the outline clipped to it would leave, without
                // the clip.
                if (hairAlpha() > 0f) {
                    canvas.drawRoundRect(0f, 0f, width, height, corner, corner, hairline);
                }
                if (rimAlpha() > 0f) {
                    float i = innerWidth / 2f;
                    canvas.drawRoundRect(i, i, width - i, height - i, Math.max(0f, corner - i),
                            Math.max(0f, corner - i), inner);
                    i = rimWidth / 2f;
                    canvas.drawRoundRect(i, i, width - i, height - i, Math.max(0f, corner - i),
                            Math.max(0f, corner - i), rim);
                }
            }
        }

        private void buildRim(float d, boolean night, float ux, float uy, float nearF,
                              float farF) {
            int white = Color.WHITE;
            // The bands that show: half of what a stroke centred on the outline would be.
            rim.setStyle(Paint.Style.STROKE);
            rimWidth = Math.max(1f, d * 0.55f);
            rim.setStrokeWidth(rimWidth);
            inner.setStyle(Paint.Style.STROKE);
            innerWidth = d * 2f;
            inner.setStrokeWidth(innerWidth);
            hairline.setStyle(Paint.Style.STROKE);
            hairline.setStrokeWidth(Math.max(1f, d));
            hairline.setColor(Color.BLACK);
            float glow = night ? 0.2f : 0.3f;
            // Across the glass along the light (at rest from the top to the bottom).
            float ext = Math.max(1f, Math.abs(ux) * width + Math.abs(uy) * height);
            float cx = width / 2f;
            float cy = height / 2f;
            float x0 = cx - ux * ext / 2f;
            float y0 = cy - uy * ext / 2f;
            float x1 = cx + ux * ext / 2f;
            float y1 = cy + uy * ext / 2f;
            float glowBand = frac(d * (night ? 2f : 3f), ext);
            rim.setShader(new LinearGradient(x0, y0, x1, y1,
                    night ? new int[]{a(white, 0.4f * nearF), 0, 0, a(white, 0.4f * farF)}
                            : new int[]{a(white, nearF), 0, 0, a(white, farF)},
                    night ? new float[]{0f, 0.35f, 0.65f, 1f}
                            : new float[]{0f, 0.5f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            inner.setShader(new LinearGradient(x0, y0, x1, y1,
                    new int[]{a(white, glow * nearF), 0, 0, a(white, glow * farF)},
                    new float[]{0f, glowBand, 1f - glowBand, 1f}, Shader.TileMode.CLAMP));
        }

        private static float frac(float px, float h) {
            return Math.max(0f, Math.min(0.5f, px / h));
        }

        private static int a(int rgb, float alpha) {
            return (Math.round(alpha * 255f) << 24) | (rgb & 0xFFFFFF);
        }

        /** Records the wallpaper part under the glass, for where {@code view} is now. */
        private boolean record(View view) {
            // transformMatrixToGlobal only pre-multiplies: it must start from identity.
            toWindow.reset();
            view.transformMatrixToGlobal(toWindow);
            toWindow.getValues(recorded);
            toWindow.preConcat(canvasMatrix);
            return recordPlaced();
        }

        /** What the node was last recorded with, beyond the key: a redraw of the same is free. */
        private final float[] lastCanvas = new float[9];
        private float lastAlpha = -1f;
        private int lastTint;
        private boolean lastNight;
        private android.graphics.Path lastShape;
        private boolean lastPrepared;
        private int lastW = -1;
        private int lastH;
        private float lastCorner;
        private float lastLeft;
        private float lastTop;
        private float lastDensity;
        private float lastZoom = 1f;
        private float lastFaceGain = 1f;
        private float lastFaceOffset;
        private int lastGen = -1;
        private Bitmap lastSource;

        /**
         * Records unless nothing that goes into the node changed since the last record (an
         * icon view redrawn for a press, a badge or a neighbour): its node is drawn as it is.
         */
        private boolean recordIfChanged(View view, float alpha) {
            toWindow.reset();
            view.transformMatrixToGlobal(toWindow);
            toWindow.getValues(current);
            canvasMatrix.getValues(m9);
            boolean same = lensPaint && keyPrepared && lastPrepared && node.hasDisplayList()
                    && recordedMapGen == mapGeneration && recordedZoomGen == zoomGeneration
                    && alpha == lastAlpha && tint == lastTint
                    && night == lastNight && shape == lastShape && width == lastW
                    && height == lastH && keyCorner == lastCorner && region.left == lastLeft
                    && region.top == lastTop && sourceBitmap == lastSource
                    && generation == lastGen && density == lastDensity
                    && lightGen == GlassLight.generation && condense == lastCondense
                    && zoom == lastZoom && faceGain == lastFaceGain
                    && faceOffset == lastFaceOffset;
            for (int i = 0; i < 9 && same; i++) {
                same = Math.abs(current[i] - recorded[i]) < 0.01f
                        && Math.abs(m9[i] - lastCanvas[i]) < 0.01f;
            }
            if (same) return true;
            System.arraycopy(m9, 0, lastCanvas, 0, 9);
            lastAlpha = alpha;
            lastTint = tint;
            lastZoom = zoom;
            lastFaceGain = faceGain;
            lastFaceOffset = faceOffset;
            lastNight = night;
            lastShape = shape;
            lastPrepared = keyPrepared;
            System.arraycopy(current, 0, recorded, 0, 9);
            toWindow.preConcat(canvasMatrix);
            return recordPlaced();
        }

        /** Records with {@link #toWindow} already the glass's place on screen. */
        private boolean recordPlaced() {
            android.os.Trace.beginSection("BlurIcons glass record");
            try {
                return recordPlacedTraced();
            } finally {
                android.os.Trace.endSection();
            }
        }

        private boolean recordPlacedTraced() {
            recordedMapGen = mapGeneration;
            recordedZoomGen = zoomGeneration;
            records++;
            lastW = width;
            lastH = height;
            lastCorner = keyCorner;
            lastLeft = region.left;
            lastTop = region.top;
            lastSource = sourceBitmap;
            lastGen = generation;
            lastDensity = density;
            lastCondense = condense;
            if (lightGen != GlassLight.generation) lightTakenPass++;
            lightGen = GlassLight.generation;
            GlassLight.wake();
            if (!toWindow.invert(fromWindow)) return false;
            onScreen.set(region);
            toWindow.mapRect(onScreen);
            offScreen = winW > 0 && (onScreen.right < 0f || onScreen.bottom < 0f
                    || onScreen.left > winW || onScreen.top > winH);
            RecordingCanvas rc = node.beginRecording(width, height);
            boolean ok = true;
            try {
                if (lensPaint) {
                    ok = recordLens(rc);
                    if (ok) rimInNode(rc);
                } else {
                    rc.translate(-region.left, -region.top);
                    rc.concat(fromWindow);
                    synchronized (WALLPAPER_TO_SCREEN) {
                        rc.concat(WALLPAPER_TO_SCREEN);
                    }
                    rc.concat(source);
                    rc.drawBitmap(sourceBitmap, 0f, 0f, BITMAP_PAINT);
                }
            } finally {
                node.endRecording();
            }
            return ok;
        }

        /** A rim of the glass's own shape, stroked after the lens and lit as the others. */
        private void rimInNode(RecordingCanvas rc) {
            if (rimInShader || (rimAlpha() <= 0f && hairAlpha() <= 0f)) return;
            drawRimShapes(rc, keyCorner, lensPaintObj.getAlpha() / 255f, litX, litY,
                    GlassLight.near, GlassLight.far);
        }

        /**
         * The rim light took a step and this glass did not move: its one draw is recorded again
         * with the new light, from what its last record found (its place, copy and alpha are
         * the same). Not while the Home screen zooms (after it, by a frame asked for), nor for
         * glass not seen: a hidden icon, a faded page, glass off the window.
         */
        private void relightIfDue(View view, View page) {
            if (!lensPaint || lightGen == GlassLight.generation) return;
            if (zooming()) {
                deferredPass++;
                requestStillFrame(view);
                return;
            }
            if (hidden || offScreen || lastAlpha <= 0.004f || sourceBitmap == null
                    || !node.hasDisplayList()) {
                return;
            }
            if (page != null && pageAlpha(page) < 0.01f) return;
            lightGen = GlassLight.generation;
            records++;
            relights++;
            lightTakenPass++;
            RecordingCanvas rc = node.beginRecording(width, height);
            try {
                if (recordLens(rc)) rimInNode(rc);
            } finally {
                node.endRecording();
            }
        }

        /** The wallpaper mapping this glass was recorded with (see {@link #mapGeneration}). */
        private int recordedMapGen = -1;
        /** The wallpaper zoom step it was recorded with (see {@link #zoomGeneration}). */
        private int recordedZoomGen = -1;

        /**
         * Follows {@code view} on screen (paging, scrolling, animations): re-records only the
         * glass node, which the view's display list draws, so the view itself is not redrawn.
         * A record is one draw with the shared lens shader, cheap enough for every frame of
         * an animation. Glass that is off screen waits until it comes back. Pre-draw, main
         * thread.
         */
        void update(View view) {
            if (!drawn || sourceBitmap == null) return;
            // Not drawn since the picture or tuning changed: its copy may be gone. The redraw
            // that comes with every such change records it with the right one.
            if (keyGeneration != generation) return;
            readMorph(view);
            // Its own view's scale (an open folder's panel growing) is not the Home screen
            // zooming: recorded on every frame, as its shape depends on the scale.
            boolean morphing = morphActive();
            View a = anchor != null ? anchor.get() : null;
            // A page that is not shown (the Apps screen behind the Home screen, a closed
            // folder): nothing to follow; it catches up when shown, as its place differs.
            if (a != null && !shown(a)) return;
            visited++;
            boolean stale = recordedMapGen != mapGeneration;
            // The wallpaper zooming under the glass (an app opening or closing): recorded
            // like a move, through the zoom's every-other-frame throttle.
            boolean zoomStale = recordedZoomGen != zoomGeneration;
            boolean fadeDue = fadePending || fadeStart != 0;
            if (a != null && anchoredOk && a.isAttachedToWindow()) {
                Anchor st = anchorFor(a);
                long sig = localSignature(view, a);
                if (sig != LOCAL_ANIMATING) {
                    // The page did not move and the icon sits where it did: nothing to do,
                    // and no matrix of its own to find. Most icons, most frames.
                    if (!st.moved && sig == lastSig && !stale && !zoomStale && !fadeDue
                            && !recordWhenStill) {
                        relightIfDue(view, a);
                        return;
                    }
                    lastSig = sig;
                    if (st.scaled) zoomThisFrame = true;
                    // The zoom's other half of the frames: without the matrix at all.
                    if (lensPaint && !stale && !fadeDue && (st.scaled || zooming())
                            && ((zoomFrame + parity) & 1) != 0) {
                        recordWhenStill = true;
                        requestStillFrame(view);
                        return;
                    }
                } else {
                    lastSig = LOCAL_ANIMATING;
                }
            }
            composes++;
            toGlobal(view, a, toWindow);
            toWindow.getValues(current);
            boolean moved = false;
            boolean reshaped = false;
            for (int i = 0; i < 9; i++) {
                // Translations in pixels, the rest scale and skew.
                if (i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y) {
                    if (Math.abs(current[i] - recorded[i]) > 0.5f) moved = true;
                } else if (Math.abs(current[i] - recorded[i]) > 0.002f) {
                    reshaped = true;
                }
            }
            // A zoom (an app opening or closing, a folder): the whole Home screen scales for
            // a few hundred milliseconds, every icon moves every frame, and recording every
            // glass on every frame is what makes it stutter. The node is drawn inside the
            // view's own transform, so it follows the zoom as it is; it is recorded once
            // more when the scale stops changing.
            // With the lens as the paint (Android 13+) a record is one draw with a shared
            // shader, cheap enough on every frame of the zoom, and the glass then shows what is
            // under it all the way through an app opening or closing or the Apps screen sliding
            // up, instead of a frozen copy that catches up when the motion ends. The freeze is
            // kept for the effect path, where every record renders a layer.
            float sx = current[Matrix.MSCALE_X];
            boolean scaling = !Float.isNaN(scaleSeen) && Math.abs(sx - scaleSeen) > 0.002f;
            scaleSeen = sx;
            if (scaling && !morphing) zoomThisFrame = true;
            if (scaling && !lensPaint) {
                if (moved || reshaped || stale) {
                    recordWhenStill = true;
                    // The last frame of a press or zoom may be the last frame drawn at all:
                    // one more is asked for, in which the glass is recorded where it ended.
                    requestStillFrame(view);
                }
                return;
            }
            if ((scaling || zooming()) && !stale && !morphing) {
                // The Home screen zooming (an app opening or closing): every glass on screen
                // moves on every frame, and recording them all on every one of 120 frames a
                // second is what made the launcher's own animation stutter. Each glass is
                // recorded on every other frame instead (half of them on the even frames,
                // half on the odd), and only for a move the eye can see; a last frame after
                // the zoom records every glass where it ended.
                boolean small = Math.abs(current[Matrix.MTRANS_X] - recorded[Matrix.MTRANS_X]) < 1f
                        && Math.abs(current[Matrix.MTRANS_Y] - recorded[Matrix.MTRANS_Y]) < 1f
                        && Math.abs(current[Matrix.MSCALE_X] - recorded[Matrix.MSCALE_X]) < 0.004f
                        && Math.abs(current[Matrix.MSCALE_Y] - recorded[Matrix.MSCALE_Y]) < 0.004f;
                if ((small && !zoomStale) || ((zoomFrame + parity) & 1) != 0) {
                    if (moved || reshaped || zoomStale) {
                        recordWhenStill = true;
                        requestStillFrame(view);
                    }
                    return;
                }
            }
            boolean fading = stepFade();
            if (!moved && !reshaped && !stale && !zoomStale && !recordWhenStill && !fading) {
                relightIfDue(view, a);
                return;
            }
            recordWhenStill = false;
            if (fading) {
                applyFade();
                // This frame's record is the fade's: the icon's own draw in the same frame
                // (the fade keeps it drawn, for the tone and the glyph) sees the same alpha
                // and records nothing more.
                lastAlpha = baseAlpha * fade;
                // Another frame for the next step of the fade.
                scheduleFrame(view);
            }
            toWindow.preConcat(canvasMatrix);
            onScreen.set(region);
            toWindow.mapRect(onScreen);
            // The window's own size (it turns with the screen): the next page, a screen to
            // the right while swiping, is off screen and waits until it scrolls in.
            android.util.DisplayMetrics dm = view.getResources().getDisplayMetrics();
            winW = dm.widthPixels;
            winH = dm.heightPixels;
            if (onScreen.right < 0f || onScreen.bottom < 0f || onScreen.left > dm.widthPixels
                    || onScreen.top > dm.heightPixels) {
                offScreen = true;
                return;
            }
            // The place was just found: recorded from it, without walking the tree again.
            System.arraycopy(current, 0, recorded, 0, 9);
            recordPlaced();
        }

        /** The page the glass's view sits in: its matrix is found once per frame for all. */
        java.lang.ref.WeakReference<View> anchor;

        void release() {
            drawn = false;
            lightGen = -1;
            offScreen = false;
            hidden = false;
            litX = 0f;
            litY = 1f;
            condense = 1f;
            lastCondense = 1f;
            lensPaint = false;
            lastSig = LOCAL_ANIMATING;
            fadePending = false;
            fadeStart = 0;
            fade = 1f;
            rimInShader = false;
            tintDrawn = false;
            sourceBitmap = null;
            // Neither keeps a copy of the picture alive while the glass is off screen: the
            // next draw records again and sets both.
            lastSource = null;
            lensPaintObj.setShader(null);
            keyW = -1;
            // Nothing recorded any more: the next draw must record, whatever it remembers.
            lastW = -1;
            lastAlpha = -1f;
            lastPrepared = false;
            posW = -1;
            posH = -1;
            nodeMode = 0;
            node.discardDisplayList();
        }
    }
}
