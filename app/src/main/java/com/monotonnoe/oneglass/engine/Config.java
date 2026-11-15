package com.monotonnoe.oneglass.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Tunables, read once per launcher start from {@code <launcher data>/files/bluricons.conf}
 * (java.util.Properties format). Every key is optional; see README for the list.
 */
final class Config {
    /** The module version, as reported by the launcher (keep in step with build-apk.sh). */
    static final String VERSION = "7.1";

    /**
     * Diagnostics in the LSPosed log: the lock screen's card traces, card and window geometry,
     * per-event lines. Off in releases (they filled the log export, megabytes a day); errors
     * and the one-time install and summary lines are logged either way. Logging only.
     */
    static final boolean DEBUG = false;

    /** Bump when the glass bitmap recipe changes so cached launcher icons are rebuilt. */
    private static final int RECIPE_VERSION = 11;

    /**
     * Replace app icons with the module's own glass icons. Off: the icons stay exactly as the
     * icon pack draws them (with its own glass and highlights) and only get the live blur.
     */
    static boolean glassIcons = false;
    static boolean blur = true;
    /** Also blur behind icons in the Apps screen (its own background already dims the wallpaper). */
    static boolean drawer = true;

    static int blurRadius = 150;
    /**
     * Live blur outline when the icon's own outline cannot be measured: a rounded rect inset by
     * this fraction of the icon size, with this corner radius as a fraction of its width. Best
     * fit to One UI's superellipse.
     */
    static float blurInset = 0.0148f;
    static float corner = 0.386f;
    /** The blur sits this fraction of the icon size inside the icon's measured outline. */
    static float outlineInset = 0.006f;
    /**
     * Liquid glass, as fractions of the glass size: the lens band along the edge and how far it
     * pulls the picture in (AndroidLiquidGlass refractionHeight and refractionAmount), the blur
     * radius, the saturation boost (vibrancy) and the depth effect.
     */
    static float lensHeight = 0.22f;
    static float lensAmount = 0.4f;
    static boolean lensDepth = true;
    /** Frosted like iOS 26 icons: enough to soften the picture, not to erase the lens. */
    static float glassBlur = 0.03f;
    static float glassSaturation = 1.5f;
    /** Liquid glass also under folder icons and app widgets on the Home screen. */
    static boolean glassFolders = true;
    static boolean glassWidgets = true;
    /** Blur of the glass in the Apps screen, over its own blurred wallpaper. */
    static float drawerBlur = 0.3f;
    /** The search bars (Apps screen, Home screen) as gray liquid glass. */
    static boolean glassSearch = true;
    /** How gray the search bar glass is: the opacity of its gray layer. */
    static float searchGray = 0.7f;
    /** Search bar glass: blur (of its height), saturation and lens pull. */
    static float searchBlur = 0.3f;
    static float searchSaturation = 0.35f;
    static float searchLens = 0.4f;
    /** Apps screen icon glass: lens band and pull, saturation, and its own tint alpha (0: Home's). */
    static float drawerLensHeight = 0.22f;
    static float drawerLensAmount = 0.4f;
    static float drawerSaturation = 1.5f;
    static int drawerTint = 0;
    /** Open folders: glass under their icons (with this blur) and as their panel. */
    static boolean glassOpenFolder = true;
    static float folderBlur = 0.3f;
    static boolean glassFolderPanel = true;
    /** Folder panel tint alpha, and its lens band and pull (of the panel's short side). */
    static int folderPanelTint = 51;
    static float folderPanelLens = 0.06f;
    /** Widgets clipped to iPhone widget corners (continuous, 0.137 of a small widget). */
    static boolean iosWidgetCorners = true;
    /** The lit rim and the dark hairline around the glass (ionic-theme-ios27), 0..1. */
    static float glassRim = 1f;
    static float glassHairline = 0.5f;
    /** The light glare over photo widgets (Gallery, Google Photos), 0..1. */
    static float galleryGlare = 0f;
    /** The photo widget's colour (GalleryLook.FULL, MONO, TINTED) and its spatial motion. */
    static int galleryLook = 0;
    static boolean gallerySpatial = true;
    /** Tinted gallery photos: how far the lights go from the tint towards white. */
    static float galleryLight = 0.88f;
    /** Glass icon packs: take their own glass away and keep the glyph. */
    static boolean packPeel = true;
    /** No Secure Folder or work profile badge in the corner of icons. */
    static boolean hideBadges = true;
    /** Frames, glass records and sync time in the settings status, every 5 s of drawing. */
    static boolean loadReadout = false;
    /** The lock screen clock as liquid glass (System UI), its refraction and tint, percent. */
    static boolean glassClock = true;
    static int clockRefraction = 163;
    static int clockTint = 10;
    /** Clock glass blur and saturation, white frost, bevel (of the stroke), highlight; %. */
    static int clockBlur = 1;
    static int clockSaturation = 95;
    static int clockFrost = 20;
    static int clockBevel = 14;
    static int clockHighlight = 5;
    /**
     * The clock as iOS 27 glass (ionic-theme-ios27) over the iOS 26 liquid glass: the frosted
     * layer's opacity, its lit rim and its dark hairline, percent.
     */
    static boolean clockIos27 = true;
    static int clockIos27Layer = 49;
    static int clockRim = 93;
    static int clockHairline = 58;
    /** Lock screen notifications as blurred glass cards: on, corners dp, shade %, blur px. */
    static boolean lockNotifGlass = true;
    static int lockNotifRadius = 24;
    static boolean lockNotifBottom = true;
    static boolean lockNotifPile = true;
    /** How far the glass rims' light follows the phone's tilt (0 keeps it still). */
    static float glassLight = 0f;
    /** The glass condenses in as it appears: its lens swells from a quarter, then settles. */
    static boolean glassCondense = true;
    /** An open folder's glass grows out of the folder icon's glass, as iOS's folder. */
    static boolean folderMorph = true;
    /** The lock screen clock as iOS 26's glass (LockGlass.IOS26), and in the chosen font. */
    static boolean clockIos26 = true;
    static boolean clockFont;
    static int lockNotifShade = 15;
    static int lockNotifBlur = 6;
    static int lockNotifRim = 50;
    static int lockNotifHairline = 0;
    static int lockNotifGlow = 20;
    /** Refraction at the cards' edge, %; 0 keeps the compositor's blur. */
    static int lockNotifRefraction = 60;
    static int lockNotifSheen = 0;
    /** 0 the iOS card, 1 react-glass-rim's glass card. */
    static int lockNotifStyle = 1;
    /** react-glass-rim's controls: rim colour, rings, band (0.1 dp), corner shadow (×0.1), border (0.1 dp), border colour. */
    static final int[] lockNotifRimExtra = {0, 1, 8, 30, 0, 3};
    /** Optional ARGB tint the compositor mixes into the blur. */
    static int blurColor = 0x00000000;

