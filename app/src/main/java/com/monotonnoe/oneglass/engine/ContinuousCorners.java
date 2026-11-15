package com.monotonnoe.oneglass.engine;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * Apple's continuous-curvature rounded rectangle as a Path, ported from Kyant's Shapes
 * (ContinuousCurvatureRoundedRectangleCornerBuilder and RoundedRectangleOutline,
 * com.kyant.shapes), Copyright 2026 Kyant, Apache License 2.0 (copied from the Control Center
 * module). Changes: Java, one radius for all four corners. Each corner reaches 2/3 of a radius
 * further along the edges than a circular one, with its middle half a circular arc. A square
 * whose radius is half its side is a circle, as in Kyant's.
 *
 * <p>The settings screen's cards and preview are clipped to it.
 */
final class ContinuousCorners {
    private static final double SQRT_2 = 1.4142135623730951;
    private static final double FRAC_PI_4 = 0.7853981633974483;
    private static final double FRAC_1_SQRT_2 = 0.7071067811865476;
    private static final double EXTENDED = 2.0 / 3.0;
    private static final double ARC = 0.5;

    private static final double SIN;
    private static final double COS;
    private static final double COT;
    private static final double K0;
    private static final double K1;
    private static final double K2;
    private static final double K3;
    /** [tH 0/1][tV 0/1], as Kyant caches them. */
    private static final double[][][] CACHE = new double[2][2][];

    static {
        double theta = (1.0 - ARC) * FRAC_PI_4;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double cot = 1.0 / Math.tan(theta);
        double cos2 = cos * cos;
        double sin2 = sin * sin;
        double cos3 = cos2 * cos;
        double sin3 = sin2 * sin;
        SIN = sin;
        COS = cos;
        COT = cot;
        K0 = 27.0 * (SQRT_2 - 6.0 * cos + 6.0 * SQRT_2 * cos2 - 4.0 * cos3) * cot
                + 2.0 * sin * (-9.0 + 2.0 * (SQRT_2 - 2.0 * sin) * sin3
                + 2.0 * SQRT_2 * cos * (9.0 + sin2) - 2.0 * cos2 * (9.0 + 2.0 * sin2));
        K1 = -81.0 * (-2.0 + SQRT_2 + 4.0 * (-1.0 + SQRT_2) * cos + 2.0 * (-2.0 + SQRT_2) * cos2)
                * cot - 4.0 * sin * (-9.0 + 9.0 * SQRT_2 + SQRT_2 * sin3
                + (-2.0 + SQRT_2) * cos * (9.0 + sin2));
        K2 = 9.0 * (9.0 * (-4.0 + 3.0 * SQRT_2 + (-6.0 + 4.0 * SQRT_2) * cos) * cot
                + (-6.0 + 4.0 * SQRT_2) * sin);
        K3 = 27.0 * (10.0 - 7.0 * SQRT_2) * cot;
        CACHE[0][0] = even(0.0);
        CACHE[0][1] = uneven(0.0, 1.0);
        CACHE[1][0] = uneven(1.0, 0.0);
        CACHE[1][1] = even(1.0);
    }

    private ContinuousCorners() {
    }

    /** The outline of {@code r} with continuous corners of {@code radius}, into {@code out}. */
    static Path path(Path out, RectF r, float radius) {
        out.rewind();
        float width = r.width();
        float height = r.height();
        float max = Math.min(width, height) * 0.5f;
        float rad = Math.max(0f, Math.min(radius, max));
        if (rad == 0f) {
            out.addRect(r, Path.Direction.CW);
            return out;
        }
        if (width == height && rad >= max) {
            out.addRoundRect(r, rad, rad, Path.Direction.CW);
            return out;
        }
        double rr = rad;
        double tW = clamp01((width * 0.5 - rr) / rr);
        double tH = clamp01((height * 0.5 - rr) / rr);
        double[] p = points(tW, tH);
        double w = width;
        double h = height;
        double ox = r.left;
        double oy = r.top;

        double x = w - rr;
        double y = 0.0;
        out.moveTo(f(ox + x + p[0] * rr), f(oy + y + p[1] * rr));
        cubic(out, ox, oy, x, y, rr, p, 2, 4, 6, 1, 1);
        cubic(out, ox, oy, x, y, rr, p, 8, 10, 12, 1, 1);
        cubic(out, ox, oy, x, y, rr, p, 14, 16, 18, 1, 1);

        x = w - rr;
        y = h;
        out.lineTo(f(ox + x + p[18] * rr), f(oy + y - p[19] * rr));
        cubic(out, ox, oy, x, y, rr, p, 16, 14, 12, 1, -1);
        cubic(out, ox, oy, x, y, rr, p, 10, 8, 6, 1, -1);
        cubic(out, ox, oy, x, y, rr, p, 4, 2, 0, 1, -1);

        x = rr;
        y = h;
        out.lineTo(f(ox + x - p[0] * rr), f(oy + y - p[1] * rr));
        cubic(out, ox, oy, x, y, rr, p, 2, 4, 6, -1, -1);
        cubic(out, ox, oy, x, y, rr, p, 8, 10, 12, -1, -1);
        cubic(out, ox, oy, x, y, rr, p, 14, 16, 18, -1, -1);

        x = rr;
        y = 0.0;
        out.lineTo(f(ox + x - p[18] * rr), f(oy + y + p[19] * rr));
        cubic(out, ox, oy, x, y, rr, p, 16, 14, 12, -1, 1);
        cubic(out, ox, oy, x, y, rr, p, 10, 8, 6, -1, 1);
        cubic(out, ox, oy, x, y, rr, p, 4, 2, 0, -1, 1);
        out.close();
        return out;
    }

