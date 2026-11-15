package com.monotonnoe.oneglass.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 * A lock screen notification card drawn as refracting glass, when the refraction setting is
 * on: the picture behind it (the one the Home screen sends for the glass clock) blurred, and
 * pulled inwards near the card's edge as through a thick lens, so the edge bends what is
 * behind it as Liquid Glass does. The compositor's blur cannot do that: it only blurs. The
 * node is drawn clipped to the card's squircle; its content follows the card on screen
 * (re-recorded in the pre-draw pass when the card moves). Android 13+, System UI.
 */
final class LockCard {
    private static final String LENS = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float amount;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 c = size * 0.5;\n"
            + "    float2 q = abs(p - c) - (c - radius);\n"
            // The distance to the card's edge, negative inside (a rounded box).
            + "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    float2 s = sign(p - c);\n"
            + "    float2 n;\n"
            + "    if (q.x > 0.0 && q.y > 0.0) { n = normalize(q) * s; }\n"
            + "    else if (q.x > q.y) { n = float2(s.x, 0.0); }\n"
            + "    else { n = float2(0.0, s.y); }\n"
            // 1 at the edge, 0 a band's depth in: the edge shows what lies further in.
            + "    float t = clamp(1.0 + d / band, 0.0, 1.0);\n"
            + "    float2 sp = p - n * amount * t * t;\n"
            + "    sp = clamp(sp, float2(0.5), size - 0.5);\n"
            + "    return content.eval(sp);\n"
            + "}\n";

    private final RenderNode node = new RenderNode("BlurIconsLockCard");
    private final Matrix toScreen = new Matrix();
    private final Matrix fromScreen = new Matrix();
    private final float[] recorded = new float[9];
    private final float[] current = new float[9];
    private final Path clip = new Path();
    /** Clipped to a circle (the lock screen's buttons), not the card's squircle. */
    boolean round;
    private int w;
    private int h;
    /**
     * The node's own size: half the card's when the blur is strong enough (radius 8 px and
     * more) that half the pixels show the same, as the picture itself is about half the
     * screen's resolution. Its layer and its blurred copy then take a quarter of the memory;
     * the node is drawn scaled up to the card, and the clip stays at full resolution.
     */
    private int nw;
    private int nh;
    private int left;
    private int top;
    /** The lens's program, compiled once: a new size only sets its uniforms. */
    private RuntimeShader lens;
    private int effectW;
    private int effectH;
    private float effectRadius;
    private int effectBlur;
    private int effectRefraction;

    /** Draws the card's glass into {@code canvas}; false when there is no picture to use. */
    boolean draw(Canvas canvas, View v, int left, int top, int w, int h, float radius,
                 int blur, int refraction) throws Exception {
        return draw(canvas, v, left, top, w, h, radius, blur, refraction, 0f);
    }