    /** Frosted fill painted inside the icon shape, vertical gradient top -> bottom. */
    static int tintTop = 0x1FFFFFFF;
    static int tintBottom = 0x0AFFFFFF;
    /** Specular rim: width as a fraction of the icon size and peak opacity. */
    static float rimWidth = 0.014f;
    static float rimAlpha = 0.70f;
    /** Foreground layer scale relative to the standard adaptive-icon viewport. */
    static float fgScale = 1.0f;
    /** 0 = original colours, 1 = white glyph (monochrome layer if the app has one). */
    static boolean mono = true;
    /** Opacity of the glyph in mono mode. */
    static float monoAlpha = 0.92f;
    /** Glass for legacy (non-adaptive) icons, and their opacity. */
    static boolean legacyGlass = true;
    static float legacyAlpha = 0.6f;

    private Config() {
    }

    static void load(File file) {
        if (!file.isFile()) {
            XLog.i("no " + file + ", using defaults");
            return;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Throwable t) {
            XLog.e("could not read " + file, t);
            return;
        }
        glassIcons = bool(p, "glass_icons", glassIcons);
        blur = bool(p, "blur", blur);
        drawer = bool(p, "drawer", drawer);
        blurRadius = integer(p, "blur_radius", blurRadius);
        corner = number(p, "corner", corner);
        blurInset = number(p, "blur_inset", blurInset);
        outlineInset = number(p, "outline_inset", outlineInset);
        lensHeight = number(p, "lens_height", lensHeight);
        lensAmount = number(p, "lens_amount", lensAmount);
        lensDepth = bool(p, "lens_depth", lensDepth);
        glassBlur = number(p, "glass_blur", glassBlur);
        glassSaturation = number(p, "glass_saturation", glassSaturation);
        blurColor = color(p, "blur_color", blurColor);
        tintTop = color(p, "tint_top", tintTop);
        tintBottom = color(p, "tint_bottom", tintBottom);
        rimWidth = number(p, "rim_width", rimWidth);
        rimAlpha = number(p, "rim_alpha", rimAlpha);
        fgScale = number(p, "fg_scale", fgScale);
        mono = bool(p, "mono", mono);
        monoAlpha = number(p, "mono_alpha", monoAlpha);
        legacyGlass = bool(p, "legacy_glass", legacyGlass);
        legacyAlpha = number(p, "legacy_alpha", legacyAlpha);
        XLog.i("config loaded: " + signature());
    }

    /** Everything that ends up baked into cached icon bitmaps. */
    static String signature() {
        return "r" + RECIPE_VERSION
                + " glass=" + glassIcons
                + " tint=" + Integer.toHexString(tintTop) + "/" + Integer.toHexString(tintBottom)
                + " rim=" + rimWidth + "/" + rimAlpha
                + " fg=" + fgScale
                + " mono=" + mono + "/" + monoAlpha
                + " legacy=" + legacyGlass + "/" + legacyAlpha
                + " peel=" + packPeel
                + " badges=" + hideBadges;
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    private static int integer(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float number(Properties p, String key, float def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int color(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            if (v == null) return def;
            v = v.trim();
            if (v.startsWith("#")) v = v.substring(1);
            if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2);
            return (int) Long.parseLong(v, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