    private static void cubic(Path out, double ox, double oy, double x, double y, double r,
                              double[] p, int i1, int i2, int i3, int sx, int sy) {
        out.cubicTo(f(ox + x + sx * p[i1] * r), f(oy + y + sy * p[i1 + 1] * r),
                f(ox + x + sx * p[i2] * r), f(oy + y + sy * p[i2 + 1] * r),
                f(ox + x + sx * p[i3] * r), f(oy + y + sy * p[i3 + 1] * r));
    }

    private static float f(double v) {
        return (float) v;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double[] points(double tH, double tV) {
        int i = tH == 0.0 ? 0 : tH == 1.0 ? 1 : -1;
        int j = tV == 0.0 ? 0 : tV == 1.0 ? 1 : -1;
        if (i >= 0 && j >= 0) return CACHE[i][j];
        return tH == tV ? even(tH) : uneven(tH, tV);
    }

    private static double[] even(double t) {
        double k = EXTENDED * t;
        double sin3 = SIN * SIN * SIN;
        double kappa = solveCubic(K3, K2, K1 + 8.0 * (-k) * sin3 * SIN, K0);
        double x3 = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + SIN) / kappa;
        double y3 = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - COS) / kappa;
        double x2 = x3 - y3 * COT;
        double x1 = x2 - 1.5 * kappa * y3 * y3 / sin3;
        double x0 = -k;
        double x6 = 1.0 - y3;
        double y6 = 1.0 - x3;
        double y7 = 1.0 - x2;
        double y8 = 1.0 - x1;
        double y9 = 1.0 - x0;
        double a = 1.5 * kappa;
        double g = COS * COS - SIN * SIN;
        double x36 = x6 - x3;
        double y36 = y6 - y3;
        double c = -(COS * y36 - SIN * x36);
        double lambda = (-g + Math.sqrt(g * g - 4.0 * a * c)) / (2.0 * a);
        double x4 = x3 + lambda * COS;
        double y4 = y3 + lambda * SIN;
        double x5 = x6 - lambda * SIN;
        double y5 = y6 - lambda * COS;
        return new double[]{x0, 0.0, x1, 0.0, x2, 0.0, x3, y3, x4, y4, x5, y5, x6, y6,
                1.0, y7, 1.0, y8, 1.0, y9};
    }

    private static double[] uneven(double tH, double tV) {
        double kH = EXTENDED * tH;
        double kV = EXTENDED * tV;
        double sin3 = SIN * SIN * SIN;
        double kappa3 = solveCubic(K3, K2, K1 + 8.0 * (-kH) * sin3 * SIN, K0);
        double kappa6 = solveCubic(K3, K2, K1 + 8.0 * (-kV) * sin3 * SIN, K0);
        double x3 = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + SIN) / kappa3;
        double y3 = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - COS) / kappa3;
        double x2 = x3 - y3 * COT;
        double x1 = x2 - 1.5 * kappa3 * y3 * y3 / sin3;
        double x0 = -kH;
        double x3p = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + SIN) / kappa6;
        double y3p = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - COS) / kappa6;
        double x2p = x3p - y3p * COT;
        double x1p = x2p - 1.5 * kappa6 * y3p * y3p / sin3;
        double x0p = -kV;
        double x6 = 1.0 - y3p;
        double y6 = 1.0 - x3p;
        double y7 = 1.0 - x2p;
        double y8 = 1.0 - x1p;
        double y9 = 1.0 - x0p;
        double a = 1.5 * kappa3;
        double b = 1.5 * kappa6;
        double g = COS * COS - SIN * SIN;
        double x36 = x6 - x3;
        double y36 = y6 - y3;
        double c = -(COS * y36 - SIN * x36);
        double d = SIN * y36 - COS * x36;
        double p = 2.0 * (d / b);
        double q = g * g * g / (a * b * b);
        double r = (a * d * d + c * g * g) / (a * b * b);
        double lambda6 = solveDepressedQuartic(p, q, r);
        double lambda3 = (-d - b * lambda6 * lambda6) / g;
        double x4 = x3 + lambda3 * COS;
        double y4 = y3 + lambda3 * SIN;
        double x5 = x6 - lambda6 * SIN;
        double y5 = y6 - lambda6 * COS;
        return new double[]{x0, 0.0, x1, 0.0, x2, 0.0, x3, y3, x4, y4, x5, y5, x6, y6,
                1.0, y7, 1.0, y8, 1.0, y9};
    }

    private static double solveCubic(double a, double b, double c, double d) {
        double f = ((3.0 * c / a) - (b * b) / (a * a)) / 3.0;
        double g = ((2.0 * b * b * b) / (a * a * a) - (9.0 * b * c) / (a * a) + (27.0 * d) / a)
                / 27.0;
        double h = g * g / 4.0 + f * f * f / 27.0;
        double sqrtH = Math.sqrt(h);
        return Math.cbrt(-g / 2.0 + sqrtH) + Math.cbrt(-g / 2.0 - sqrtH) - b / (3.0 * a);
    }

    private static double solveDepressedQuartic(double p, double q, double r) {
        double b = -p / 2.0;
        double c = -r;
        double d = r * p / 2.0 - q * q / 8.0;
        double f = ((3.0 * c) - (b * b)) / 3.0;
        double g = ((2.0 * b * b * b) - (9.0 * b * c) + (27.0 * d)) / 27.0;
        double rr = Math.sqrt(-f * f * f / 27.0);
        double phi = Math.acos(-g / (2.0 * rr));
        double y = 2.0 * Math.sqrt(-f / 3.0) * Math.cos(phi / 3.0);
        double z = y - b / 3.0;
        double u = Math.sqrt(2.0 * z - p);
        return (u - Math.sqrt(u * u - 4.0 * (z + q / (2.0 * u)))) / 2.0;
    }
}
