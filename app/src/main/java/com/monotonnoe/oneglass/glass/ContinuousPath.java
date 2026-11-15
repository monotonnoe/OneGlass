package com.monotonnoe.oneglass.glass;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * Rounded rectangle with "continuous" (squircle-like) corners, the same construction Figma
 * uses for its corner smoothing slider. The iOS 27 kit uses smoothing 0.6 for the dock,
 * the search field and other glass platters.
 */
public final class ContinuousPath {

    public static final float IOS_SMOOTHING = 0.6f;

    private static final RectF OVAL = new RectF();

    private ContinuousPath() {
    }

    public static void build(Path path, RectF r, float radius, float smoothing) {
        path.reset();
        float w = r.width();
        float h = r.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float budget = Math.min(w, h) / 2f;
        float R = Math.min(radius, budget);
        if (R <= 0.01f) {
            path.addRect(r, Path.Direction.CW);
            return;
        }
        float s = Math.max(0f, Math.min(1f, smoothing));
        float p = (1f + s) * R;
        if (p > budget) {
            float maxS = budget / R - 1f;
            s = Math.max(0f, Math.min(s, maxS));
            p = Math.min(p, budget);
        }
        double arcMeasure = 90.0 * (1.0 - s);
        float arcLen = (float) (Math.sin(Math.toRadians(arcMeasure / 2.0)) * R * Math.sqrt(2.0));
        double alpha = (90.0 - arcMeasure) / 2.0;
        float p3p4 = (float) (R * Math.tan(Math.toRadians(alpha / 2.0)));
        double beta = 45.0 * s;
        float c = (float) (p3p4 * Math.cos(Math.toRadians(beta)));
        float d = (float) (c * Math.tan(Math.toRadians(beta)));
        float b = (p - arcLen - c - d) / 3f;
        float a = 2f * b;

        path.moveTo(r.left + p, r.top);
        corner(path, r.right, r.top, 1, 0, 0, 1, a, b, c, d, p, R, (float) arcMeasure);
        corner(path, r.right, r.bottom, 0, 1, -1, 0, a, b, c, d, p, R, (float) arcMeasure);
        corner(path, r.left, r.bottom, -1, 0, 0, -1, a, b, c, d, p, R, (float) arcMeasure);
        corner(path, r.left, r.top, 0, -1, 1, 0, a, b, c, d, p, R, (float) arcMeasure);
        path.close();
    }

    /**
     * One corner, walking clockwise. (cx, cy) is the sharp corner point, e1 the direction of the
     * incoming edge, e2 the direction of the outgoing edge.
     */
    private static void corner(Path path, float cx, float cy,
                               float e1x, float e1y, float e2x, float e2y,
                               float a, float b, float c, float d, float p, float R, float arcMeasure) {
        float sx = cx - p * e1x;
        float sy = cy - p * e1y;
        path.lineTo(sx, sy);

        float p1x = sx + (a + b + c) * e1x + d * e2x;
        float p1y = sy + (a + b + c) * e1y + d * e2y;
        path.cubicTo(sx + a * e1x, sy + a * e1y,
                sx + (a + b) * e1x, sy + (a + b) * e1y,
                p1x, p1y);

        if (arcMeasure > 0.01f) {
            float ox = cx - R * e1x + R * e2x;
            float oy = cy - R * e1y + R * e2y;
            float start = (float) Math.toDegrees(Math.atan2(p1y - oy, p1x - ox));
            OVAL.set(ox - R, oy - R, ox + R, oy + R);
            path.arcTo(OVAL, start, arcMeasure, false);
        }

        float ex = cx + p * e2x;
        float ey = cy + p * e2y;
        path.cubicTo(ex - (a + b) * e2x, ey - (a + b) * e2y,
                ex - a * e2x, ey - a * e2y,
                ex, ey);
    }
}
