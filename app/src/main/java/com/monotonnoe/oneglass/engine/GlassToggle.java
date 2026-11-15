package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * An on/off switch with a liquid glass thumb, Kyant's LiquidToggle and DampedDragAnimation
 * (AndroidLiquidGlass catalog, Copyright 2025 Kyant, Apache License 2.0), as Control Center's
 * port of it: a 64 x 28 track, grey to green with the thumb's place; a 40 x 24 thumb, 2 in.
 * At rest the thumb is opaque white; pressed, its white fades out and it becomes glass (a lens
 * with dispersion over the track, the rim light, an inner shadow), swells to 1.5x on wobbly
 * springs and stretches with its speed. A tap flips it; a drag moves it and it springs to the
 * nearer end on release.
 */
final class GlassToggle extends View {
    interface Listener {
        void onChanged(boolean on);
    }

    private static final float W = 64f;
    private static final float H = 28f;
    private static final float THUMB_W = 40f;
    private static final float THUMB_H = 24f;
    private static final float PAD = 2f;
    private static final float TRAVEL = W - THUMB_W - 2f * PAD;
    private static final float PRESSED = 1.5f;

    private final Ui ui;
    private final GlassPaint glass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Ui.Spring value = new Ui.Spring(1f, 1000f, 0.001f);
    private final Ui.Spring press = new Ui.Spring(1f, 1000f, 0.001f);
    private final Ui.Spring scaleX = new Ui.Spring(0.6f, 250f, 0.001f);
    private final Ui.Spring scaleY = new Ui.Spring(0.7f, 250f, 0.001f);
    private final Ui.Spring velocity = new Ui.Spring(0.5f, 300f, 0.01f);
    private final Ui.Clock clock = new Ui.Clock();
    private final RectF track = new RectF();
    private final RectF thumb = new RectF();
    private final int slop;
    private boolean on;
    private float fraction;
    private float lastValue;
    private boolean down;
    private boolean dragged;
    private float downX;
    private float lastX;
    private boolean releasePending;
    private Listener listener;
    private final GlassPaint.Backdrop backdrop = c -> drawTrack(c);

    GlassToggle(Ui ui, boolean on) {
        super(ui.context);
        this.ui = ui;
        glass = new GlassPaint(ui.density);
        slop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        this.on = on;
        fraction = on ? 1f : 0f;
        value.snap(fraction);
        lastValue = fraction;
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        velocity.snap(0f);
        setFocusable(true);
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOn() {
        return on;
    }

    /** Set from elsewhere (a reset): moved without the press. */
    void setOn(boolean v) {
        if (v == on) return;
        on = v;
        fraction = v ? 1f : 0f;
        value.target = fraction;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(ui.px(W + 24f), ui.px(44f));
    }

    @Override
    public boolean performClick() {
        super.performClick();
        flip();
        return true;
    }

    private void flip() {
        on = !on;
        fraction = on ? 1f : 0f;
        value.target = fraction;
        pressIn();
        releasePending = true;
        invalidate();
        if (listener != null) listener.onChanged(on);
    }

    private void pressIn() {
        press.target = 1f;
        scaleX.target = PRESSED;
        scaleY.target = PRESSED;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                dragged = false;
                downX = x;
                lastX = x;
                releasePending = false;
                pressIn();
                clock.reset();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down) return false;
                if (!dragged && Math.abs(x - downX) > slop) {
                    dragged = true;
                    lastX = downX + Math.signum(x - downX) * slop;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragged) {
                    float d = (x - lastX) / Math.max(1f, ui.dp(TRAVEL));
                    lastX = x;
                    fraction = Math.max(0f, Math.min(1f, fraction + d));
                    value.target = fraction;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!down) return false;
                down = false;
                if (dragged) {
                    boolean to = fraction >= 0.5f;
                    fraction = to ? 1f : 0f;
                    value.target = fraction;
                    releasePending = true;
                    if (to != on) {
                        on = to;
                        if (listener != null) listener.onChanged(on);
                    }
                    invalidate();
                } else {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!down) return false;
                down = false;
                dragged = false;
                fraction = on ? 1f : 0f;
                value.target = fraction;
                releasePending = true;
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
        velocity.target = m ? v : 0f;
        if (releasePending && !down && Math.abs(value.value - value.target) < 0.025f) {
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

    private void drawTrack(Canvas c) {
        paint.setColor(ui.card);
        c.drawRect(track.left - ui.dp(20), track.top - ui.dp(20), track.right + ui.dp(20),
                track.bottom + ui.dp(20), paint);
        float v = Math.max(0f, Math.min(1f, value.value));
        paint.setColor(Ui.mix(ui.track, ui.green, v));
        float r = track.height() / 2f;
        c.drawRoundRect(track, r, r, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean moving = step();
        float right = getWidth() - ui.dp(2f);
        float cy = getHeight() / 2f;
        track.set(right - ui.dp(W), cy - ui.dp(H / 2f), right, cy + ui.dp(H / 2f));
        if (!isEnabled()) c.saveLayerAlpha(null, 100);
        paint.setColor(Ui.mix(ui.track, ui.green, Math.max(0f, Math.min(1f, value.value))));
        float tr = track.height() / 2f;
        c.drawRoundRect(track, tr, tr, paint);
        // Kyant's layerBlock: the press's scale, then the velocity's stretch.
        float vel = velocity.value / 50f;
        float sx = scaleX.value / (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.75f)));
        float sy = scaleY.value * (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.25f)));
        float cx = track.left + ui.dp(PAD + THUMB_W / 2f + TRAVEL * value.value);
        float hw = ui.dp(THUMB_W / 2f) * sx;
        float hh = ui.dp(THUMB_H / 2f) * sy;
        thumb.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float p = Math.max(0f, Math.min(1f, press.value));
        glass.surface = Ui.alpha(0xFFFFFFFF, 1f - p);
        glass.lensHeight = ui.dp(5f) * p;
        glass.lensAmount = ui.dp(10f) * p;
        glass.dispersion = true;
        glass.blur = 0f;
        glass.rimAlpha = p;
        glass.innerRadius = ui.dp(4f) * p;
        glass.innerOffset = ui.dp(4f) * p;
        glass.innerAlpha = 0.15f * p;
        glass.shadowRadius = ui.dp(4f);
        glass.shadowOffset = ui.dp(1f);
        glass.shadowAlpha = 0.12f;
        glass.pressAlpha = 0f;
        glass.draw(c, thumb, thumb.height() / 2f, p > 0.001f ? backdrop : null);
        if (!isEnabled()) c.restore();
        if (moving) postInvalidateOnAnimation();
        else clock.reset();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(on);
    }
}
