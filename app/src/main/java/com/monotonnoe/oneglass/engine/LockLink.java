package com.monotonnoe.oneglass.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One UI Home's side of the liquid glass lock screen clock: System UI can neither read a live
 * lock wallpaper nor see the module, so the launcher sends it the picture its own glass refracts
 * (a JPEG of the downscaled copy) and the clock settings, whenever they change or System UI
 * asks ({@link LockGlass#ACTION_REQUEST}).
 */
final class LockLink {
    private static final ExecutorService SEND = Executors.newSingleThreadExecutor();
    private static Context app;

    private LockLink() {
    }

    static void install(Context context) {
        if (app != null) return;
        app = context;
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                send(true);
            }
        }, new IntentFilter(LockGlass.ACTION_REQUEST), Context.RECEIVER_EXPORTED);
    }

    /** Sends the clock settings, and the picture too when {@code withPicture}. */
    static void send(boolean withPicture) {
        Context c = app;
        if (c == null) return;
        int[] settings = {Config.glassClock ? 1 : 0, Config.clockRefraction, Config.clockBlur,
                Config.clockSaturation, Config.clockTint, Config.clockFrost, Config.clockBevel,
                Config.clockHighlight, Config.clockIos27 ? 1 : 0, Config.clockIos27Layer,
                Config.clockRim, Config.clockHairline, Config.lockNotifGlass ? 1 : 0,
                Config.lockNotifRadius, Config.lockNotifShade, Config.lockNotifBlur, Config.lockNotifRim,
                Config.lockNotifHairline, Config.lockNotifGlow,
                Config.lockNotifRefraction, Config.lockNotifSheen, Config.lockNotifStyle,
                Config.lockNotifRimExtra[0], Config.lockNotifRimExtra[1],
                Config.lockNotifRimExtra[2], Config.lockNotifRimExtra[3],
                Config.lockNotifRimExtra[4], Config.lockNotifRimExtra[5],
                Config.lockNotifBottom ? 1 : 0, Config.lockNotifPile ? 1 : 0,
                Config.clockIos26 ? 1 : 0, Config.clockFont ? 1 : 0};
        Bitmap picture = withPicture ? LiquidGlass.picture() : null;
        SEND.execute(() -> {
            try {
                Intent intent = new Intent(LockGlass.ACTION_PICTURE)
                        .setPackage(LockScreenDiag.SYSTEMUI)
                        .putExtra(LockGlass.EXTRA_SETTINGS, settings);
                if (picture != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    picture.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    intent.putExtra(LockGlass.EXTRA_JPEG, out.toByteArray());
                }
                c.sendBroadcast(intent);
            } catch (Throwable t) {
                XLog.e("lock link", t);
            }
        });
    }
}
