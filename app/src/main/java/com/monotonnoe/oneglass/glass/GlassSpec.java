package com.monotonnoe.oneglass.glass;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RectF;

import com.monotonnoe.oneglass.Config;

/** Resolved, pixel-space parameters of one glass surface. */
public final class GlassSpec {

    public static final int ROLE_DOCK = 1;
    public static final int ROLE_PILL = 2;
    public static final int ROLE_WIDGET = 3;
    public static final int ROLE_FOLDER = 4;

    /** Folder icons follow the iOS app icon ratio (radius = 22.37% of the side). */
    public static final float ICON_RADIUS_RATIO = 0.2237f;

    public float radius;
    /** 2 = circular corners, higher = continuous-looking corners in the shader. */
    public float cornerPow;
    public float smoothing;
    public float refractionHeight;
    public float refractionAmount;
    public float dispersion;
    public float saturation;
    public float lumaTarget;
    public float lumaMix;
    public final float[] tint = new float[4];
    public float rimWidth;
    public float rimStrength;
    public float glowBand;
    public float glowStrength;
    public float lightX;
    public float lightY;
    public float outline;

    private static final GlassSpec SHARED = new GlassSpec();

    /** Main-thread only: returns a shared instance. */
    public static GlassSpec resolve(int role, Config cfg, Resources res, RectF rect) {
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return resolve(role, cfg, res.getDisplayMetrics().density, night, rect);
    }

    /** @param dp pixels per dp (the preview passes a scaled value) */
    public static GlassSpec resolve(int role, Config cfg, float dp, boolean night, RectF rect) {
        GlassSpec s = SHARED;
        boolean dark = cfg.tintMode == Config.TINT_DARK || (cfg.tintMode == Config.TINT_AUTO && night);
        float minSide = Math.min(rect.width(), rect.height());

        switch (role) {
            case ROLE_PILL:
                s.radius = minSide / 2f;
                s.cornerPow = 2f;
                break;
            case ROLE_WIDGET:
                s.radius = cfg.widgetRadius * dp;
                s.cornerPow = 2.6f;
                break;
            case ROLE_FOLDER:
                s.radius = rect.width() * ICON_RADIUS_RATIO;
                s.cornerPow = 2.6f;
                break;
            case ROLE_DOCK:
            default:
                s.radius = cfg.dockRadius * dp;
                s.cornerPow = 2.6f;
                break;
        }
        s.radius = Math.min(s.radius, minSide / 2f);
        s.smoothing = role == ROLE_PILL ? 0f : ContinuousPath.IOS_SMOOTHING;

        // Small surfaces (folder icons, pill) get a proportionally thinner lens.
        float scale = Math.min(1f, minSide / (64f * dp));
        s.refractionHeight = Math.min(cfg.refractionHeight * dp * Math.max(scale, 0.45f), minSide / 2f);
        s.refractionAmount = cfg.refraction * dp * Math.max(scale, 0.45f);
        s.dispersion = cfg.dispersion / 100f;
        s.saturation = cfg.saturation / 100f;

        // Figma: "Fill + Shadow" = #999999 at 33% in LUMINOSITY blend. Dark mode uses a deep grey.
        s.lumaTarget = dark ? 0.20f : 0.60f;
        s.lumaMix = cfg.lumaMix / 100f;
        float t = dark ? 0f : 1f;
        s.tint[0] = t;
        s.tint[1] = t;
        s.tint[2] = t;
        s.tint[3] = cfg.tintAlpha / 100f;

        // Figma: 1.25pt LINEAR_DODGE #282828 hairlines + 10pt soft inner glow on top / bottom.
        float rimK = cfg.rim / 100f;
        s.rimWidth = 1.25f * dp;
        s.rimStrength = 0.38f * rimK * (dark ? 0.7f : 1f);
        s.glowBand = Math.max(2f * dp, Math.min(10f * dp, minSide / 6f));
        s.glowStrength = 0.16f * rimK * (dark ? 0.6f : 1f);
        double a = Math.toRadians(78.0);
        s.lightX = (float) Math.cos(a);
        s.lightY = (float) Math.sin(a);
        // Figma: 0.5pt LINEAR_BURN #cccccc outline (~20% darkening).
        s.outline = dark ? 0.28f : 0.18f;
        return s;
    }
}
