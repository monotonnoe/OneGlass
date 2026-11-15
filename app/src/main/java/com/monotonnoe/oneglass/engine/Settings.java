package com.monotonnoe.oneglass.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Settings shared between the module's settings screen and the launcher process.
 *
 * <p>The module app owns the values ({@link SettingsProvider}) and announces changes with
 * {@link #ACTION_CHANGED}. The background opacity, the style and the glass tuning apply
 * instantly. The foreground colour is baked into One UI Home's icon cache, so the launcher keeps a
 * local copy of it (readable before its Application exists, where the cache is reset) and
 * restarts itself when it changes.
 */
final class Settings {
    static final String AUTHORITY = "com.monotonnoe.oneglass.engine.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    /** The picture the liquid glass refracts when the wallpaper is live (only for the launcher). */
    static final Uri PICTURE_URI = Uri.parse("content://" + AUTHORITY + "/picture");
    static final String ACTION_CHANGED = "com.monotonnoe.oneglass.engine.SETTINGS_CHANGED";
    static final String LAUNCHER = "com.sec.android.app.launcher";

    static final String KEY_OPACITY = "background_opacity";
    static final String KEY_WHITE = "white_foreground";
    static final String KEY_STYLE = "style";
    /** The glass colour (RGB), or 0 for the automatic light or dark tone. */
    static final String KEY_TINT_COLOR = "tint_color";
    /** Broadcast extra: the picture for live wallpapers was chosen or removed. */
    static final String KEY_PICTURE = "picture";
    /** What the launcher's liquid glass is doing, reported back to the settings screen. */
    static final String KEY_STATUS = "glass_status";
    /**
     * What System UI found on the lock screen, as a global setting: System UI cannot see the
     * module's provider, but may write global settings, which the settings screen can read.
     */
    static final String GLOBAL_LOCK_STATUS = "oneglass_lock_status";

    /**
     * Liquid glass tuning, applied instantly: blur, lens band and pull (percent of the glass
     * size), saturation (percent), and the depth effect, folders and widgets switches (0/1).
     */
    static final String[] GLASS_KEYS = {"glass_blur", "lens_height", "lens_amount",
            "glass_saturation", "lens_depth", "glass_folders", "glass_widgets", "glass_drawer",
            "drawer_blur", "glass_search", "search_gray", "glass_clock", "clock_refraction",
            "clock_tint", "clock_blur", "clock_saturation", "clock_frost", "clock_bevel",
            "clock_highlight", "drawer_lens_height", "drawer_lens_amount", "drawer_saturation",
            "drawer_tint", "glass_open_folder", "folder_blur", "glass_folder_panel",
            "folder_panel_tint", "folder_panel_lens", "search_blur", "search_saturation",
            "search_lens", "widget_ios_corners", "glass_rim", "glass_hairline",
            "clock_ios27", "clock_ios27_layer", "clock_rim", "clock_hairline",
            "gallery_glare", "gallery_look", "gallery_spatial", "gallery_light", "pack_peel", "hide_badges", "load_readout",
            "lock_notif_glass", "lock_notif_radius", "lock_notif_shade", "lock_notif_blur",
            "lock_notif_rim", "lock_notif_hairline", "lock_notif_glow",
            "lock_notif_refraction", "lock_notif_sheen",
            "lock_notif_style", "lock_notif_rim_color", "lock_notif_rings", "lock_notif_band",
            "lock_notif_corner_shadow", "lock_notif_border", "lock_notif_border_color",
            "lock_notif_bottom", "lock_hide_fp_icon", "lock_notif_pile", "glass_light",
            "glass_condense", "folder_morph", "clock_ios26", "clock_font"};
    static final int[] GLASS_DEFAULTS = {1, 22, 40, 150, 1, 1, 1, 1, 30, 1, 39, 1, 163, 10, 1, 95, 20, 14, 5, 22, 40, 150, 0, 0, 30, 1, 20, 6, 51, 35, 40, 1, 100, 50, 1, 49, 93, 58, 14, 2, 1, 150, 0, 1, 0, 1, 24, 15, 6, 50, 0, 0, 60, 0, 1, 0, 1, 8, 30, 0, 3, 1, 1, 1, 0, 1, 1, 1, 0};
    static final int[] GLASS_MIN = {0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    static final int[] GLASS_MAX = {20, 50, 100, 250, 1, 1, 1, 1, 60, 1, 100, 1, 200, 100, 40, 200, 100, 80, 150, 50, 100, 250, 100, 1, 60, 1, 100, 30, 60, 150, 100, 1, 100, 100, 1, 100, 100, 100, 100, 2, 1, 400, 1, 1, 1, 1, 30, 40, 60, 100, 100, 100, 100, 100, 1, 5, 6, 20, 30, 30, 5, 1, 1, 1, 100, 1, 1, 1, 1};
    static final int GLASS_BLUR = 0;
    static final int LENS_HEIGHT = 1;
    static final int LENS_AMOUNT = 2;
    static final int GLASS_SATURATION = 3;
    static final int LENS_DEPTH = 4;
    static final int GLASS_FOLDERS = 5;
    static final int GLASS_WIDGETS = 6;
    static final int GLASS_DRAWER = 7;
    static final int DRAWER_BLUR = 8;
    static final int GLASS_SEARCH = 9;
    static final int SEARCH_GRAY = 10;
    static final int GLASS_CLOCK = 11;
    static final int CLOCK_REFRACTION = 12;
    static final int CLOCK_TINT = 13;
    static final int CLOCK_BLUR = 14;
    static final int CLOCK_SATURATION = 15;
    static final int CLOCK_FROST = 16;
    static final int CLOCK_BEVEL = 17;
    static final int CLOCK_HIGHLIGHT = 18;
    static final int DRAWER_LENS_HEIGHT = 19;
    static final int DRAWER_LENS_AMOUNT = 20;
    static final int DRAWER_SATURATION = 21;
    static final int DRAWER_TINT = 22;
    static final int GLASS_OPEN_FOLDER = 23;
    static final int FOLDER_BLUR = 24;
    static final int GLASS_FOLDER_PANEL = 25;
    static final int FOLDER_PANEL_TINT = 26;
    static final int FOLDER_PANEL_LENS = 27;
    static final int SEARCH_BLUR = 28;
    static final int SEARCH_SATURATION = 29;
    static final int SEARCH_LENS = 30;
    static final int WIDGET_IOS_CORNERS = 31;
    static final int GLASS_RIM = 32;
    static final int GLASS_HAIRLINE = 33;
    static final int CLOCK_IOS27 = 34;
    static final int CLOCK_IOS27_LAYER = 35;
    static final int CLOCK_RIM = 36;
    static final int CLOCK_HAIRLINE = 37;
    static final int GALLERY_GLARE = 38;
    static final int GALLERY_LOOK = 39;
    static final int GALLERY_SPATIAL = 40;
    static final int GALLERY_LIGHT = 41;
    static final int PACK_PEEL = 42;
    static final int HIDE_BADGES = 43;
    static final int LOAD_READOUT = 44;
    static final int LOCK_NOTIF_GLASS = 45;
    static final int LOCK_NOTIF_RADIUS = 46;
    static final int LOCK_NOTIF_SHADE = 47;
    static final int LOCK_NOTIF_BLUR = 48;
    static final int LOCK_NOTIF_RIM = 49;
    static final int LOCK_NOTIF_HAIRLINE = 50;
    static final int LOCK_NOTIF_GLOW = 51;
    static final int LOCK_NOTIF_REFRACTION = 52;
    static final int LOCK_NOTIF_SHEEN = 53;
    static final int LOCK_NOTIF_STYLE = 54;
    static final int LOCK_NOTIF_RIM_COLOR = 55;
    static final int LOCK_NOTIF_RINGS = 56;
    static final int LOCK_NOTIF_BAND = 57;
    static final int LOCK_NOTIF_CORNER_SHADOW = 58;
    static final int LOCK_NOTIF_BORDER = 59;
    static final int LOCK_NOTIF_BORDER_COLOR = 60;
    /** The lock screen's notifications at the bottom of the screen, as iOS's. */
    static final int LOCK_NOTIF_BOTTOM = 61;
    /** The fingerprint sensor's icon hidden on the lock screen (FingerprintIcon). */
    static final int LOCK_HIDE_FP_ICON = 62;
    /** The lock screen's cards piled up as iOS's stack (LockBottom). */
    static final int LOCK_NOTIF_PILE = 63;
    /** How far the glass rims' light follows the phone's tilt, percent (GlassLight). */
    static final int GLASS_LIGHT = 64;
    /** The glass condenses in (its lens swells, then settles) as it appears. */
    static final int GLASS_CONDENSE = 65;
    /** An open folder's glass grows out of the folder icon's glass. */
    static final int FOLDER_MORPH = 66;
    /** The lock screen clock as iOS 26's glass, and in the font chosen for the cards. */
    static final int CLOCK_IOS26 = 67;
    static final int CLOCK_FONT = 68;
    /** The removed ionic glass theme's key (it was GLASS_KEYS' last). */
    static final String REMOVED_IONIC = "glass_ionic";
    /** The colours to pick for the rim and the border, as react-glass-rim's playground offers. */
    static final int[] RIM_COLORS = {0xFFFFFF, 0xB4D2FF, 0xFFE0B8, 0xB69BFF, 0xFFB4D8, 0xB8F5DC};

    /** Liquid glass (refraction of the wallpaper), or the plain compositor blur. */
    static final int STYLE_LIQUID = 0;
    static final int STYLE_BLUR = 1;
    static final int DEFAULT_STYLE = STYLE_LIQUID;

    /**
     * Off, then Glass, Light, Medium, Solid: the same alphas F1Live (twidget) uses over Samsung
     * blur. Off by default: icon packs in the clear style bring their own glass.
     */
    static final int[] OPACITY_PRESETS = {0, 38, 102, 178, 240};
    /** Glass, as up to 6.88 (6.89 to 7.0: 28, iOS 27's 11 % white veil). */
    static final int DEFAULT_OPACITY = 38;
    static final boolean DEFAULT_WHITE = true;
    /** The glass colour to start with: iOS's grey, as on the author's phone. */
    static final int DEFAULT_TINT = 0xFF8E8E93;

    /** Alpha of the tone colour painted over the live blur, inside the icon shape. */
    static volatile int backgroundAlpha = DEFAULT_OPACITY;
    static volatile int style = DEFAULT_STYLE;
    static volatile int tintColor = DEFAULT_TINT;
    /** The launcher's current {@link #GLASS_KEYS} values. */
    private static final int[] glass = GLASS_DEFAULTS.clone();

    private static File localFile;
    private static boolean installed;

    private Settings() {
    }

    /** Launcher side, before the Application starts: the last values this launcher saw. */
    /**
     * One-time changes to stored settings: 5.66 turns the glass under the icons of open
     * folders off, it looked wrong there.
     */
    static void migrate(android.content.SharedPreferences p) {
        // The "ionic" glass theme is gone: its value (glass_ionic, once the last of
        // GLASS_KEYS) is dropped. Settings files that still carry it import as before: keys
        // are read by name, an unknown one is skipped.
        if (p.contains(REMOVED_IONIC)) p.edit().remove(REMOVED_IONIC).apply();
        // 6.89: the Home screen's glass as iOS 27's Clear icons, measured on an iPhone's
        // screenshot: a wide lens band, a light blur, no extra saturation, the white veil, a
        // dark hairline on the sides and a static light from the top.
        if (!p.getBoolean("migrated_689", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            for (int i : new int[]{GLASS_BLUR, LENS_HEIGHT, LENS_AMOUNT, GLASS_SATURATION,
                    GLASS_RIM, GLASS_HAIRLINE, GLASS_LIGHT}) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putInt(KEY_OPACITY, DEFAULT_OPACITY).putInt(KEY_TINT_COLOR, DEFAULT_TINT)
                    .putBoolean("migrated_689", true).apply();
        }
        // migrated_701: the glass itself back to 6.88's (its lens, blur, saturation, tone and
        // colour); 6.89's light stays (its rim, hairline and still light are not touched here).
        if (!p.getBoolean("migrated_701", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            for (int i : new int[]{GLASS_BLUR, LENS_HEIGHT, LENS_AMOUNT, GLASS_SATURATION}) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putInt(KEY_OPACITY, DEFAULT_OPACITY).putInt(KEY_TINT_COLOR, DEFAULT_TINT)
                    .putBoolean("migrated_701", true).apply();
        }
        if (!p.getBoolean("migrated_566", false)) {
            p.edit().putInt(GLASS_KEYS[GLASS_OPEN_FOLDER], 0).putBoolean("migrated_566", true)
                    .apply();
        }
        // 5.93: the lock screen notification cards set once to the iPhone's look.
        if (!p.getBoolean("migrated_593", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            for (int i = LOCK_NOTIF_RADIUS; i <= LOCK_NOTIF_GLOW; i++) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putBoolean("migrated_593", true).apply();
        }
        // 6.00: the cards measured against an iPhone's: the rim, hairline, glow and sheen.
        if (!p.getBoolean("migrated_600", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_RIM], GLASS_DEFAULTS[LOCK_NOTIF_RIM])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_HAIRLINE], GLASS_DEFAULTS[LOCK_NOTIF_HAIRLINE])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_GLOW], GLASS_DEFAULTS[LOCK_NOTIF_GLOW])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_SHEEN], GLASS_DEFAULTS[LOCK_NOTIF_SHEEN])
                    .putBoolean("migrated_600", true).apply();
        }
        // 6.01: the cards set once to react-glass-rim's glass card.
        if (!p.getBoolean("migrated_601", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            for (int i = LOCK_NOTIF_RADIUS; i <= LOCK_NOTIF_STYLE; i++) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putBoolean("migrated_601", true).apply();
        }
        // 6.40: an open folder's panel is glass, as iOS's folders are (now on whether or not
        // its icons have glass).
        if (!p.getBoolean("migrated_640", false)) {
            p.edit().putInt(GLASS_KEYS[GLASS_FOLDER_PANEL], GLASS_DEFAULTS[GLASS_FOLDER_PANEL])
                    .putBoolean("migrated_640", true).apply();
        }
        // 6.39: 6.38's iOS 27 edge undone; who had it gets the diagonal rim's values back.
        if (p.getBoolean("migrated_638", false) && !p.getBoolean("migrated_639", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_RIM], GLASS_DEFAULTS[LOCK_NOTIF_RIM])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_HAIRLINE], GLASS_DEFAULTS[LOCK_NOTIF_HAIRLINE])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_GLOW], GLASS_DEFAULTS[LOCK_NOTIF_GLOW])
                    .putBoolean("migrated_639", true).apply();
        }
        // 6.19: a denser milkiness, nearer iOS's material (15 %).
        if (!p.getBoolean("migrated_619", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_SHADE], GLASS_DEFAULTS[LOCK_NOTIF_SHADE])
                    .putBoolean("migrated_619", true).apply();
        }
        // 6.16: the shade setting is the glass's milkiness now (white, 8 %).
        if (!p.getBoolean("migrated_616", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_SHADE], GLASS_DEFAULTS[LOCK_NOTIF_SHADE])
                    .putBoolean("migrated_616", true).apply();
        }
        // 6.11: the blur as light as iOS's (a shape's edge stays visible through the card, so
        // the edge's refraction shows).
        if (!p.getBoolean("migrated_611", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_BLUR], GLASS_DEFAULTS[LOCK_NOTIF_BLUR])
                    .putBoolean("migrated_611", true).apply();
        }
        // 6.10: the edge refracts through the blur again, as iOS's cards do.
        if (!p.getBoolean("migrated_610", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_REFRACTION],
                    GLASS_DEFAULTS[LOCK_NOTIF_REFRACTION]).putBoolean("migrated_610", true).apply();
        }
        // 6.06: the card as measured on iOS 26: the rim 0.5 at its lit corners, gone at the
        // other two, one 0.8 dp line.
        if (!p.getBoolean("migrated_606", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_RIM], GLASS_DEFAULTS[LOCK_NOTIF_RIM])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_RINGS], GLASS_DEFAULTS[LOCK_NOTIF_RINGS])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_BAND], GLASS_DEFAULTS[LOCK_NOTIF_BAND])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_CORNER_SHADOW],
                            GLASS_DEFAULTS[LOCK_NOTIF_CORNER_SHADOW])
                    .putBoolean("migrated_606", true).apply();
        }
        // 6.05: the rim as react-glass-rim's project picture (og.svg): 0.9 at the corners,
        // one 1.2 dp line.
        if (!p.getBoolean("migrated_605", false)) {
            p.edit().putInt(GLASS_KEYS[LOCK_NOTIF_RIM], GLASS_DEFAULTS[LOCK_NOTIF_RIM])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_RINGS], GLASS_DEFAULTS[LOCK_NOTIF_RINGS])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_BAND], GLASS_DEFAULTS[LOCK_NOTIF_BAND])
                    .putInt(GLASS_KEYS[LOCK_NOTIF_CORNER_SHADOW],
                            GLASS_DEFAULTS[LOCK_NOTIF_CORNER_SHADOW])
                    .putBoolean("migrated_605", true).apply();
        }
        // 6.04: only react-glass-rim's controls are left, at its playground's defaults; the
        // blur is in its CSS px now (16).
        if (!p.getBoolean("migrated_604", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            for (int i = LOCK_NOTIF_GLASS; i <= LOCK_NOTIF_BORDER_COLOR; i++) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putBoolean("migrated_604", true).apply();
        }
        // 6.02: react-glass-rim's intensity is the rim setting now (30 = its 0.3), and its
        // playground's controls are settings with its defaults.
        if (!p.getBoolean("migrated_602", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            e.putInt(GLASS_KEYS[LOCK_NOTIF_RIM], GLASS_DEFAULTS[LOCK_NOTIF_RIM]);
            for (int i = LOCK_NOTIF_RIM_COLOR; i <= LOCK_NOTIF_BORDER_COLOR; i++) {
                e.putInt(GLASS_KEYS[i], GLASS_DEFAULTS[i]);
            }
            e.putBoolean("migrated_602", true).apply();
        }
    }

    static void loadLocal(File dataDir) {
        localFile = new File(dataDir, "files/oneglass.settings");
        if (!localFile.isFile()) {
            // Before the first unlock the launcher's own files cannot be read either.
            localMissing = true;
            XLog.i("no local settings copy yet (" + localFile + "), defaults until the sync");
            return;
        }
        localMissing = false;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(localFile)) {
            p.load(in);
            Config.mono = Boolean.parseBoolean(p.getProperty(KEY_WHITE, "" + Config.mono));
            backgroundAlpha = Integer.parseInt(p.getProperty(KEY_OPACITY, "" + DEFAULT_OPACITY));
            style = Integer.parseInt(p.getProperty(KEY_STYLE, "" + DEFAULT_STYLE));
            if (style != STYLE_BLUR) style = STYLE_LIQUID;
            tintColor = Integer.parseInt(p.getProperty(KEY_TINT_COLOR, "" + DEFAULT_TINT));
            int[] g = glass.clone();
            for (int i = 0; i < GLASS_KEYS.length; i++) {
                g[i] = Integer.parseInt(p.getProperty(GLASS_KEYS[i], "" + g[i]));
            }
            setGlass(g);
        } catch (Throwable t) {
            XLog.e("could not read " + localFile, t);
        }
    }

    private static boolean saveLocal() {
        if (localFile == null) return false;
        Properties p = new Properties();
        p.setProperty(KEY_WHITE, "" + Config.mono);
        p.setProperty(KEY_OPACITY, "" + backgroundAlpha);
        p.setProperty(KEY_STYLE, "" + style);
        p.setProperty(KEY_TINT_COLOR, "" + tintColor);
        for (int i = 0; i < GLASS_KEYS.length; i++) p.setProperty(GLASS_KEYS[i], "" + glass[i]);
        try {
            File dir = localFile.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(localFile)) {
                p.store(out, "BlurIcons, written by the launcher");
            }
            return true;
        } catch (Throwable t) {
            XLog.e("could not write " + localFile, t);
            return false;
        }
    }

    /**
     * The launcher's local copy was not readable at start (before the first unlock): read
     * once the user is unlocked, and applied like a change, so the glass is drawn again.
     */
    private static boolean localMissing;
    /** Set while the local copy is applied after the unlock: it bakes nothing new. */
    private static boolean catchingUp;

    /** Launcher side, once the Application exists: sync with the module and follow changes. */
    static void install(Context app) {
        if (installed) return;
        installed = true;
        Handler main = new Handler(Looper.getMainLooper());
        watchUnlock(app, main);
        sync(app, main);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(KEY_PICTURE)) {
                    LiquidGlass.reload();
                    return;
                }
                int[] g = glass.clone();
                for (int i = 0; i < GLASS_KEYS.length; i++) {
                    g[i] = intent.getIntExtra(GLASS_KEYS[i], g[i]);
                }
                applyGlass(g);
                apply(intent.getIntExtra(KEY_OPACITY, backgroundAlpha),
                        intent.getBooleanExtra(KEY_WHITE, Config.mono),
                        intent.getIntExtra(KEY_STYLE, style),
                        intent.getIntExtra(KEY_TINT_COLOR, tintColor));
            }
        };
        app.registerReceiver(receiver, new IntentFilter(ACTION_CHANGED),
                Context.RECEIVER_EXPORTED);
        XLog.i("settings receiver registered");
    }

    /** Reads every value from the module's provider and applies what changed. */
    private static void sync(Context app, Handler main) {
        new Thread(() -> {
            Integer opacity = null;
            Boolean white = null;
            int styleValue = style;
            int tintValue = tintColor;
            int[] g = glass.clone();
            try (Cursor c = app.getContentResolver().query(URI, null, null, null, null)) {
                if (c == null) {
                    // No provider: the module's is not there before the user unlock.
                    XLog.i("settings provider not reachable yet, synced after the unlock");
                    return;
                }
                while (c.moveToNext()) {
                    String key = c.getString(0);
                    int value = c.getInt(1);
                    if (KEY_OPACITY.equals(key)) opacity = value;
                    else if (KEY_WHITE.equals(key)) white = value != 0;
                    else if (KEY_STYLE.equals(key)) styleValue = value;
                    else if (KEY_TINT_COLOR.equals(key)) tintValue = value;
                    for (int i = 0; i < GLASS_KEYS.length; i++) {
                        if (GLASS_KEYS[i].equals(key)) g[i] = value;
                    }
                }
            } catch (Throwable t) {
                XLog.e("settings provider unavailable", t);
            }
            if (opacity == null || white == null) return;
            int o = opacity;
            boolean w = white;
            int s = styleValue;
            int tint = tintValue;
            main.post(() -> {
                applyGlass(g);
                apply(o, w, s, tint);
            });
        }, "BlurIconsSettings").start();
    }

    /**
     * One UI Home starts before the user is unlocked (System UI binds its direct-boot
     * service), when neither the module's provider nor the launcher's own files can be read.
     * Once unlocked: the local copy, the module's values and the glass's picture again.
     */
    private static void watchUnlock(Context app, Handler main) {
        UserManager um = app.getSystemService(UserManager.class);
        if (um == null || um.isUserUnlocked()) return;
        BroadcastReceiver unlocked = new BroadcastReceiver() {
            private boolean done;

            @Override
            public void onReceive(Context c, Intent i) {
                if (done) return;
                done = true;
                try {
                    app.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                XLog.i("user unlocked: settings and liquid glass loaded again");
                if (localMissing && localFile != null) catchUpLocal();
                sync(app, main);
                if (Config.blur) LiquidGlass.retry();
            }
        };
        try {
            // A protected broadcast, sent to registered receivers only.
            app.registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    null, main, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            XLog.e("unlock listener", t);
            return;
        }
        // The unlock between the check above and the registration.
        if (um.isUserUnlocked()) main.post(() -> unlocked.onReceive(app, null));
    }

    /** The local copy, readable now: applied as a change (redraws, nothing restarted). */
    private static void catchUpLocal() {
        if (!localFile.isFile()) return;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(localFile)) {
            p.load(in);
            int[] g = glass.clone();
            for (int i = 0; i < GLASS_KEYS.length; i++) {
                g[i] = Integer.parseInt(p.getProperty(GLASS_KEYS[i], "" + g[i]));
            }
            localMissing = false;
            catchingUp = true;
            try {
                applyGlass(g);
                apply(Integer.parseInt(p.getProperty(KEY_OPACITY, "" + backgroundAlpha)),
                        Boolean.parseBoolean(p.getProperty(KEY_WHITE, "" + Config.mono)),
                        Integer.parseInt(p.getProperty(KEY_STYLE, "" + style)),
                        Integer.parseInt(p.getProperty(KEY_TINT_COLOR, "" + tintColor)));
            } finally {
                catchingUp = false;
            }
        } catch (Throwable t) {
            XLog.e("could not read " + localFile, t);
        }
    }

    /** Clamps and stores the glass tuning and hands it to {@link Config}. */
    private static boolean setGlass(int[] values) {
        boolean changed = false;
        for (int i = 0; i < GLASS_KEYS.length; i++) {
            int v = Math.max(GLASS_MIN[i], Math.min(GLASS_MAX[i], values[i]));
            if (v != glass[i]) changed = true;
            glass[i] = v;
        }
        Config.glassBlur = glass[GLASS_BLUR] / 100f;
        Config.lensHeight = glass[LENS_HEIGHT] / 100f;
        Config.lensAmount = glass[LENS_AMOUNT] / 100f;
        Config.glassSaturation = glass[GLASS_SATURATION] / 100f;
        Config.lensDepth = glass[LENS_DEPTH] != 0;
        Config.glassFolders = glass[GLASS_FOLDERS] != 0;
        Config.glassWidgets = glass[GLASS_WIDGETS] != 0;
        // OneGlass: the One UI apps screen always stays stock.
        Config.drawer = false;
        Config.drawerBlur = glass[DRAWER_BLUR] / 100f;
        Config.glassSearch = glass[GLASS_SEARCH] != 0;
        Config.searchGray = glass[SEARCH_GRAY] / 100f;
        Config.glassClock = glass[GLASS_CLOCK] != 0;
        Config.clockRefraction = glass[CLOCK_REFRACTION];
        Config.clockTint = glass[CLOCK_TINT];
        Config.clockBlur = glass[CLOCK_BLUR];
        Config.clockSaturation = glass[CLOCK_SATURATION];
        Config.clockFrost = glass[CLOCK_FROST];
        Config.clockBevel = glass[CLOCK_BEVEL];
        Config.clockHighlight = glass[CLOCK_HIGHLIGHT];
        Config.drawerLensHeight = glass[DRAWER_LENS_HEIGHT] / 100f;
        Config.drawerLensAmount = glass[DRAWER_LENS_AMOUNT] / 100f;
        Config.drawerSaturation = glass[DRAWER_SATURATION] / 100f;
        Config.drawerTint = Math.round(glass[DRAWER_TINT] * 2.55f);
        Config.glassOpenFolder = glass[GLASS_OPEN_FOLDER] != 0;
        Config.folderBlur = glass[FOLDER_BLUR] / 100f;
        Config.glassFolderPanel = glass[GLASS_FOLDER_PANEL] != 0;
        Config.folderPanelTint = Math.round(glass[FOLDER_PANEL_TINT] * 2.55f);
        Config.folderPanelLens = glass[FOLDER_PANEL_LENS] / 100f;
        Config.searchBlur = glass[SEARCH_BLUR] / 100f;
        Config.searchSaturation = glass[SEARCH_SATURATION] / 100f;
        Config.searchLens = glass[SEARCH_LENS] / 100f;
        Config.iosWidgetCorners = glass[WIDGET_IOS_CORNERS] != 0;
        Config.glassRim = glass[GLASS_RIM] / 100f;
        Config.glassHairline = glass[GLASS_HAIRLINE] / 100f;
        Config.clockIos27 = glass[CLOCK_IOS27] != 0;
        Config.clockIos27Layer = glass[CLOCK_IOS27_LAYER];
        Config.clockRim = glass[CLOCK_RIM];
        Config.clockHairline = glass[CLOCK_HAIRLINE];
        Config.galleryGlare = glass[GALLERY_GLARE] / 100f;
        Config.galleryLook = glass[GALLERY_LOOK];
        Config.gallerySpatial = glass[GALLERY_SPATIAL] != 0;
        Config.galleryLight = glass[GALLERY_LIGHT] / 100f;
        Config.packPeel = glass[PACK_PEEL] != 0;
        Config.hideBadges = glass[HIDE_BADGES] != 0;
        Config.loadReadout = glass[LOAD_READOUT] != 0;
        Config.lockNotifGlass = glass[LOCK_NOTIF_GLASS] != 0;
        Config.lockNotifRadius = glass[LOCK_NOTIF_RADIUS];
        Config.lockNotifShade = glass[LOCK_NOTIF_SHADE];
        Config.lockNotifBlur = glass[LOCK_NOTIF_BLUR];
        Config.lockNotifRim = glass[LOCK_NOTIF_RIM];
        Config.lockNotifHairline = glass[LOCK_NOTIF_HAIRLINE];
        Config.lockNotifGlow = glass[LOCK_NOTIF_GLOW];
        Config.lockNotifRefraction = glass[LOCK_NOTIF_REFRACTION];
        Config.lockNotifSheen = glass[LOCK_NOTIF_SHEEN];
        Config.lockNotifStyle = glass[LOCK_NOTIF_STYLE];
        for (int i = LOCK_NOTIF_RIM_COLOR; i <= LOCK_NOTIF_BORDER_COLOR; i++) {
            Config.lockNotifRimExtra[i - LOCK_NOTIF_RIM_COLOR] = glass[i];
        }
        Config.lockNotifBottom = glass[LOCK_NOTIF_BOTTOM] != 0;
        Config.lockNotifPile = glass[LOCK_NOTIF_PILE] != 0;
        Config.glassLight = glass[GLASS_LIGHT] / 100f;
        Config.glassCondense = glass[GLASS_CONDENSE] != 0;
        Config.folderMorph = glass[FOLDER_MORPH] != 0;
        Config.clockIos26 = glass[CLOCK_IOS26] != 0;
        Config.clockFont = glass[CLOCK_FONT] != 0;
        return changed;
    }

    /**
     * The settings screen's preview only: these values in this process's {@link Config} and
     * statics, as the launcher applies them, so the preview draws with what the Home screen
     * will. Nothing is saved, sent or redrawn here.
     */
    static void preview(int[] values, int opacity, int newStyle, int newTint) {
        setGlass(values);
        backgroundAlpha = Math.max(0, Math.min(255, opacity));
        style = newStyle != STYLE_BLUR ? STYLE_LIQUID : STYLE_BLUR;
        tintColor = newTint;
    }

    private static void applyGlass(int[] values) {
        boolean badges = Config.hideBadges;
        boolean peel = Config.packPeel;
        if (!setGlass(values)) return;
        boolean saved = saveLocal();
        XLog.i("settings: glass " + java.util.Arrays.toString(glass));
        LiquidGlass.clearTuning();
        IconBlur.onSettingsChanged();
        if (peel && !Config.packPeel) IconBlur.onPeelOff();
        SearchGlass.invalidateAll();
        CellGlass.invalidateAll();
        FolderGlass.invalidateAll();
        GalleryLook.onSettingsChanged();
        GlassLight.onSettingsChanged();
        LockLink.send(false);
        // The badge, and the peel of the module's own glass icons, are baked into One UI
        // Home's icon cache (Config.signature()): it is reset on the next start. Not when the
        // copy could not be saved (the restart would only loop), nor while the local copy is
        // caught up after the unlock (it was what the cache was made with).
        if (saved && !catchingUp && (badges != Config.hideBadges
                || (Config.glassIcons && peel != Config.packPeel))) {
            XLog.i("icon cache settings changed, restarting One UI Home");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 400);
        }
    }

    private static void apply(int opacity, boolean white, int newStyle, int newTint) {
        // 5.8's iOS 27 style (2) is the liquid glass again, now with its rim.
        if (newStyle != STYLE_BLUR) newStyle = STYLE_LIQUID;
        int alpha = Math.max(0, Math.min(255, opacity));
        boolean opacityChanged = alpha != backgroundAlpha || newStyle != style
                || newTint != tintColor;
        // The foreground colour only exists in the module's own glass icons.
        boolean iconsChanged = Config.glassIcons && white != Config.mono;
        if (!opacityChanged && !iconsChanged) return;
        backgroundAlpha = alpha;
        style = newStyle;
        tintColor = newTint;
        Config.mono = white;
        boolean saved = saveLocal();
        XLog.i("settings: background opacity=" + alpha + " white=" + white + " style=" + style);
        IconBlur.onSettingsChanged();
        CellGlass.invalidateAll();
        SearchGlass.invalidateAll();
        GlassLight.onSettingsChanged();
        if (iconsChanged && saved && !catchingUp) {
            // The icon cache is rebuilt on the next start (Config.signature() changed).
            XLog.i("foreground colour changed, restarting One UI Home");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 400);
        }
    }
}
