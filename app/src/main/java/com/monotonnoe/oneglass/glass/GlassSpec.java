package com.monotonnoe.oneglass.glass;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RectF;

import com.monotonnoe.oneglass.Config;

/**
 * Resolved, pixel-space parameters of one glass surface.
 *
 * The layer recipes are copied from the iOS 27 Figma kit ("iOS 27 Widgets", page Playground):
 * every Home Screen variant (Light/Dark x Default/Clear/Tinted) uses the same structure,
 *  - platters (Dock, Search): parent fill + GLASS with Linear Dodge corner glows and hairlines,
 *  - tiles (widgets, folders): parent fill, optional tint / Linear Burn overlay, GLASS with
 *    Linear Dodge corner glows and Color hairlines.
 * The optical constants of Figma's GLASS effect itself are not exposed by the Figma API; they
 * were fitted against Figma's own render of the Playground (see tools/fit in the repo history).
 */
public final class GlassSpec {

    public static final int ROLE_DOCK = 1;
    public static final int ROLE_PILL = 2;
    public static final int ROLE_WIDGET = 3;
    public static final int ROLE_FOLDER = 4;
    /** GLASS layer over an app icon (Figma "Light/Dark-Default App Icon"): refracts the icon itself. */
    public static final int ROLE_ICON = 5;

    /** Figma: app icon 60pt with corner radius 15.5pt. */
    public static final float ICON_RADIUS_RATIO = 15.5f / 60f;

    // Figma blend modes as passed to the shader
    public static final float M_NONE = 0f;
    public static final float M_NORMAL = 1f;
    public static final float M_EXCLUSION = 2f;
    public static final float M_LUMINOSITY = 3f;
    public static final float M_LINEAR_BURN = 4f;
    public static final float M_COLOR = 5f;
    public static final float M_LINEAR_DODGE = 6f;

    // ---- GLASS optics, fitted to Figma's render (values in pt; 1pt = 1dp) ----
    public static final float FROST = 0.6f;
    public static final float REFRACTION_HEIGHT = 14f;
    public static final float REFRACTION_AMOUNT = -16f;
    public static final float DEPTH = 0.25f;
    public static final float DISPERSION = 0.5f;
    public static final float SATURATION = 1.5f;
    public static final float BRIGHTNESS = 0f;
    public static final float LIGHT = 0.3f;
    public static final float LIGHT_WIDTH = 0.6f;
    public static final float LIGHT_SOFTNESS = 0.3f;
    public static final float LIGHT_ANGLE = 45f;
    public static final float LIGHT_FALLOFF = 1f;
    public static final float CORNER_POW = 2.6f;
    public static final float ZOOM = 1f;

    public float radius;
    public float cornerPow;
    public float smoothing;
    public float refractionHeight;
    public float refractionAmount;
    public float depth;
    public float dispersion;
    public float saturation;
    public float brightness;
    /** r, g, b, alpha, mode */
    public final float[] layer1 = new float[5];
    public final float[] layer2 = new float[5];
    public final float[] layer3 = new float[5];
    public float lightIntensity;
    public float lightWidth;
    public float lightSoftness;
    public float lightFalloff;
    public float lightX;
    public float lightY;
    public float glowAmount;
    public float glowOffset;
    public float glowSigma;
    public final float[] hairColor = new float[3];
    public float hairOffset;
    public float hairMode;
    public float hairAmount;
    public float hairSigma;
    public float sideLine;
    public float sideOffset;
    public boolean edgeOnly;
    public float zoom;

    private static final GlassSpec SHARED = new GlassSpec();

    public static boolean isDark(Config cfg, Resources res) {
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return cfg.tintMode == Config.TINT_DARK || (cfg.tintMode == Config.TINT_AUTO && night);
    }

    /** Main-thread only: returns a shared instance. */
    public static GlassSpec resolve(int role, Config cfg, Resources res, RectF rect) {
        return resolve(role, cfg, res.getDisplayMetrics().density, isDark(cfg, res), rect);
    }