    /**
     * As above; with {@code hole} > 0 only what lies outside a round rect of that corner
     * radius is drawn: the compositor's blur covers the round rect, this the squircle's corners
     * past it, so the blurred shape is the squircle exactly.
     */
    boolean draw(Canvas canvas, View v, int left, int top, int w, int h, float radius,
                 int blur, int refraction, float hole) throws Exception {
        if (!(canvas instanceof RecordingCanvas) || LockGlass.picture() == null) return false;
        this.left = left;
        this.top = top;
        boolean half = blur >= HALF_FROM && w >= 8 && h >= 8;
        int nw = half ? (w + 1) / 2 : w;
        int nh = half ? (h + 1) / 2 : h;
        if (w != this.w || h != this.h || nw != this.nw || nh != this.nh) {
            this.w = w;
            this.h = h;
            this.nw = nw;
            this.nh = nh;
            node.setPosition(0, 0, nw, nh);
            node.setPivotX(0f);
            node.setPivotY(0f);
            node.setScaleX(w / (float) nw);
            node.setScaleY(h / (float) nh);
        }
        // One UI animates a card's clip and height frame by frame: then only the uniforms
        // change (6.67 compiled the program anew each frame, the cards stuttered).
        if (lens == null || w != effectW || h != effectH || radius != effectRadius
                || blur != effectBlur || refraction != effectRefraction) {
            effectW = w;
            effectH = h;
            effectRadius = radius;
            effectBlur = blur;
            effectRefraction = refraction;
            if (lens == null) lens = new RuntimeShader(LENS);
            // The lens works in the node's pixels: the card's lengths times k.
            float k = nw / (float) w;
            float min = Math.min(w, h);
            lens.setFloatUniform("size", nw, nh);
            lens.setFloatUniform("radius", Math.min(radius * 1.2f, min / 2f) * k);
            // As iOS's cards: the bend lies in a narrow band at the very edge (about 6 dp; the
            // middle of the card stays flat), what is behind squeezed into it from up to 8 dp
            // further in at 100 %.
            float dp = v.getResources().getDisplayMetrics().density;
            lens.setFloatUniform("band", Math.min(min * 0.32f, 6f * dp) * k);
            lens.setFloatUniform("amount", 8f * dp * refraction / 100f * k);
            // At half size the same blur on screen: half the sigma (sigma = 0.57735 r + 0.5).
            float br = nw != w ? blur / 2f - 0.433f : blur;
            RenderEffect blurred = blur > 0
                    ? RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP) : null;
            // The effect keeps the uniforms as they are now: the shader can be set again.
            RenderEffect bend = RenderEffect.createRuntimeShaderEffect(lens, "content");
            node.setRenderEffect(blurred != null ? RenderEffect.createChainEffect(bend, blurred)
                    : bend);
        }
        if (!record(v)) return false;
        clip.reset();
        if (round) {
            clip.addOval(0, 0, w, h, Path.Direction.CW);
        } else {
            LockNotifications.squircle(clip, 0, 0, w, h, radius);
        }
        canvas.save();
        canvas.translate(left, top);
        canvas.clipPath(clip);
        if (hole > 0f) {
            HOLE.reset();
            HOLE.addRoundRect(0, 0, w, h, hole, hole, Path.Direction.CW);
            canvas.clipOutPath(HOLE);
        }
        ((RecordingCanvas) canvas).drawRenderNode(node);
        canvas.restore();
        return true;
    }

    /** The picture as it lies behind the card on screen, now. */
    private boolean record(View v) {
        Bitmap pic = LockGlass.picture();
        if (pic == null) return false;
        toScreen.reset();
        v.transformMatrixToGlobal(toScreen);
        toScreen.preTranslate(left, top);
        toScreen.getValues(recorded);
        if (!toScreen.invert(fromScreen)) return false;
        RecordingCanvas rc = node.beginRecording(nw, nh);
        try {
            if (nw != w || nh != h) rc.scale(nw / (float) w, nh / (float) h);
            rc.concat(fromScreen);
            LockGlass.concatPictureToScreen(rc);
            rc.drawBitmap(pic, 0f, 0f, PAINT);
        } finally {
            node.endRecording();
        }
        return true;
    }

    /** Follows the card on screen (scrolling, the lock screen's animations). Pre-draw. */
    void follow(View v) {
        if (w <= 0 || node == null || !node.hasDisplayList()) return;
        toScreen.reset();
        v.transformMatrixToGlobal(toScreen);
        toScreen.preTranslate(left, top);
        toScreen.getValues(current);
        for (int i = 0; i < 9; i++) {
            float tol = i == Matrix.MTRANS_X || i == Matrix.MTRANS_Y ? 0.5f : 0.002f;
            if (Math.abs(current[i] - recorded[i]) > tol) {
                record(v);
                return;
            }
        }
    }

    void release() {
        node.discardDisplayList();
        effectW = 0;
        w = 0;
        h = 0;
        nw = 0;
        nh = 0;
    }

    /** The blur radius (px) from which the node is drawn at half size. */
    private static final int HALF_FROM = 8;

    private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Path HOLE = new Path();
}
