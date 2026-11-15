package com.monotonnoe.oneglass.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * The fonts the user chose for the lock screen notification cards (the settings screen keeps
 * the files; nothing is bundled), loaded in System UI from the module's provider: One UI's own
 * font does not give the cards a semibold title over a regular text. One file serves both when
 * it is a variable font (SF Pro's is), taken at each weight. Characters a font lacks come from
 * the system's.
 */
final class LockFonts {
    /** Sent to System UI by the settings screen when a font is chosen or removed. */
    static final String ACTION_FONTS = "com.monotonnoe.oneglass.engine.LOCK_FONTS";
    static final String TITLE = "title";
    static final String TEXT = "text";
    /** The weights iOS's notifications take from SF Pro: semibold (590) and regular. */
    static final int TITLE_WEIGHT = 590;
    static final int TEXT_WEIGHT = 400;
    /**
     * The optical size taken from a variable font: SF Pro's smallest, its Text design, which
     * iOS draws its 15 pt notification texts in (the file's default is 28, its Display one).
     */
    static final int OPTICAL_SIZE = 17;
    /**
     * SF Pro's tracking at 15 pt, from its trak table (-32 of 2048), in ems: iOS applies it,
     * Android does not read the table, so the cards set it on the texts in a chosen font.
     */
    static final float TRACKING = -0.0156f;

    private static volatile Typeface title;
    private static volatile Typeface text;
    private static boolean started;

    private LockFonts() {
    }

    /** The title's font, or null for One UI's. */
    static Typeface title() {
        return title;
    }

    /** The text's (and the time's) font, or null for One UI's. */
    static Typeface text() {
        return text;
    }

    /** Loads the fonts once, and again whenever the settings screen says they changed. */
    static void start(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                load(app);
            }
        }, new IntentFilter(ACTION_FONTS), Context.RECEIVER_EXPORTED);
        // System UI starts before the user is unlocked, when the module's provider (and so
        // the font files) cannot be read: the fonts came out as One UI's after every reboot.
        // Read again once the user is unlocked.
        try {
            android.os.UserManager um = app.getSystemService(android.os.UserManager.class);
            if (um != null && !um.isUserUnlocked()) {
                BroadcastReceiver unlocked = new BroadcastReceiver() {
                    private boolean done;

                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        if (done) return;
                        done = true;
                        try {
                            app.unregisterReceiver(this);
                        } catch (Throwable ignored) {
                        }
                        XLog.i("lock notifications: user unlocked, fonts read again");
                        load(app);
                    }
                };
                app.registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                        null, new android.os.Handler(android.os.Looper.getMainLooper()),
                        Context.RECEIVER_NOT_EXPORTED);
                if (um.isUserUnlocked()) unlocked.onReceive(app, null);
            }
        } catch (Throwable t) {
            XLog.e("lock notifications: unlock listener", t);
        }
        load(app);
    }

    private static void load(Context c) {
        new Thread(() -> {
            Typeface t = read(c, TITLE, TITLE_WEIGHT);
            Typeface x = read(c, TEXT, TEXT_WEIGHT);
            // One file for both: a variable font at the other weight too.
            if (t == null && x != null) t = read(c, TEXT, TITLE_WEIGHT);
            if (x == null && t != null) x = read(c, TITLE, TEXT_WEIGHT);
            title = t;
            text = x;
            XLog.i("lock notifications: fonts, title " + describe(c, TITLE, t)
                    + ", text " + describe(c, TEXT, x));
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(LockNotifications::refreshAll);
        }, "BlurIconsFonts").start();
    }

    /** Whether a font was chosen, and whether its file is variable (takes any weight). */
    private static String describe(Context c, String which, Typeface face) {
        if (face == null) return "One UI's";
        Typeface thin = read(c, which, 100);
        Typeface heavy = read(c, which, 900);
        if (thin == null || heavy == null) return "chosen";
        android.graphics.Paint p = new android.graphics.Paint();
        p.setTextSize(100f);
        p.setTypeface(thin);
        float a = p.measureText("Hamburgefonstiv");
        p.setTypeface(heavy);
        float b = p.measureText("Hamburgefonstiv");
        return Math.abs(a - b) > 1f ? "chosen, variable" : "chosen, one weight";
    }

    /** The chosen file {@code which} at {@code weight}, or null when there is none. */
    private static Typeface read(Context c, String which, int weight) {
        return read(c, which, weight, OPTICAL_SIZE);
    }

    private static Typeface read(Context c, String which, int weight, int opsz) {
        Uri uri = Uri.parse("content://" + Settings.AUTHORITY + "/font/" + which);
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;
            ByteBuffer buffer;
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel ch = in.getChannel();
                buffer = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            }
            Font font = new Font.Builder(buffer)
                    .setWeight(weight)
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz)
                    .build();
            return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                    .setStyle(new FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif")
                    .build();
        } catch (FileNotFoundException none) {
            return null;
        } catch (Throwable t) {
            XLog.e("lock notifications: font " + which, t);
            return null;
        }
    }
}
