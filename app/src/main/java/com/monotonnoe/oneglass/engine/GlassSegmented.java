package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A segmented control whose selection is a liquid glass capsule, after Kyant's LiquidBottomTabs
 * (AndroidLiquidGlass catalog, Copyright 2025 Kyant, Apache License 2.0): at rest the
 * selected segment is a plain capsule (white, dark grey at night); pressed or dragged it swells
 * on wobbly springs and becomes glass that refracts the segments under it (a lens with
 * dispersion, the rim light, an inner shadow), follows the finger and springs to the nearest
 * segment on release. A tap on another segment springs it there.
 */
final class GlassSegmented extends View {
    interface Listener {
        void onSelected(int index);
    }

    private static final float H = 32f;
    private static final float INSET = 2f;
    private static final float PRESSED = 1.3f;

    private final Ui ui;
    private final String[] labels;
    private final GlassPaint glass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Ui.Spring value = new Ui.Spring(1f, 1000f, 0.001f);
    private final Ui.Spring press = new Ui.Spring(1f, 1000f, 0.001f);
    private final Ui.Spring scaleX = new Ui.Spring(0.6f, 250f, 0.001f);
    private final Ui.Spring scaleY = new Ui.Spring(0.7f, 250f, 0.001f);
    private final Ui.Spring velocity = new Ui.Spring(0.5f, 300f, 0.01f);
    private final Ui.Clock clock = new Ui.Clock();
    private final RectF bar = new RectF();
    private final RectF thumb = new RectF();
    private final int slop;
    private int selected;
    private float fraction;
    private float lastValue;
    private boolean down;
    private boolean onThumb;
    private boolean dragging;
    private float downX;
    private float lastX;
    private boolean releasePending;
    private Listener listener;
    private final GlassPaint.Backdrop backdrop = c -> drawBase(c, true);

    GlassSegmented(Ui ui, String[] labels, int selected) {
        super(ui.context);
        this.ui = ui;
        this.labels = labels;
        glass = new GlassPaint(ui.density);
        slop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        this.selected = Math.max(0, Math.min(labels.length - 1, selected));
        fraction = this.selected;
        value.snap(fraction);
        lastValue = fraction;
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        velocity.snap(0f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(ui.sp(13f));
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setSelected(int index) {
        index = Math.max(0, Math.min(labels.length - 1, index));
        if (index == selected || down) return;
        selected = index;
        fraction = index;
        value.target = index;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), ui.px(44f));
    }

    private float segment() {
        return (bar.width() - 2f * ui.dp(INSET)) / labels.length;
    }

    private void layoutBar() {
        float cy = getHeight() / 2f;
        bar.set(getPaddingLeft(), cy - ui.dp(H / 2f), getWidth() - getPaddingRight(),
                cy + ui.dp(H / 2f));
    }

    private int indexAt(float x) {
        int i = (int) ((x - bar.left - ui.dp(INSET)) / segment());
        return Math.max(0, Math.min(labels.length - 1, i));
    }

    private void pressIn() {
        press.target = 1f;
        scaleX.target = PRESSED;
        scaleY.target = PRESSED;
    }

    private void choose(int index) {
        fraction = index;
        value.target = index;
        releasePending = true;
        if (index != selected) {
            selected = index;
            if (listener != null) listener.onSelected(index);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                layoutBar();
                down = true;
                dragging = false;
                downX = x;
                lastX = x;
                onThumb = indexAt(x) == selected;
                releasePending = false;
                if (onThumb) pressIn();
                clock.reset();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down) return false;
                if (!dragging && onThumb && Math.abs(x - downX) > slop) {
                    dragging = true;
                    lastX = downX + Math.signum(x - downX) * slop;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    fraction = Math.max(0f, Math.min(labels.length - 1f,
                            fraction + (x - lastX) / segment()));
                    lastX = x;
                    value.target = fraction;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!down) return false;
                down = false;
                if (dragging) {
                    choose(Math.round(fraction));
                } else {
                    pressIn();
                    choose(indexAt(x));
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                }
                dragging = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!down) return false;
                down = false;
                dragging = false;
                choose(selected);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private boolean step() {
        float dt = clock.tick();
        boolean m = value.step(dt);
        float v = (value.value - lastValue) / dt;
        lastValue = value.value;
        velocity.target = m ? v / labels.length : 0f;
        if (releasePending && !down && Math.abs(value.value - value.target) < 0.05f) {
            releasePending = false;
            press.target = 0f;
            scaleX.target = 1f;
            scaleY.target = 1f;
        }
        m |= press.step(dt);
        m |= scaleX.step(dt);
        m |= scaleY.step(dt);
        m |= velocity.step(dt);
        return m || releasePending || down;
    }

    /** The track and the labels; under the glass, the card too. */
    private void drawBase(Canvas c, boolean asBackdrop) {
        if (asBackdrop) {
            paint.setColor(ui.card);
            c.drawRect(bar.left - ui.dp(30), bar.top - ui.dp(30), bar.right + ui.dp(30),
                    bar.bottom + ui.dp(30), paint);
        }
        float r = bar.height() / 2f;
        paint.setColor(ui.segmentTrack);
        c.drawRoundRect(bar, r, r, paint);
        if (asBackdrop) drawLabels(c, 1f);
    }

    private void drawLabels(Canvas c, float alpha) {
        if (alpha <= 0.01f) return;
        float seg = segment();
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = bar.centerY() - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < labels.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(value.value - i));
            text.setTypeface(near > 0.5f ? ui.semibold : ui.medium);
            text.setColor(Ui.alpha(ui.label, alpha));
            String s = labels[i];
            float avail = seg - ui.dp(8f);
            float tw = text.measureText(s);
            float scale = tw > avail ? avail / tw : 1f;
            float cx = bar.left + ui.dp(INSET) + seg * (i + 0.5f);
            c.save();
            c.scale(scale, 1f, cx, baseline);
            c.drawText(s, cx, baseline, text);
            c.restore();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean moving = step();
        layoutBar();
        drawBase(c, false);
        drawLabels(c, 1f);
        float seg = segment();
        float vel = velocity.value;
        float sx = scaleX.value / (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.75f)));
        float sy = scaleY.value * (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.25f)));
        float cx = bar.left + ui.dp(INSET) + seg * (value.value + 0.5f);
        float cy = bar.centerY();
        float hw = seg / 2f * sx;
        float hh = (bar.height() / 2f - ui.dp(INSET)) * sy;
        thumb.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float p = Math.max(0f, Math.min(1f, press.value));
        glass.surface = Ui.alpha(ui.segmentThumb, 1f - p);
        glass.blur = 0f;
        glass.lensHeight = ui.dp(8f) * p;
        glass.lensAmount = ui.dp(12f) * p;
        glass.dispersion = true;
        glass.rimAlpha = p;
        glass.innerRadius = ui.dp(6f) * p;
        glass.innerOffset = ui.dp(6f) * p;
        glass.innerAlpha = 0.15f * p;
        glass.shadowRadius = ui.dp(4f);
        glass.shadowOffset = ui.dp(1f);
        glass.shadowAlpha = ui.night ? 0.2f : 0.1f;
        glass.pressAlpha = 0f;
        glass.draw(c, thumb, thumb.height() / 2f, p > 0.001f ? backdrop : null);
        // Over the thumb at rest; while it is glass, only what it refracts.
        drawLabels(c, 1f - p);
        if (moving) postInvalidateOnAnimation();
        else clock.reset();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.RadioGroup");
        info.setText(labels[selected]);
        info.setClickable(true);
    }
}
