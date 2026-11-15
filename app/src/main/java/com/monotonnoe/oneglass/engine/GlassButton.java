package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A liquid glass button, Kyant's LiquidButton with its InteractiveHighlight (AndroidLiquidGlass
 * catalog, Copyright 2025 Kyant, Apache License 2.0): a capsule (or a circle) of glass over
 * what is behind it (vibrancy, a 2 dp blur, a 12 / 24 dp lens), its surface, the rim light and
 * a soft shadow. Pressed, it grows by 4 dp, a light follows the finger and the glass leans a
 * little towards where the finger drags it, springing back on release.
 */
final class GlassButton extends View {
    static final int GLYPH_NONE = 0;
    static final int GLYPH_BACK = 1;
    static final int GLYPH_PHOTO = 2;

    /** Room around the shape for its growth and shadow. */
    private static final float MARGIN = 4f;

    private final Ui ui;
    private final GlassPaint glass;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Ui.Spring press = new Ui.Spring(0.5f, 300f, 0.001f);
    private final Ui.Spring offX = new Ui.Spring(0.5f, 300f, 0.5f);
    private final Ui.Spring offY = new Ui.Spring(0.5f, 300f, 0.5f);
    private final Ui.Clock clock = new Ui.Clock();
    private final RectF shape = new RectF();
    private final Path path = new Path();
    private final String label;
    private final int glyph;
    private final boolean circle;
    private int surface;
    private int textColor;
    private GlassPaint.Backdrop backdrop;
    private float downX;
    private float downY;
    private float fingerX;
    private float fingerY;
    private boolean down;

    GlassButton(Ui ui, String label, int glyph, boolean circle) {
        super(ui.context);
        this.ui = ui;
        this.label = label;
        this.glyph = glyph;
        this.circle = circle;
        glass = new GlassPaint(ui.density);
        press.snap(0f);
        offX.snap(0f);
        offY.snap(0f);
        surface = ui.night ? 0x99303034 : 0x99FFFFFF;
        textColor = ui.label;
        text.setTextSize(ui.sp(Ui.BODY));
        text.setTypeface(ui.medium);
        text.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
        if (label != null) setContentDescription(label);
    }

    void setColors(int surface, int textColor) {
        this.surface = surface;
        this.textColor = textColor;
        invalidate();
    }

    /** What is behind the button, drawn in the button's coordinates; null for nothing. */
    void setBackdrop(GlassPaint.Backdrop b) {
        backdrop = b;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float h = circle ? 44f : 48f;
        int height = ui.px(h + 2f * MARGIN);
        int width;
        if (circle) {
            width = height;
        } else if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) {
            width = MeasureSpec.getSize(wSpec);
        } else {
            float content = (label != null ? text.measureText(label) : 0f)
                    + (glyph != GLYPH_NONE ? ui.dp(label != null ? 28f : 20f) : 0f);
            width = Math.round(content + ui.dp(32f + 2f * MARGIN));
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.AT_MOST) {
                width = Math.min(width, MeasureSpec.getSize(wSpec));
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                downX = e.getX();
                downY = e.getY();
                fingerX = downX;
                fingerY = downY;
                press.target = 1f;
                clock.reset();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down) return false;
                fingerX = e.getX();
                fingerY = e.getY();
                offX.snap(fingerX - downX);
                offY.snap(fingerY - downY);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!down) return false;
                down = false;
                press.target = 0f;
                offX.target = 0f;
                offY.target = 0f;
                boolean inside = e.getX() >= -ui.dp(16) && e.getX() <= getWidth() + ui.dp(16)
                        && e.getY() >= -ui.dp(16) && e.getY() <= getHeight() + ui.dp(16);
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && inside) performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float dt = clock.tick();
        boolean moving = press.step(dt) | offX.step(dt) | offY.step(dt) | down;
        float m = ui.dp(MARGIN);
        float w = getWidth() - 2f * m;
        float h = getHeight() - 2f * m;
        float p = Math.max(0f, press.value);
        // InteractiveHighlight's layer: 4 dp larger, leaning towards the drag (tanh).
        float maxOffset = Math.min(w, h);
        float ox = maxOffset * (float) Math.tanh(0.05f * offX.value / maxOffset);
        float oy = maxOffset * (float) Math.tanh(0.05f * offY.value / maxOffset);
        float scale = 1f + ui.dp(4f) / h * p;
        float cx = getWidth() / 2f + ox;
        float cy = getHeight() / 2f + oy;
        shape.set(cx - w / 2f * scale, cy - h / 2f * scale, cx + w / 2f * scale,
                cy + h / 2f * scale);
        glass.surface = surface;
        glass.blur = ui.dp(2f);
        glass.saturation = 1.5f;
        glass.lensHeight = ui.dp(12f);
        glass.lensAmount = ui.dp(24f);
        glass.dispersion = false;
        glass.rimAlpha = 1f;
        glass.innerRadius = 0f;
        glass.innerOffset = 0f;
        glass.innerAlpha = 0f;
        glass.shadowRadius = ui.dp(8f);
        glass.shadowOffset = ui.dp(2f);
        glass.shadowAlpha = ui.night ? 0.25f : 0.08f;
        glass.pressX = fingerX;
        glass.pressY = fingerY;
        glass.pressAlpha = Math.min(1f, p);
        glass.draw(c, shape, shape.height() / 2f, backdrop);
        drawContent(c, cx, cy);
        if (moving) postInvalidateOnAnimation();
        else clock.reset();
    }

    private void drawContent(Canvas c, float cx, float cy) {
        float alpha = isEnabled() ? 1f : 0.35f;
        int color = Ui.alpha(textColor, alpha);
        float gw = glyph == GLYPH_NONE ? 0f : ui.dp(label != null ? 28f : 20f);
        float tw = label != null ? text.measureText(label) : 0f;
        float start = cx - (gw + tw) / 2f;
        if (glyph != GLYPH_NONE) {
            float gx = label != null ? start + ui.dp(10f) : cx;
            drawGlyph(c, gx, cy, color);
        }
        if (label != null) {
            text.setColor(color);
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, start + gw + tw / 2f, cy - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    private void drawGlyph(Canvas c, float x, float y, int color) {
        stroke.setColor(color);
        path.reset();
        if (glyph == GLYPH_BACK) {
            stroke.setStrokeWidth(ui.dp(2.6f));
            float s = ui.dp(8.5f);
            path.moveTo(x + s * 0.35f, y - s);
            path.lineTo(x - s * 0.65f, y);
            path.lineTo(x + s * 0.35f, y + s);
            c.drawPath(path, stroke);
        } else if (glyph == GLYPH_PHOTO) {
            stroke.setStrokeWidth(ui.dp(1.8f));
            float s = ui.dp(9f);
            c.drawRoundRect(x - s, y - s * 0.78f, x + s, y + s * 0.78f, ui.dp(3f), ui.dp(3f),
                    stroke);
            path.moveTo(x - s, y + s * 0.45f);
            path.lineTo(x - s * 0.3f, y - s * 0.15f);
            path.lineTo(x + s * 0.25f, y + s * 0.35f);
            path.lineTo(x + s * 0.55f, y + s * 0.1f);
            path.lineTo(x + s, y + s * 0.45f);
            c.drawPath(path, stroke);
            c.drawCircle(x + s * 0.45f, y - s * 0.35f, ui.dp(1.5f), stroke);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
    }
}
