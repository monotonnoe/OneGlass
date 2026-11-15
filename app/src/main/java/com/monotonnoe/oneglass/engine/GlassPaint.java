package com.monotonnoe.oneglass.engine;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * Liquid glass for the settings screen's controls, after Kyant's Backdrop (AndroidLiquidGlass,
 * Copyright 2025 Kyant, Apache License 2.0): {@code drawBackdrop} with its effects (vibrancy,
 * blur, lens with optional chromatic aberration), its surface, the Default highlight (a thin
 * rim lit along a 45 degree angle), the inner shadow, the drop shadow and the press highlight
 * of InteractiveHighlight (a light that follows the finger).
 *
 * <p>The backdrop is drawn again into a RenderNode in the glass's coordinates (Kyant's
 * layerBackdrop) and refracted by the lens as a RenderEffect; the surface, rim and shadows are
 * one shader over the shape. Android 13+ (RuntimeShader); otherwise, or if a shader cannot be
 * made, a flat rounded fill with a hairline rim.
 *
 * <p>Changes from Kyant's: Java on android.graphics, one shape (a rounded rectangle, a
 * capsule when the radius is half the height), the highlight and inner shadow folded into one
 * pass. Main thread.
 */
final class GlassPaint {
    /** Draws what is behind the glass, in the owner's coordinates. */
    interface Backdrop {
        void draw(Canvas c);
    }