    private static void set(float[] l, int rgb, float alpha, float mode) {
        l[0] = ((rgb >> 16) & 0xFF) / 255f;
        l[1] = ((rgb >> 8) & 0xFF) / 255f;
        l[2] = (rgb & 0xFF) / 255f;
        l[3] = alpha;
        l[4] = mode;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * @param dp   pixels per pt/dp (the preview passes a scaled value)
     * @param dark resolved appearance
     */
    public static GlassSpec resolve(int role, Config cfg, float dp, boolean dark, RectF rect) {
        GlassSpec s = SHARED;
        float minSide = Math.min(rect.width(), rect.height());
        boolean tile = role == ROLE_WIDGET || role == ROLE_FOLDER;

        switch (role) {
            case ROLE_PILL:
                s.radius = minSide / 2f;
                break;
            case ROLE_WIDGET:
                s.radius = cfg.widgetRadius * dp;
                break;
            case ROLE_FOLDER:
            case ROLE_ICON:
                s.radius = rect.width() * ICON_RADIUS_RATIO;
                break;
            case ROLE_DOCK:
            default:
                s.radius = cfg.dockRadius * dp;
                break;
        }
        s.radius = Math.min(s.radius, minSide / 2f);
        s.cornerPow = role == ROLE_PILL ? 2f : CORNER_POW;
        s.smoothing = role == ROLE_PILL ? 0f : ContinuousPath.IOS_SMOOTHING;

        // ---- optics (Figma GLASS, fitted) with the user's fine-tune multipliers ----
        float kRefr = cfg.refraction / 100f;
        s.refractionHeight = Math.min(REFRACTION_HEIGHT * dp, minSide / 2f);
        s.refractionAmount = REFRACTION_AMOUNT * dp * kRefr;
        s.depth = DEPTH;
        s.zoom = role == ROLE_ICON ? 1f : ZOOM;
        s.dispersion = DISPERSION * cfg.dispersion / 100f;
        s.saturation = SATURATION;
        s.brightness = BRIGHTNESS;
        float kLight = cfg.rim / 100f;
        s.lightIntensity = LIGHT * kLight;
        s.lightWidth = LIGHT_WIDTH * dp;
        s.lightSoftness = LIGHT_SOFTNESS * dp;
        s.lightFalloff = LIGHT_FALLOFF;
        double a = Math.toRadians(LIGHT_ANGLE);
        s.lightX = (float) Math.cos(a);
        s.lightY = (float) Math.sin(a);

        // ---- Figma layer stack ----
        set(s.layer1, 0, 0f, M_NONE);
        set(s.layer2, 0, 0f, M_NONE);
        set(s.layer3, 0, 0f, M_NONE);
        int style = cfg.style;
        int tint = cfg.tintColor & 0xFFFFFF;
        float glowAlpha;
        if (!tile) {
            // Dock / Search platter
            if (dark) {
                set(s.layer1, 0x111111, 0.30f, M_EXCLUSION);
            } else if (style == Config.STYLE_DEFAULT) {
                set(s.layer1, 0x999999, 0.30f, M_LUMINOSITY);
            } else {
                set(s.layer1, 0x606060, 0.30f, M_LUMINOSITY);
            }
            glowAlpha = dark && style == Config.STYLE_TINTED ? 0.30f : 0.50f;
            s.hairMode = 1f;
            s.hairColor[0] = s.hairColor[1] = s.hairColor[2] = 0x28 / 255f;
            s.hairAmount = glowAlpha;
            s.hairOffset = 1.25f * dp;
        } else {
            // Widget / folder tile ("Widget Container" / "App Icon")
            glowAlpha = 1f;
            if (style == Config.STYLE_TINTED) {
                set(s.layer1, 0x999999, 0.30f, M_EXCLUSION);
                if (dark) {
                    set(s.layer2, tint, 0.50f * 0.16f, M_NORMAL);
                    set(s.layer3, 0x1A1A1A, 0.25f, M_LINEAR_BURN);
                } else {
                    // Figma uses #5787a8 for the blue tint #24a8f1: the tint pulled halfway to grey.
                    set(s.layer2, mix(tint, 0x8A8A8A, 0.5f), 0.35f, M_LINEAR_BURN);
                }
            } else if (dark) {
                set(s.layer1, 0x999999, 0.30f, M_EXCLUSION);
                set(s.layer2, 0x2A2A2A, 0.25f, M_LINEAR_BURN);
            } else {
                set(s.layer1, 0x999999, 0.30f, M_LUMINOSITY);
            }
            s.hairMode = 2f;
            int hc = style == Config.STYLE_TINTED && dark ? tint : 0x000000;
            s.hairColor[0] = ((hc >> 16) & 0xFF) / 255f;
            s.hairColor[1] = ((hc >> 8) & 0xFF) / 255f;
            s.hairColor[2] = (hc & 0xFF) / 255f;
            s.hairAmount = 1f;
            s.hairOffset = 0.5f * dp;
        }
        s.hairSigma = Math.max(0.35f, 0.125f * dp);
        // Inner shadows: offset 0/±40, spread -40, blur 10 -> gaussian sigma = blur / 2
        s.glowAmount = (0x28 / 255f) * glowAlpha;
        s.glowOffset = 40f * dp;
        s.glowSigma = 5f * dp;
        // Parent drop shadows: offset ±0.5, Linear Burn #d0d0d0 (darken by 0.184)
        s.sideLine = 0.184f * 1.6f;
        s.sideOffset = 0.5f * dp;
        s.edgeOnly = false;

        if (role == ROLE_ICON) {
            s.edgeOnly = true;
            // Default icons: only the GLASS layer (lens + light) over the icon's own pixels.
            set(s.layer1, 0, 0f, M_NONE);
            set(s.layer2, 0, 0f, M_NONE);
            set(s.layer3, 0, 0f, M_NONE);
            s.saturation = 1f;
            s.brightness = 0f;
            s.glowAmount = 0f;
            s.hairMode = 0f;
            s.sideLine = 0f;
        }
        return s;
    }

    /** Frost (blur) of the backdrop in pt, with the user's multiplier. */
    public static float frost(Config cfg) {
        return FROST * cfg.blur / 100f;
    }
}
