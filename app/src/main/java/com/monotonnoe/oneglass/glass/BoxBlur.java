package com.monotonnoe.oneglass.glass;

import android.graphics.Bitmap;

/** Three-pass box blur (close to a gaussian) on a small ARGB_8888 bitmap, done once per wallpaper. */
public final class BoxBlur {

    private BoxBlur() {
    }

    /** Returns a new mutable ARGB_8888 bitmap. */
    public static Bitmap blur(Bitmap src, int radius) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (radius < 1) {
            return out;
        }
        int w = out.getWidth();
        int h = out.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        out.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            horizontal(a, b, w, h, radius);
            vertical(b, a, w, h, radius);
        }
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void horizontal(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        int half = div / 2;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + clamp(i, 0, w - 1)];
                sa += c >>> 24;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (((sa + half) / div) << 24) | (((sr + half) / div) << 16)
                        | (((sg + half) / div) << 8) | ((sb + half) / div);
                int out = src[row + clamp(x - r, 0, w - 1)];
                int in = src[row + clamp(x + r + 1, 0, w - 1)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void vertical(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        int half = div / 2;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[clamp(i, 0, h - 1) * w + x];
                sa += c >>> 24;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (((sa + half) / div) << 24) | (((sr + half) / div) << 16)
                        | (((sg + half) / div) << 8) | ((sb + half) / div);
                int out = src[clamp(y - r, 0, h - 1) * w + x];
                int in = src[clamp(y + r + 1, 0, h - 1) * w + x];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
