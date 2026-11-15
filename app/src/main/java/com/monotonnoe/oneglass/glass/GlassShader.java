package com.monotonnoe.oneglass.glass;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Liquid Glass as one AGSL shader.
 *
 * Optics (refraction with depth effect, 7-tap chromatic dispersion, vibrancy, directional
 * highlight) follow Kyant0/AndroidLiquidGlass (Apache-2.0, see NOTICE). On top of that the
 * shader reproduces the exact layer stack of the iOS 27 Figma kit ("iOS 27 Widgets", Playground):
 * up to three fill layers with Figma blend modes (Normal, Exclusion, Luminosity, Linear Burn,
 * Color, Linear Dodge), the Linear Dodge corner glows (inner shadows 0/±40, spread -40, blur 10),
 * the 1.25pt / 0.5pt hairlines (Linear Dodge or Color) and the ±0.5pt Linear Burn side lines.
 *
 * Coordinates are local canvas pixels; {@code content} is the frosted backdrop in the same space.
 */
public final class GlassShader {

    static final String SRC = ""
            + "uniform shader content;\n"
            + "uniform float2 origin;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float cornerPow;\n"
            // x refraction height, y refraction amount (signed), z depth effect, w dispersion
            + "uniform float4 lens;\n"
            // x saturation, y brightness
            + "uniform float2 tone;\n"
            // fill layers: rgb + unused, (alpha, mode)
            + "uniform float4 l1c;\n"
            + "uniform float2 l1;\n"
            + "uniform float4 l2c;\n"
            + "uniform float2 l2;\n"
            + "uniform float4 l3c;\n"
            + "uniform float2 l3;\n"
            // highlight: x intensity, y width, z softness, w falloff
            + "uniform float4 light;\n"
            + "uniform float2 lightDir;\n"
            // corner glows: x amount, y offset, z sigma
            + "uniform float3 glow;\n"
            // hairlines: rgb colour, w offset; hairMode: x mode (0 off, 1 dodge, 2 color), y amount, z sigma
            + "uniform float4 hairC;\n"
            + "uniform float3 hairMode;\n"
            // outside side lines: x darkening, y offset
            + "uniform float2 sideLine;\n"
            // 1 = only paint the refracting rim (icons: the live icon stays visible inside)
            + "uniform float edgeOnly;\n"
            // magnification of the whole platter content (Figma GLASS behaves like a thick lens)
            + "uniform float zoom;\n"
            + "\n"
            + "float lumF(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }\n"
            + "\n"
            + "float3 clipColor(float3 c) {\n"
            + "    float l = lumF(c);\n"
            + "    float n = min(min(c.r, c.g), c.b);\n"
            + "    float x = max(max(c.r, c.g), c.b);\n"
            + "    if (n < 0.0) { c = l + (c - l) * l / max(l - n, 0.00001); }\n"
            + "    if (x > 1.0) { c = l + (c - l) * (1.0 - l) / max(x - l, 0.00001); }\n"
            + "    return c;\n"
            + "}\n"
            + "\n"
            + "float3 setLum(float3 c, float l) { return clipColor(c + (l - lumF(c))); }\n"
            + "\n"
            + "float3 blendLayer(float3 b, float3 s, float a, float mode) {\n"
            + "    if (a <= 0.0 || mode < 0.5) { return b; }\n"
            + "    float3 r = s;\n"
            + "    if (mode < 1.5) { r = s; }\n"
            + "    else if (mode < 2.5) { r = b + s - 2.0 * b * s; }\n"
            + "    else if (mode < 3.5) { r = setLum(b, lumF(s)); }\n"
            + "    else if (mode < 4.5) { r = clamp(b + s - 1.0, 0.0, 1.0); }\n"
            + "    else if (mode < 5.5) { r = setLum(s, lumF(b)); }\n"
            + "    else { r = min(b + s, float3(1.0)); }\n"
            + "    return mix(b, r, a);\n"
            + "}\n"
            + "\n"
            + "float sdRR(float2 p, float2 hs, float r) {\n"
            + "    r = max(min(r, min(hs.x, hs.y)), 0.0);\n"
            + "    float2 q = abs(p) - (hs - float2(r, r));\n"
            + "    float2 m = max(q, float2(0.0, 0.0));\n"
            + "    float o = 0.0;\n"
            + "    if (m.x > 0.0 || m.y > 0.0) {\n"
            + "        o = pow(pow(m.x, cornerPow) + pow(m.y, cornerPow), 1.0 / cornerPow);\n"
            + "    }\n"
            + "    return o + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            // Kyant: analytic gradient of the rounded rect SDF with an enlarged radius (smooth normals)
            + "float2 gradRR(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - float2(r, r));\n"
            + "    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);\n"
            + "    if (q.x >= 0.0 || q.y >= 0.0) {\n"
            + "        float2 m = max(q, float2(0.0, 0.0));\n"
            + "        float l = length(m);\n"
            + "        if (l < 0.0001) { return s * 0.70710678; }\n"
            + "        return s * (m / l);\n"
            + "    }\n"
            + "    float gx = q.x > q.y ? 1.0 : 0.0;\n"
            + "    return s * float2(gx, 1.0 - gx);\n"
            + "}\n"
            + "\n"
            // smooth coverage of a gaussian-blurred edge
            + "float phi(float x) { return 1.0 / (1.0 + exp(-1.702 * x)); }\n"
            + "\n"
            + "float4 sampleC(float2 c) {\n"
            + "    float4 v = float4(content.eval(c));\n"
            + "    if (v.a <= 0.0001) { return float4(0.0); }\n"
            + "    return float4(v.rgb / v.a, v.a);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = size * 0.5;\n"
            + "    float2 p = coord - origin - hs;\n"
            + "    float r = min(radius, min(hs.x, hs.y));\n"
            + "    float sd = sdRR(p, hs, r);\n"
            + "    if (sd > 2.0) { return half4(0.0); }\n"
            + "    float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    float t = max(-sd, 0.0);\n"
            + "    float gr = min(r * 1.5, min(hs.x, hs.y));\n"
            + "    float2 gn = gradRR(p, hs, gr);\n"
            // refraction (Kyant lens): circle-map profile inside the edge band
            + "    float2 sc = origin + hs + p * zoom;\n"
            + "    float d = 0.0;\n"
            + "    float2 dir = gn;\n"
            + "    if (lens.x > 0.0 && t < lens.x) {\n"
            + "        float x = 1.0 - t / lens.x;\n"
            + "        d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * lens.y;\n"
            + "        float lp = length(p);\n"
            + "        float2 mid = lp > 0.0001 ? p / lp : float2(0.0, 0.0);\n"
            + "        float2 g2 = gn + lens.z * mid;\n"
            + "        float lg = length(g2);\n"
            + "        dir = lg > 0.0001 ? g2 / lg : gn;\n"
            + "        sc = sc + d * dir;\n"
            + "    }\n"
            + "    float3 rgb = float3(0.0);\n"
            + "    float ca = 1.0;\n"
            + "    if (lens.w > 0.0 && d != 0.0) {\n"
            + "        float k = lens.w * (p.x * p.y) / max(hs.x * hs.y, 1.0);\n"
            + "        float2 dc = d * dir * k;\n"
            + "        float4 red = sampleC(sc + dc);\n"
            + "        float4 ora = sampleC(sc + dc * 0.6666667);\n"
            + "        float4 yel = sampleC(sc + dc * 0.3333333);\n"
            + "        float4 gre = sampleC(sc);\n"
            + "        float4 cya = sampleC(sc - dc * 0.3333333);\n"
            + "        float4 blu = sampleC(sc - dc * 0.6666667);\n"
            + "        float4 pur = sampleC(sc - dc);\n"
            + "        rgb.r = red.r / 3.5 + ora.r / 3.5 + yel.r / 3.5 + pur.r / 7.0;\n"
            + "        rgb.g = ora.g / 7.0 + yel.g / 3.5 + gre.g / 3.5 + cya.g / 3.5;\n"
            + "        rgb.b = cya.b / 3.0 + blu.b / 3.0 + pur.b / 3.0;\n"
            + "        ca = gre.a;\n"
            + "    } else {\n"
            + "        float4 c0 = sampleC(sc);\n"
            + "        rgb = c0.rgb;\n"
            + "        ca = c0.a;\n"
            + "    }\n"
            // vibrancy (Kyant colorControls)
            + "    float lv = dot(rgb, float3(0.213, 0.715, 0.072));\n"
            + "    rgb = clamp(lv + (rgb - lv) * tone.x + tone.y, 0.0, 1.0);\n"
            // Figma fill stack
            + "    rgb = blendLayer(rgb, l1c.rgb, l1.x, l1.y);\n"
            + "    rgb = blendLayer(rgb, l2c.rgb, l2.x, l2.y);\n"
            + "    rgb = blendLayer(rgb, l3c.rgb, l3.x, l3.y);\n"
            // Kyant highlight: |dot(n, light)|^falloff on a soft band inside the edge (Plus)
            + "    float hi = pow(abs(dot(gn, lightDir)), light.w);\n"
            + "    float band = phi((light.y - t) / max(light.z, 0.15));\n"
            + "    rgb = min(rgb + light.x * hi * band, float3(1.0));\n"
            // Figma corner glows: inner shadows offset 0/-G and 0/+G, spread -G, Linear Dodge
            + "    if (glow.x > 0.0) {\n"
            + "        float2 hh = hs + float2(glow.y, glow.y);\n"
            + "        float rr = r + glow.y;\n"
            + "        float g = phi(sdRR(p + float2(0.0, glow.y), hh, rr) / glow.z)\n"
            + "                + phi(sdRR(p - float2(0.0, glow.y), hh, rr) / glow.z);\n"
            + "        rgb = min(rgb + glow.x * g, float3(1.0));\n"
            + "    }\n"
            // Figma hairlines: inner shadows offset 0/±h, Linear Dodge or Color
            + "    if (hairMode.x > 0.5) {\n"
            + "        float h = phi(sdRR(p + float2(0.0, hairC.w), hs, r) / hairMode.z)\n"
            + "                + phi(sdRR(p - float2(0.0, hairC.w), hs, r) / hairMode.z);\n"
            + "        h = clamp(h, 0.0, 1.0) * hairMode.y;\n"
            + "        if (hairMode.x < 1.5) {\n"
            + "            rgb = min(rgb + hairC.rgb * h, float3(1.0));\n"
            + "        } else {\n"
            + "            rgb = mix(rgb, setLum(hairC.rgb, lumF(rgb)), h);\n"
            + "        }\n"
            + "    }\n"
            + "    float a = inside * ca;\n"
            + "    if (edgeOnly > 0.5) {\n"
            + "        float bandW = max(lens.x, light.y + 2.0 * light.z);\n"
            + "        a *= clamp(bandW + 1.0 - t, 0.0, 1.0);\n"
            + "    }\n"
            + "    float o = 0.0;\n"
            + "    if (sd > -1.0) {\n"
            + "        float a1 = 1.0 - phi(sdRR(p + float2(sideLine.y, 0.0), hs, r) / 0.35);\n"
            + "        float a2 = 1.0 - phi(sdRR(p - float2(sideLine.y, 0.0), hs, r) / 0.35);\n"
            + "        o = clamp(a1 + a2, 0.0, 1.0) * sideLine.x * (1.0 - inside);\n"
            + "    }\n"
            + "    return half4(rgb * a, a + o);\n"
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

