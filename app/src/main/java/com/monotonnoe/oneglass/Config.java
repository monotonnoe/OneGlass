package com.monotonnoe.oneglass;

import android.content.SharedPreferences;

/**
 * All user tunables. The module UI writes them to a world-readable SharedPreferences file
 * (LSPosed "xposedsharedprefs"), the launcher side reads them with XSharedPreferences.
 *
 * Defaults come from the iOS 27 Figma kit (Home Screen - iPhone / Dock / Search Field):
 * dock 368x103pt, radius 38pt, corner smoothing 0.6, material = #999999 @33% luminosity
 * over a refracting glass layer with 1.25pt light hairlines and a 0.5pt darker outline.
 */
public final class Config {

    public static final String PREFS = "oneglass";
    public static final String ACTION_RELOAD = "com.monotonnoe.oneglass.RELOAD";
    public static final String LAUNCHER = "com.sec.android.app.launcher";

    // switches
    public static final String DOCK = "dock";
    public static final String PILL = "pill";
    public static final String WIDGETS = "widgets";
    public static final String WIDGET_CLIP = "widget_clip";
    public static final String FOLDERS = "folders";
    public static final String ICON_RIM = "icon_rim";
    public static final String DIAG = "diag";
    // material
    public static final String BLUR = "blur";
    public static final String REFRACTION = "refraction";
    public static final String REFRACTION_HEIGHT = "refraction_height";
    public static final String DISPERSION = "dispersion";
    public static final String SATURATION = "saturation";
    public static final String LUMA_MIX = "luma_mix";
    public static final String RIM = "rim";
    public static final String TINT_MODE = "tint_mode";
    public static final String TINT_ALPHA = "tint_alpha";
    // geometry
    public static final String DOCK_RADIUS = "dock_radius";
    public static final String DOCK_PAD_H = "dock_pad_h";
    public static final String DOCK_PAD_V = "dock_pad_v";
    public static final String WIDGET_RADIUS = "widget_radius";
    public static final String PILL_WIDTH = "pill_width";
    public static final String ICON_RIM_ALPHA = "icon_rim_alpha";

    public static final int TINT_AUTO = 0;
    public static final int TINT_LIGHT = 1;
    public static final int TINT_DARK = 2;

    public boolean dock = true;
    public boolean pill = true;
    public boolean widgets = true;
    public boolean widgetClip = true;
    public boolean folders = true;
    public boolean iconRim = true;
    public boolean diag = false;

    /** Frost of the backdrop, dp. */
    public int blur = 12;
    /** How far the edge pulls the backdrop, dp. */
    public int refraction = 16;
    /** Width of the refracting edge band, dp. */
    public int refractionHeight = 18;
    /** Chromatic dispersion at the edge, percent. */
    public int dispersion = 30;
    /** Backdrop vibrancy, percent (100 = unchanged). */
    public int saturation = 150;
    /** Mix of the #999999 luminosity layer from the Figma material, percent. */
    public int lumaMix = 33;
    /** Strength of the light hairlines / inner glow, percent. */
    public int rim = 100;
    public int tintMode = TINT_AUTO;
    /** Extra milky (light) or smoky (dark) tint, percent. */
    public int tintAlpha = 0;

    public int dockRadius = 38;
    public int dockPadH = 10;
    public int dockPadV = 10;
    public int widgetRadius = 26;
    /** 0 = auto. */
    public int pillWidth = 0;
    public int iconRimAlpha = 55;

    /** Bumped on every reload so cached drawings know they are stale. */
    public int generation;

    public static volatile Config current = new Config();

    public static Config read(SharedPreferences p) {
        Config d = new Config();
        Config c = new Config();
        c.dock = p.getBoolean(DOCK, d.dock);
        c.pill = p.getBoolean(PILL, d.pill);
        c.widgets = p.getBoolean(WIDGETS, d.widgets);
        c.widgetClip = p.getBoolean(WIDGET_CLIP, d.widgetClip);
        c.folders = p.getBoolean(FOLDERS, d.folders);
        c.iconRim = p.getBoolean(ICON_RIM, d.iconRim);
        c.diag = p.getBoolean(DIAG, d.diag);
        c.blur = p.getInt(BLUR, d.blur);
        c.refraction = p.getInt(REFRACTION, d.refraction);
        c.refractionHeight = p.getInt(REFRACTION_HEIGHT, d.refractionHeight);
        c.dispersion = p.getInt(DISPERSION, d.dispersion);
        c.saturation = p.getInt(SATURATION, d.saturation);
        c.lumaMix = p.getInt(LUMA_MIX, d.lumaMix);
        c.rim = p.getInt(RIM, d.rim);
        c.tintMode = p.getInt(TINT_MODE, d.tintMode);
        c.tintAlpha = p.getInt(TINT_ALPHA, d.tintAlpha);
        c.dockRadius = p.getInt(DOCK_RADIUS, d.dockRadius);
        c.dockPadH = p.getInt(DOCK_PAD_H, d.dockPadH);
        c.dockPadV = p.getInt(DOCK_PAD_V, d.dockPadV);
        c.widgetRadius = p.getInt(WIDGET_RADIUS, d.widgetRadius);
        c.pillWidth = p.getInt(PILL_WIDTH, d.pillWidth);
        c.iconRimAlpha = p.getInt(ICON_RIM_ALPHA, d.iconRimAlpha);
        return c;
    }

    public void write(SharedPreferences.Editor e) {
        e.putBoolean(DOCK, dock)
                .putBoolean(PILL, pill)
                .putBoolean(WIDGETS, widgets)
                .putBoolean(WIDGET_CLIP, widgetClip)
                .putBoolean(FOLDERS, folders)
                .putBoolean(ICON_RIM, iconRim)
                .putBoolean(DIAG, diag)
                .putInt(BLUR, blur)
                .putInt(REFRACTION, refraction)
                .putInt(REFRACTION_HEIGHT, refractionHeight)
                .putInt(DISPERSION, dispersion)
                .putInt(SATURATION, saturation)
                .putInt(LUMA_MIX, lumaMix)
                .putInt(RIM, rim)
                .putInt(TINT_MODE, tintMode)
                .putInt(TINT_ALPHA, tintAlpha)
                .putInt(DOCK_RADIUS, dockRadius)
                .putInt(DOCK_PAD_H, dockPadH)
                .putInt(DOCK_PAD_V, dockPadV)
                .putInt(WIDGET_RADIUS, widgetRadius)
                .putInt(PILL_WIDTH, pillWidth)
                .putInt(ICON_RIM_ALPHA, iconRimAlpha);
    }
}
