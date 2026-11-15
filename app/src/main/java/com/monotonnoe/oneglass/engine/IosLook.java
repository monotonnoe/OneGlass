package com.monotonnoe.oneglass.engine;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.monotonnoe.oneglass.glass.ContinuousPath;
import com.monotonnoe.oneglass.glass.Units;

/**
 * iOS 27 app icon geometry, from the Figma kit ("iOS 27 Widgets", Playground: App Icon 60x60pt,
 * corner radius 15.5pt, corner smoothing 60%, i.e. Apple's continuous corners).
 */
final class IosLook {

    static final float ICON_PT = 60f;
    static final float CORNER = 15.5f / 60f;
    static final float SMOOTHING = 0.6f;

    private static final Path PATH = new Path();
    private static final Rect PATH_FOR = new Rect();
    private static final RectF TMP = new RectF();

    private IosLook() {
    }

    static boolean shape() {
        return com.monotonnoe.oneglass.Config.current.iosIcons;
    }

    /** The iOS icon squircle filling {@code b}. Main thread; the path is shared. */
    static Path iconPath(Rect b) {
        if (!b.equals(PATH_FOR)) {
            TMP.set(b);
            ContinuousPath.build(PATH, TMP, CORNER * b.width(), SMOOTHING);
            PATH_FOR.set(b);
        }
        return PATH;
    }

    /** Scale that makes an icon of width {@code w} px the iOS 60pt one (1 when off). */
    static float iconScale(View v, int w) {
        if (v == null || w <= 0 || !com.monotonnoe.oneglass.Config.current.iosIconSize) return 1f;
        float s = ICON_PT * Units.pt(v) / w;
        if (Math.abs(s - 1f) < 0.02f) return 1f;
        return Math.max(0.7f, Math.min(1.4f, s));
    }
}
