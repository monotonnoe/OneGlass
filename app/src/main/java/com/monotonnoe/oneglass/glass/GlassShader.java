package com.monotonnoe.oneglass.glass;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * The Liquid Glass material as an AGSL shader.
 *
 * The backdrop (already frosted) comes in as {@code content}, sampled in the same local
 * coordinates the shader is drawn in. Per pixel:
 *  1. continuous rounded-rect SDF of the platter (p-norm corners),
 *  2. refraction: inside an edge band the sample point is pushed outward along the SDF
 *     normal with a circular lens profile, so the rim bends the wallpaper like thick glass,
 *  3. chromatic dispersion: R / G / B sampled at slightly different bends,
 *  4. vibrancy (saturation boost) and the Figma luminosity layer (#999999 @ 33%),
 *  5. optional milky / smoky tint,
 *  6. light: 1.25pt hairline + soft inner glow, strongest where the edge faces the light,
 *  7. 0.5pt darker outline just outside the shape.
 */
public final class GlassShader {

    static final String SRC = ""
            + "uniform shader content;\n"
            + "uniform float2 origin;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float cornerPow;\n"
            + "uniform float refrHeight;\n"
            + "uniform float refrAmount;\n"
            + "uniform float dispersion;\n"
            + "uniform float saturation;\n"
            + "uniform float2 luma;\n"
            + "uniform float4 tint;\n"
            + "uniform float4 rim;\n"
            + "uniform float2 lightDir;\n"
            + "uniform float outline;\n"
            + "\n"
            + "float lum(float3 c) {\n"
            + "    return dot(c, float3(0.2126, 0.7152, 0.0722));\n"
            + "}\n"
            + "\n"
            + "float sdShape(float2 p) {\n"
            + "    float2 hs = size * 0.5;\n"
            + "    float r = min(radius, min(hs.x, hs.y));\n"
            + "    float2 q = abs(p) - (hs - float2(r, r));\n"
            + "    float2 m = max(q, float2(0.0, 0.0));\n"
            + "    float outside = 0.0;\n"
            + "    if (m.x > 0.0 || m.y > 0.0) {\n"
            + "        outside = pow(pow(m.x, cornerPow) + pow(m.y, cornerPow), 1.0 / cornerPow);\n"
            + "    }\n"
            + "    return outside + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "float2 normalAt(float2 p) {\n"
            + "    float e = 0.75;\n"
            + "    float dx = sdShape(p + float2(e, 0.0)) - sdShape(p - float2(e, 0.0));\n"
            + "    float dy = sdShape(p + float2(0.0, e)) - sdShape(p - float2(0.0, e));\n"
            + "    float2 g = float2(dx, dy);\n"
            + "    float l = length(g);\n"
            + "    if (l < 0.0001) {\n"
            + "        return float2(0.0, 0.0);\n"
            + "    }\n"
            + "    return g / l;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 p = coord - origin - size * 0.5;\n"
            + "    float sd = sdShape(p);\n"
            + "    float o = outline * clamp(1.0 - abs(sd - 0.4) / 0.9, 0.0, 1.0);\n"
            + "    if (sd > 0.5) {\n"
            + "        return half4(0.0, 0.0, 0.0, o);\n"
            + "    }\n"
            + "    float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    float t = max(-sd, 0.0);\n"
            + "    float2 n = normalAt(p);\n"
            + "    float bend = 0.0;\n"
            + "    if (refrHeight > 0.0 && t < refrHeight) {\n"
            + "        float x = 1.0 - t / refrHeight;\n"
            + "        bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * refrAmount;\n"
            + "    }\n"
            + "    float4 c;\n"
            + "    if (dispersion > 0.0 && bend > 0.0) {\n"
            + "        float4 cr = float4(content.eval(coord + n * (bend * (1.0 + dispersion))));\n"
            + "        float4 cg = float4(content.eval(coord + n * bend));\n"
            + "        float4 cb = float4(content.eval(coord + n * (bend * (1.0 - dispersion))));\n"
            + "        c = float4(cr.r, cg.g, cb.b, cg.a);\n"
            + "    } else {\n"
            + "        c = float4(content.eval(coord + n * bend));\n"
            + "    }\n"
            + "    float a = c.a;\n"
            + "    float3 rgb = float3(0.0, 0.0, 0.0);\n"
            + "    if (a > 0.0001) {\n"
            + "        rgb = c.rgb / a;\n"
            + "    }\n"
            + "    float l0 = lum(rgb);\n"
            + "    rgb = clamp(mix(float3(l0, l0, l0), rgb, saturation), 0.0, 1.0);\n"
            + "    rgb = clamp(rgb + (luma.x - lum(rgb)) * luma.y, 0.0, 1.0);\n"
            + "    rgb = mix(rgb, tint.rgb, tint.a);\n"
            + "    float facing = abs(dot(n, lightDir));\n"
            + "    float hair = clamp(1.0 - abs(t - rim.x * 0.5) / max(rim.x, 0.5), 0.0, 1.0);\n"
            + "    float glow = exp(-t / max(rim.z, 0.001));\n"
            + "    float add = rim.y * hair * (0.35 + 0.65 * facing) + rim.w * glow * (0.25 + 0.75 * facing);\n"
            + "    rgb = rgb + (1.0 - rgb) * clamp(add, 0.0, 1.0);\n"
            + "    float alpha = inside * a;\n"
            + "    float outA = alpha + (1.0 - alpha) * o;\n"
            + "    return half4(rgb * alpha, outA);\n"
            + "}\n";

    private static RuntimeShader shader;
    private static boolean failed;
    private static String error;

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FALLBACK = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF TMP = new RectF();

    static {
        RIM.setStyle(Paint.Style.STROKE);
    }

    private GlassShader() {
    }

    /** Null when the shader compiled fine (or was not tried yet). */
    public static String error() {
        return error;
    }

    private static RuntimeShader shader() {
        if (shader == null && !failed) {
            try {
                shader = new RuntimeShader(SRC);
            } catch (Throwable t) {
                failed = true;
                error = String.valueOf(t.getMessage());
                Log.e("glass shader did not compile, using the flat fallback", t);
            }
        }
        return shader;
    }

    /**
     * Draws one glass platter. Main thread only.
     *
     * @param content frosted backdrop in the canvas' local coordinates
     * @param alpha   0..1 extra opacity (e.g. the hotseat fading out)
     */
    public static void draw(Canvas canvas, RectF rect, GlassSpec s, Shader content, float alpha) {
        if (rect.width() < 1f || rect.height() < 1f || alpha <= 0.003f) {
            return;
        }
        RuntimeShader rs = canvas.isHardwareAccelerated() ? shader() : null;
        if (rs == null) {
            drawFallback(canvas, rect, s, content, alpha);
            return;
        }
        try {
            rs.setInputShader("content", content);
            rs.setFloatUniform("origin", rect.left, rect.top);
            rs.setFloatUniform("size", rect.width(), rect.height());
            rs.setFloatUniform("radius", s.radius);
            rs.setFloatUniform("cornerPow", s.cornerPow);
            rs.setFloatUniform("refrHeight", s.refractionHeight);
            rs.setFloatUniform("refrAmount", s.refractionAmount);
            rs.setFloatUniform("dispersion", s.dispersion);
            rs.setFloatUniform("saturation", s.saturation);
            rs.setFloatUniform("luma", s.lumaTarget, s.lumaMix);
            rs.setFloatUniform("tint", s.tint[0], s.tint[1], s.tint[2], s.tint[3]);
            rs.setFloatUniform("rim", s.rimWidth, s.rimStrength, s.glowBand, s.glowStrength);
            rs.setFloatUniform("lightDir", s.lightX, s.lightY);
            rs.setFloatUniform("outline", s.outline);
        } catch (Throwable t) {
            failed = true;
            shader = null;
            error = String.valueOf(t.getMessage());
            Log.e("glass shader uniforms rejected, using the flat fallback", t);
            drawFallback(canvas, rect, s, content, alpha);
            return;
        }
        PAINT.setShader(rs);
        PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
        canvas.drawRect(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, PAINT);
        PAINT.setShader(null);
    }

    /** Frosted fill + light hairline, for software canvases or if AGSL is unavailable. */
    static void drawFallback(Canvas canvas, RectF rect, GlassSpec s, Shader content, float alpha) {
        int a = Math.round(255f * Math.min(1f, alpha));
        ContinuousPath.build(PATH, rect, s.radius, s.smoothing);
        if (content != null) {
            FALLBACK.setShader(content);
            FALLBACK.setColor(0xFFFFFFFF);
            FALLBACK.setAlpha(a);
            canvas.drawPath(PATH, FALLBACK);
            FALLBACK.setShader(null);
        }
        int veil = Math.round(255f * s.lumaMix * 0.6f);
        int g = Math.round(255f * s.lumaTarget);
        FALLBACK.setColor((veil << 24) | (g << 16) | (g << 8) | g);
        FALLBACK.setAlpha(Math.round(veil * Math.min(1f, alpha)));
        canvas.drawPath(PATH, FALLBACK);

        TMP.set(rect);
        float half = s.rimWidth / 2f;
        TMP.inset(half, half);
        ContinuousPath.build(PATH, TMP, Math.max(0f, s.radius - half), s.smoothing);
        RIM.setStrokeWidth(s.rimWidth);
        RIM.setColor(0xFFFFFFFF);
        RIM.setAlpha(Math.round(255f * Math.min(1f, s.rimStrength * 1.4f) * Math.min(1f, alpha)));
        canvas.drawPath(PATH, RIM);
    }
}