    private static final String SDF = ""
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0) + 0.0001);\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    /**
     * Kyant's RoundedRectRefraction(WithDispersion)Shader, cut to the shape (its outside
     * transparent) and with the vibrancy (saturation) after the blur.
     */
    private static final String LENS = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float chromaticAberration;\n"
            + "uniform float saturation;\n"
            + SDF
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 vib(half4 c) {\n"
            + "    float l = dot(float3(c.rgb), float3(0.213, 0.715, 0.072));\n"
            + "    return half4(half3(clamp(mix(float3(l), float3(c.rgb), saturation), 0.0,"
            + " float(c.a))), c.a);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centered = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(centered, halfSize, radius);\n"
            + "    float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    if (inside <= 0.0) return half4(0.0);\n"
            + "    if (refractionHeight <= 0.0 || -sd >= refractionHeight) {\n"
            + "        return vib(content.eval(coord)) * half(inside);\n"
            + "    }\n"
            + "    float s = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -s / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius)"
            + " + 0.0001);\n"
            + "    float2 refracted = coord + d * grad;\n"
            + "    if (chromaticAberration <= 0.0) {\n"
            + "        return vib(content.eval(refracted)) * half(inside);\n"
            + "    }\n"
            + "    float k = chromaticAberration * ((centered.x * centered.y)"
            + " / (halfSize.x * halfSize.y));\n"
            + "    float2 dc = d * grad * k;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refracted + dc);\n"
            + "    color.r += red.r / 3.5; color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refracted + dc * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5; color.g += orange.g / 7.0;"
            + " color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refracted + dc * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5;"
            + " color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refracted);\n"
            + "    color.g += green.g / 3.5; color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refracted - dc * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refracted - dc * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refracted - dc);\n"
            + "    color.r += purple.r / 7.0; color.b += purple.b / 3.0;"
            + " color.a += purple.a / 7.0;\n"
            + "    return vib(color) * half(inside);\n"
            + "}\n";

    /**
     * Over the refracted backdrop: the surface (onDrawSurface), the inner shadow (the shape
     * offset down, its outside darkened inside the glass), the press highlight (InteractiveHighlight:
     * a white plus 8 %, and a light 15 % around the finger) and the rim (Highlight.Default:
     * a band at the edge, white along the 45 degree axis).
     */
    private static final String DECOR = ""
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "layout(color) uniform half4 surface;\n"
            // x: rim width, y: its blur, z: its alpha, w: the light's angle (radians).
            + "uniform float4 rim;\n"
            // x: inner shadow radius, y: its offset down, z: its alpha.
            + "uniform float3 inner;\n"
            // xy: the finger, z: the light's radius, w: the press progress.
            + "uniform float4 press;\n"
            + SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 c = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(c, halfSize, radius);\n"
            + "    float inside = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    if (inside <= 0.0) return half4(0.0);\n"
            + "    half4 col = half4(surface.rgb * surface.a, surface.a);\n"
            + "    if (inner.z > 0.0) {\n"
            + "        float so = sdRoundedRect(c - float2(0.0, inner.y), halfSize, radius);\n"
            + "        float a = inner.z * clamp((so + inner.x) / max(2.0 * inner.x, 0.001),"
            + " 0.0, 1.0);\n"
            + "        col = half4(col.rgb * half(1.0 - a), half(a) + col.a * half(1.0 - a));\n"
            + "    }\n"
            + "    if (press.w > 0.0) {\n"
            + "        float g = 0.08 * press.w + 0.15 * press.w"
            + " * smoothstep(press.z, press.z * 0.5, distance(coord, press.xy));\n"
            + "        col = half4(min(col.rgb + half(g), half3(1.0)), min(col.a + half(g), 1.0));\n"
            + "    }\n"
            + "    if (rim.z > 0.0) {\n"
            + "        float band = clamp(1.0 - (abs(sd + rim.x * 0.5) - rim.x * 0.5)"
            + " / max(rim.y, 0.001), 0.0, 1.0);\n"
            + "        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "        float2 grad = gradSdRoundedRect(c, halfSize, gradRadius);\n"
            + "        float l = abs(dot(grad, float2(cos(rim.w), sin(rim.w))));\n"
            + "        float w = band * rim.z * (0.25 + 0.75 * l);\n"
            + "        col = half4(min(col.rgb + half(w), half3(1.0)), min(col.a + half(w), 1.0));\n"
            + "    }\n"
            + "    return col * half(inside);\n"
            + "}\n";

    private static boolean broken = Build.VERSION.SDK_INT < 33;

    // The look, set by the owner before each draw (px, alphas 0..1).
    float lensHeight;
    float lensAmount;
    float blur;
    float saturation = 1f;
    boolean dispersion;
    int surface;
    float rimAlpha;
    float innerRadius;
    float innerOffset;
    float innerAlpha;
    float shadowRadius;
    float shadowOffset;
    float shadowAlpha;
    float pressX;
    float pressY;
    float pressAlpha;

    private final float density;
    private RenderNode node;
    private RuntimeShader lens;
    private RuntimeShader decor;
    private final Paint decorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shadowMaskRadius = -1f;
    private final RectF tmp = new RectF();

    GlassPaint(float density) {
        this.density = density;
    }

    /** Whether the glass is drawn with its shaders (else flat). */
    static boolean live() {
        return !broken;
    }

    /**
     * The glass over {@code r} (the owner's coordinates) with this corner radius, refracting
     * what {@code backdrop} draws (none: only the surface and its light).
     */
    void draw(Canvas canvas, RectF r, float radius, Backdrop backdrop) {
        float w = r.width();
        float h = r.height();
        if (w < 1f || h < 1f) return;
        radius = Math.min(radius, Math.min(w, h) / 2f);
        drawShadow(canvas, r, radius);
        if (!broken && canvas.isHardwareAccelerated()) {
            try {
                if (backdrop != null) drawBackdrop(canvas, r, radius, backdrop);
                drawDecor(canvas, r, radius);
                return;
            } catch (Throwable t) {
                broken = true;
                XLog.e("settings glass falls back to flat", t);
            }
        }
        drawFlat(canvas, r, radius, backdrop);
    }

    private void drawShadow(Canvas canvas, RectF r, float radius) {
        if (shadowAlpha <= 0f || shadowRadius <= 0f) return;
        if (shadowMaskRadius != shadowRadius) {
            shadowMaskRadius = shadowRadius;
            shadow.setMaskFilter(new BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL));
        }
        shadow.setColor(Ui.alpha(0xFF000000, shadowAlpha));
        tmp.set(r);
        tmp.offset(0f, shadowOffset);
        canvas.drawRoundRect(tmp, radius, radius, shadow);
    }

    private void drawBackdrop(Canvas canvas, RectF r, float radius, Backdrop backdrop) {
        int w = (int) Math.ceil(r.width());
        int h = (int) Math.ceil(r.height());
        if (node == null) node = new RenderNode("BlurIconsControlGlass");
        node.setPosition(0, 0, w, h);
        RecordingCanvas rc = node.beginRecording(w, h);
        try {
            rc.translate(-r.left, -r.top);
            backdrop.draw(rc);
        } finally {
            node.endRecording();
        }
        if (lens == null) lens = new RuntimeShader(LENS);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("radius", radius);
        float lh = Math.min(lensHeight, radius);
        lens.setFloatUniform("refractionHeight", lh);
        lens.setFloatUniform("refractionAmount", lh > 0f ? -Math.min(lensAmount, Math.min(w, h))
                : 0f);
        lens.setFloatUniform("chromaticAberration", dispersion ? 1f : 0f);
        lens.setFloatUniform("saturation", saturation);
        RenderEffect e = RenderEffect.createRuntimeShaderEffect(lens, "content");
        if (blur > 0.5f) {
            e = RenderEffect.createChainEffect(e,
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
        node.setRenderEffect(e);
        canvas.save();
        canvas.translate(r.left, r.top);
        ((RecordingCanvas) canvas).drawRenderNode(node);
        canvas.restore();
    }

    private void drawDecor(Canvas canvas, RectF r, float radius) {
        if (decor == null) decor = new RuntimeShader(DECOR);
        float w = r.width();
        float h = r.height();
        decor.setFloatUniform("size", w, h);
        decor.setFloatUniform("radius", radius);
        decor.setColorUniform("surface", surface);
        decor.setFloatUniform("rim", 0.5f * density, 0.25f * density, rimAlpha * 0.5f,
                (float) Math.toRadians(45));
        decor.setFloatUniform("inner", Math.max(0.001f, innerRadius), innerOffset, innerAlpha);
        decor.setFloatUniform("press", pressX - r.left, pressY - r.top, Math.min(w, h) * 1.5f,
                pressAlpha);
        decorPaint.setShader(decor);
        canvas.save();
        canvas.translate(r.left, r.top);
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, decorPaint);
        canvas.restore();
    }

    /** Without the shaders: the backdrop as it is, the surface and a hairline rim. */
    private void drawFlat(Canvas canvas, RectF r, float radius, Backdrop backdrop) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        int base = surface;
        // A thumb turning to glass has a clear surface: without the lens it stays visible.
        if (((base >>> 24) & 0xFF) < 0x40) base = Ui.alpha(0xFFFFFFFF, backdrop != null ? 0.6f : 0.25f);
        fill.setColor(base);
        canvas.drawRoundRect(r, radius, radius, fill);
        if (pressAlpha > 0f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * pressAlpha));
            canvas.drawRoundRect(r, radius, radius, fill);
        }
        if (rimAlpha > 0f) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1f, 0.5f * density));
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.5f * rimAlpha));
            canvas.drawRoundRect(r, radius, radius, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }
}
