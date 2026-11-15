package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A slider with a liquid glass thumb, Kyant's LiquidSlider and DampedDragAnimation
 * (AndroidLiquidGlass catalog, Copyright 2025 Kyant, Apache License 2.0): a 6 dp capsule track,
 * filled in the accent colour up to the thumb; a 40 x 24 thumb, opaque white at rest. While it
 * is dragged the thumb's white fades out and it becomes glass: it swells to 1.5x on wobbly
 * springs, stretches with its speed and refracts the track under it (a lens with dispersion,
 * the rim light, an inner shadow). The thumb moves with the finger (relative, not to where it
 * is), a tap on the track springs it there. Optional ticks under the track and the iOS 26
 * Liquid Glass setting's two glyphs at its ends (clear and tinted).
 */
final class GlassSlider extends View {
    interface Listener {
        /** {@code done}: the finger let go (or a tap): the value to keep. */
        void onChanged(int value, boolean done);
    }

    private static final float THUMB_W = 40f;
    private static final float THUMB_H = 24f;
    private static final float TRACK_H = 6f;
    private static final float PRESSED = 1.5f;
    private static final float ICON = 30f;

    private final Ui ui;
    private final GlassPaint glass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Ui.Spring value = new Ui.Spring(1f, 1000f, 0.0005f);
    private final Ui.Spring press = new Ui.Spring(1f, 1000f, 0.001f);
    private final Ui.Spring scaleX = new Ui.Spring(0.6f, 250f, 0.001f);
    private final Ui.Spring scaleY = new Ui.Spring(0.7f, 250f, 0.001f);
    private final Ui.Spring velocity = new Ui.Spring(0.5f, 300f, 0.01f);
    private final Ui.Clock clock = new Ui.Clock();
    private final RectF track = new RectF();
    private final RectF thumb = new RectF();
    private final RectF tmp = new RectF();
    private final int slop;
    private final int min;
    private final int max;
    private float[] ticks;
    private boolean icons;
    private float fraction;
    private float lastValue;
    private boolean down;
    private boolean dragging;
    private float downX;
    private float downY;
    private float lastX;
    private boolean releasePending;
    private Listener listener;
    private final GlassPaint.Backdrop backdrop = c -> drawTrack(c, true);

