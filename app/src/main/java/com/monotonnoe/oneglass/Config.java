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

    public static final String PREFS = "oneglass2";
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
    public static final String IOS_GRID = "ios_grid_v3";
    public static final String IOS_ICONS = "ios_icons";
    public static final String IOS_ICON_SIZE = "ios_icon_size";
    public static final String IOS_LABELS = "ios_labels";
    public static final String FOLDER_PANEL = "folder_panel";
    public static final String LIGHT_TILT = "light_tilt";
    public static final String HIDE_BADGES = "hide_badges";
    public static final String NO_DIM = "no_dim";
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
    public static final String STYLE = "style";
    public static final String TINT_COLOR = "tint_color";
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

    /** iOS 27 home screen styles (Figma Playground variants). */
    public static final int STYLE_DEFAULT = 0;
    public static final int STYLE_CLEAR = 1;
    public static final int STYLE_TINTED = 2;

    public boolean dock = true;
    public boolean pill = true;
    public boolean widgets = true;
    public boolean widgetClip = true;
    public boolean folders = true;
    public boolean iconRim = true;
    public boolean diag = false;
    /** Re-lay the home grid, widgets and dock on the iOS 27 grid from the Figma kit. */
    public boolean iosGrid = true;
    /** iOS 27 icon shape: continuous-corner squircle, Figma 15.5 / 60pt. */
    public boolean iosIcons = true;
    /** iOS 27 icon size: 60pt. */
    public boolean iosIconSize = true;
    /** iOS labels: 12pt medium, white, soft shadow. */
    public boolean iosLabels = true;
    /** Glass behind the opened folder panel. */
    public boolean folderPanel = true;
    /** The glass highlight follows the phone tilt (iOS 26/27). */
    public boolean lightTilt = true;
    /** iOS has no work-profile / clone badges on icons (BlurIcons: hide badges). */
    public boolean hideBadges = true;
    /** No One UI dimming of icons (BlurIcons: dim effect off). */
    public boolean noDim = true;

    /** iOS style of the glass: Default, Clear or Tinted (Figma Playground). */
    public int style = STYLE_CLEAR;
    /** Light / dark appearance. */
    public int tintMode = TINT_AUTO;
    /** Tint colour for the Tinted style (Figma: #24A8F1). */
    public int tintColor = 0xFF24A8F1;

    // Fine-tune multipliers, percent of the values measured from the Figma kit (100 = 1:1).
    public int blur = 100;
    public int refraction = 100;
    public int dispersion = 100;
    public int rim = 100;
    // Legacy keys kept so old settings files still parse.
    public int refractionHeight = 100;
    public int saturation = 100;
    public int lumaMix = 100;
    public int tintAlpha = 0;

    /** Figma: Dock 357x99, corner radius 35. */
    public int dockRadius = 35;
    public int dockPadH = 0;
    public int dockPadV = 0;
    /** Figma: widget 158x158, corner radius 27. */
    public int widgetRadius = 27;
    /** 0 = auto. */
    public int pillWidth = 0;
    public int iconRimAlpha = 100;

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
        c.iosGrid = p.getBoolean(IOS_GRID, d.iosGrid);
        c.iosIcons = p.getBoolean(IOS_ICONS, d.iosIcons);
        c.iosIconSize = p.getBoolean(IOS_ICON_SIZE, d.iosIconSize);
        c.iosLabels = p.getBoolean(IOS_LABELS, d.iosLabels);
        c.folderPanel = p.getBoolean(FOLDER_PANEL, d.folderPanel);
        c.lightTilt = p.getBoolean(LIGHT_TILT, d.lightTilt);
        c.hideBadges = p.getBoolean(HIDE_BADGES, d.hideBadges);
        c.noDim = p.getBoolean(NO_DIM, d.noDim);
        c.blur = p.getInt(BLUR, d.blur);
        c.refraction = p.getInt(REFRACTION, d.refraction);
        c.refractionHeight = p.getInt(REFRACTION_HEIGHT, d.refractionHeight);
        c.dispersion = p.getInt(DISPERSION, d.dispersion);
        c.saturation = p.getInt(SATURATION, d.saturation);
        c.lumaMix = p.getInt(LUMA_MIX, d.lumaMix);
        c.rim = p.getInt(RIM, d.rim);
        c.tintMode = p.getInt(TINT_MODE, d.tintMode);
        c.tintAlpha = p.getInt(TINT_ALPHA, d.tintAlpha);
        c.style = p.getInt(STYLE, d.style);
        c.tintColor = p.getInt(TINT_COLOR, d.tintColor);
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
                .putBoolean(IOS_GRID, iosGrid)
                .putBoolean(IOS_ICONS, iosIcons)
                .putBoolean(IOS_ICON_SIZE, iosIconSize)
                .putBoolean(IOS_LABELS, iosLabels)
                .putBoolean(FOLDER_PANEL, folderPanel)
                .putBoolean(LIGHT_TILT, lightTilt)
                .putBoolean(HIDE_BADGES, hideBadges)
                .putBoolean(NO_DIM, noDim)
                .putInt(BLUR, blur)
                .putInt(REFRACTION, refraction)
                .putInt(REFRACTION_HEIGHT, refractionHeight)
                .putInt(DISPERSION, dispersion)
                .putInt(SATURATION, saturation)
                .putInt(LUMA_MIX, lumaMix)
                .putInt(RIM, rim)
                .putInt(TINT_MODE, tintMode)
                .putInt(TINT_ALPHA, tintAlpha)
                .putInt(STYLE, style)
                .putInt(TINT_COLOR, tintColor)
                .putInt(DOCK_RADIUS, dockRadius)
                .putInt(DOCK_PAD_H, dockPadH)
                .putInt(DOCK_PAD_V, dockPadV)
                .putInt(WIDGET_RADIUS, widgetRadius)
                .putInt(PILL_WIDTH, pillWidth)
                .putInt(ICON_RIM_ALPHA, iconRimAlpha);
    }
}