    private static void layer(RuntimeShader rs, String c, String a, float[] l) {
        rs.setFloatUniform(c, l[0], l[1], l[2], 0f);
        rs.setFloatUniform(a, l[3], l[4]);
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
            rs.setFloatUniform("lens", s.refractionHeight, s.refractionAmount, s.depth, s.dispersion);
            rs.setFloatUniform("tone", s.saturation, s.brightness);
            layer(rs, "l1c", "l1", s.layer1);
            layer(rs, "l2c", "l2", s.layer2);
            layer(rs, "l3c", "l3", s.layer3);
            rs.setFloatUniform("light", s.lightIntensity, s.lightWidth, s.lightSoftness, s.lightFalloff);
            rs.setFloatUniform("lightDir", s.lightX, s.lightY);
            rs.setFloatUniform("glow", s.glowAmount, s.glowOffset, s.glowSigma);
            rs.setFloatUniform("hairC", s.hairColor[0], s.hairColor[1], s.hairColor[2], s.hairOffset);
            rs.setFloatUniform("hairMode", s.hairMode, s.hairAmount, s.hairSigma);
            rs.setFloatUniform("sideLine", s.sideLine, s.sideOffset);
            rs.setFloatUniform("edgeOnly", s.edgeOnly ? 1f : 0f);
            rs.setFloatUniform("zoom", s.zoom);
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
        float[] l = s.layer1;
        int veil = Math.round(255f * Math.min(1f, l[3]));
        FALLBACK.setColor((veil << 24) | (Math.round(l[0] * 255f) << 16) | (Math.round(l[1] * 255f) << 8)
                | Math.round(l[2] * 255f));
        FALLBACK.setAlpha(Math.round(veil * Math.min(1f, alpha)));
        canvas.drawPath(PATH, FALLBACK);

        TMP.set(rect);
        float half = s.lightWidth / 2f;
        TMP.inset(half, half);
        ContinuousPath.build(PATH, TMP, Math.max(0f, s.radius - half), s.smoothing);
        RIM.setStrokeWidth(Math.max(1f, s.lightWidth));
        RIM.setColor(0xFFFFFFFF);
        RIM.setAlpha(Math.round(255f * Math.min(1f, s.lightIntensity) * Math.min(1f, alpha)));
        canvas.drawPath(PATH, RIM);
    }
}