    GlassSlider(Ui ui, int min, int max, int current) {
        super(ui.context);
        this.ui = ui;
        this.min = min;
        this.max = Math.max(min + 1, max);
        glass = new GlassPaint(ui.density);
        slop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        fraction = toFraction(current);
        value.snap(fraction);
        lastValue = fraction;
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        velocity.snap(0f);
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Small dots under the track at these values. */
    void setTicks(int... values) {
        ticks = new float[values.length];
        for (int i = 0; i < values.length; i++) ticks[i] = toFraction(values[i]);
        invalidate();
    }

    /** The Liquid Glass setting's glyphs: clear on the left, tinted on the right. */
    void setClearTintedIcons(boolean on) {
        icons = on;
        requestLayout();
        invalidate();
    }

    /** Set from elsewhere (a reset, an import): moved there. */
    void setValue(int v) {
        if (down) return;
        fraction = toFraction(v);
        value.target = fraction;
        invalidate();
    }

    int value() {
        return Math.round(min + fraction * (max - min));
    }

    private float toFraction(int v) {
        return Math.max(0f, Math.min(1f, (v - min) / (float) (max - min)));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), ui.px(ticks != null ? 48f : 44f));
    }

    private void layoutTrack() {
        float inset = ui.dp(THUMB_W / 4f) + (icons ? ui.dp(ICON + 8f) : 0f);
        float cy = ui.dp(22f);
        track.set(getPaddingLeft() + inset, cy - ui.dp(TRACK_H / 2f),
                getWidth() - getPaddingRight() - inset, cy + ui.dp(TRACK_H / 2f));
    }

    private float thumbCenter(float progress) {
        float w = ui.dp(THUMB_W);
        float x = -w / 2f + track.width() * progress;
        x = Math.max(-w / 4f, Math.min(track.width() - w * 3f / 4f, x));
        return track.left + x + w / 2f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                layoutTrack();
                down = true;
                dragging = false;
                downX = x;
                downY = y;
                lastX = x;
                clock.reset();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down) return false;
                if (!dragging) {
                    float dx = Math.abs(x - downX);
                    float dy = Math.abs(y - downY);
                    if (dx > slop && dx > dy) {
                        dragging = true;
                        lastX = downX + Math.signum(x - downX) * slop;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        releasePending = false;
                        press.target = 1f;
                        scaleX.target = PRESSED;
                        scaleY.target = PRESSED;
                        invalidate();
                    } else if (dy > slop) {
                        down = false;
                        return false;
                    }
                }
                if (dragging) {
                    float d = (x - lastX) / Math.max(1f, track.width());
                    lastX = x;
                    fraction = Math.max(0f, Math.min(1f, fraction + d));
                    value.target = fraction;
                    if (listener != null) listener.onChanged(value(), false);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!down) return false;
                down = false;
                if (!dragging) {
                    // Kyant's tap: the thumb swells, travels there and settles.
                    fraction = Math.max(0f, Math.min(1f, (x - track.left) / track.width()));
                    value.target = fraction;
                    press.target = 1f;
                    scaleX.target = PRESSED;
                    scaleY.target = PRESSED;
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                }
                dragging = false;
                releasePending = true;
                if (listener != null) listener.onChanged(value(), true);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!down) return false;
                down = false;
                if (dragging && listener != null) listener.onChanged(value(), true);
                dragging = false;
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
        if (releasePending && !dragging
                && Math.abs(value.value - value.target) < 0.025f) {
            releasePending = false;
            press.target = 0f;
            scaleX.target = 1f;
            scaleY.target = 1f;
        }
        m |= press.step(dt);
        m |= scaleX.step(dt);
        m |= scaleY.step(dt);
        m |= velocity.step(dt);
        return m || releasePending || dragging;
    }

    /** The track and its fill; under the glass, the card too. */
    private void drawTrack(Canvas c, boolean asBackdrop) {
        if (asBackdrop) {
            paint.setColor(ui.card);
            c.drawRect(track.left - ui.dp(40), track.top - ui.dp(30), track.right + ui.dp(40),
                    track.bottom + ui.dp(30), paint);
        }
        float r = track.height() / 2f;
        paint.setColor(ui.track);
        c.drawRoundRect(track, r, r, paint);
        float p = Math.max(0f, Math.min(1f, value.value));
        if (p > 0f) {
            tmp.set(track.left, track.top, track.left + track.width() * p, track.bottom);
            paint.setColor(ui.accent);
            c.drawRoundRect(tmp, r, r, paint);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean moving = step();
        layoutTrack();
        drawTrack(c, false);
        if (ticks != null) {
            paint.setColor(ui.tertiaryLabel);
            float ty = track.bottom + ui.dp(9f);
            for (float t : ticks) {
                c.drawCircle(track.left + track.width() * t, ty, ui.dp(1.5f), paint);
            }
        }
        if (icons) drawIcons(c);
        float vel = velocity.value / 10f;
        float sx = scaleX.value / (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.75f)));
        float sy = scaleY.value * (1f - Math.max(-0.2f, Math.min(0.2f, vel * 0.25f)));
        float cx = thumbCenter(Math.max(0f, Math.min(1f, value.value)));
        float cy = track.centerY();
        float hw = ui.dp(THUMB_W / 2f) * sx;
        float hh = ui.dp(THUMB_H / 2f) * sy;
        thumb.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float p = Math.max(0f, Math.min(1f, press.value));
        glass.surface = Ui.alpha(0xFFFFFFFF, 1f - p);
        glass.blur = ui.dp(8f) * (1f - p);
        glass.lensHeight = ui.dp(10f) * p;
        glass.lensAmount = ui.dp(14f) * p;
        glass.dispersion = true;
        glass.rimAlpha = p;
        glass.innerRadius = ui.dp(4f) * p;
        glass.innerOffset = ui.dp(4f) * p;
        glass.innerAlpha = 0.15f * p;
        glass.shadowRadius = ui.dp(4f);
        glass.shadowOffset = ui.dp(1f);
        glass.shadowAlpha = ui.night ? 0.3f : 0.12f;
        glass.pressAlpha = 0f;
        glass.draw(c, thumb, thumb.height() / 2f, p > 0.001f ? backdrop : null);
        if (moving) postInvalidateOnAnimation();
        else clock.reset();
    }

    /** Two overlapping rounded squares: outlined (clear) and filled (tinted). */
    private void drawIcons(Canvas c) {
        float s = ui.dp(13f);
        float cy = track.centerY();
        float lx = getPaddingLeft() + ui.dp(ICON / 2f);
        float rx = getWidth() - getPaddingRight() - ui.dp(ICON / 2f);
        paint.setStrokeWidth(ui.dp(1.6f));
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? lx : rx;
            float r = ui.dp(3.5f);
            // The back square, then the front one.
            tmp.set(x - s * 0.62f, cy - s * 0.62f, x + s * 0.28f, cy + s * 0.28f);
            paint.setColor(ui.secondaryLabel);
            paint.setStyle(side == 0 ? Paint.Style.STROKE : Paint.Style.FILL);
            if (side == 1) paint.setColor(Ui.alpha(ui.secondaryLabel, 0.55f));
            c.drawRoundRect(tmp, r, r, paint);
            tmp.set(x - s * 0.28f, cy - s * 0.28f, x + s * 0.62f, cy + s * 0.62f);
            if (side == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.card);
                c.drawRoundRect(tmp, r, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ui.secondaryLabel);
            } else {
                paint.setColor(ui.secondaryLabel);
            }
            c.drawRoundRect(tmp, r, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value()));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        float step = Math.max(1f / (max - min), 0.05f);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            fraction = Math.max(0f, Math.min(1f, fraction
                    + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step : -step)));
            value.target = fraction;
            if (listener != null) listener.onChanged(value(), true);
            invalidate();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
