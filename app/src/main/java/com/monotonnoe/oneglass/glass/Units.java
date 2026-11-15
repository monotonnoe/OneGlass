package com.monotonnoe.oneglass.glass;

import android.util.DisplayMetrics;
import android.view.View;

/**
 * iOS layout unit. The Figma kit is drawn on a 402 x 874 pt iPhone screen; One UI phones have
 * the same 2.17 aspect ratio, so 1 pt = (short screen side) / 402 reproduces the kit 1:1.
 */
public final class Units {

    public static final float SCREEN_W_PT = 402f;
    public static final float SCREEN_H_PT = 874f;

    private Units() {
    }

    public static float pt(View v) {
        View root = v.getRootView();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = v.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return Math.min(w, h) / SCREEN_W_PT;
    }
}
