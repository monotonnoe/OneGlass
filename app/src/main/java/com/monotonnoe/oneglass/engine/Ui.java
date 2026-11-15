package com.monotonnoe.oneglass.engine;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * The settings screen's look, after Apple's Human Interface Guidelines (iOS 26 Settings): the
 * grouped background and its inset cards, the label colours, the system accent colours, and
 * the text styles at the default Dynamic Type size (1 pt = 1 dp, text in sp so the phone's
 * font size scales it). Light or dark with the system theme.
 */
final class Ui {
    final Context context;
    final boolean night;
    final float density;

    /** systemGroupedBackground and secondarySystemGroupedBackground (the cards). */
    final int background;
    final int card;
    /** A card's row while pressed (systemGray4/5). */
    final int cardPressed;
    final int label;
    final int secondaryLabel;
    final int tertiaryLabel;
    final int separator;
    /** The system blue, Kyant's catalog accent (#0088FF / #0091FF). */
    final int accent;
    /** The switch's on colour (systemGreen). */
    final int green;
    final int red;
    /** A control's track: Kyant's (#787878 at 20 %, dark #787880 at 36 %). */
    final int track;
    /** A segmented control's track (tertiarySystemFill). */
    final int segmentTrack;
    /** A segmented control's selected segment. */
    final int segmentThumb;

    final Typeface regular;
    final Typeface medium;
    final Typeface semibold;
    final Typeface bold;

    Ui(Context c) {
        context = c;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        if (night) {
            background = 0xFF000000;
            card = 0xFF1C1C1E;
            cardPressed = 0xFF2C2C2E;
            label = 0xFFFFFFFF;
            secondaryLabel = 0x99EBEBF5;
            tertiaryLabel = 0x4DEBEBF5;
            separator = 0xFF38383A;
            accent = 0xFF0091FF;
            green = 0xFF30D158;
            red = 0xFFFF453A;
            track = 0x5C787880;
            segmentTrack = 0x3D767680;
            segmentThumb = 0xFF636366;
        } else {
            background = 0xFFF2F2F7;
            card = 0xFFFFFFFF;
            cardPressed = 0xFFE5E5EA;
            label = 0xFF000000;
            secondaryLabel = 0x993C3C43;
            tertiaryLabel = 0x4D3C3C43;
            separator = 0xFFC6C6C8;
            accent = 0xFF0088FF;
            green = 0xFF34C759;
            red = 0xFFFF3B30;
            track = 0x33787878;
            segmentTrack = 0x1F767680;
            segmentThumb = 0xFFFFFFFF;
        }
        regular = Typeface.create(Typeface.SANS_SERIF, 400, false);
        medium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        semibold = Typeface.create(Typeface.SANS_SERIF, 600, false);
        bold = Typeface.create(Typeface.SANS_SERIF, 700, false);
    }

    float dp(float v) {
        return v * density;
    }

    int px(float v) {
        return Math.round(v * density);
    }

    float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                context.getResources().getDisplayMetrics());
    }

    /** iOS's text styles at the default size: Large Title, Headline, Body, Subhead, Footnote. */
    static final float LARGE_TITLE = 34f;
    static final float TITLE = 17f;
    static final float BODY = 17f;
    static final float SUBHEAD = 15f;
    static final float FOOTNOTE = 13f;
    static final float CAPTION = 12f;

    static int alpha(int color, float a) {
        int al = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (color & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /**
     * A damped spring, as Compose's spring(dampingRatio, stiffness, visibilityThreshold) that
     * Kyant's components animate with: stepped by the frame's time, settles on its target.
     * (Control Center's Glass.Spring.)
     */
    static final class Spring {
        float damping;
        float stiffness;
        final float threshold;
        float value;
        float velocity;
        float target;

        Spring(float damping, float stiffness, float threshold) {
            this.damping = damping;
            this.stiffness = stiffness;
            this.threshold = threshold;
        }

        void snap(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }

        boolean settled() {
            return value == target && velocity == 0f;
        }

        /** Advances by dt seconds; false once it has settled on its target. */
        boolean step(float dt) {
            if (value == target && velocity == 0f) return false;
            float c = 2f * damping * (float) Math.sqrt(stiffness);
            float t = Math.min(dt, 1f / 15f);
            int n = Math.max(1, (int) Math.ceil(t * 480f));
            float h = t / n;
            for (int i = 0; i < n; i++) {
                velocity += (-stiffness * (value - target) - c * velocity) * h;
                value += velocity * h;
            }
            if (Math.abs(value - target) < threshold && Math.abs(velocity) < threshold * 10f) {
                value = target;
                velocity = 0f;
                return false;
            }
            return true;
        }
    }

    /** Seconds since the last call for this clock, at most a fifteenth (a resumed animation). */
    static final class Clock {
        private long last;

        float tick() {
            long now = System.nanoTime();
            float dt = last == 0 ? 1f / 120f : (now - last) / 1e9f;
            last = now;
            return Math.max(1f / 240f, Math.min(1f / 15f, dt));
        }

        void reset() {
            last = 0;
        }
    }
}
