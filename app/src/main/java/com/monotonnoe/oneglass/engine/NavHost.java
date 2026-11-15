package com.monotonnoe.oneglass.engine;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings' navigation stack: pages pushed in from the right over the one before, which
 * slides a third of the way left and dims, and popped back the same way, as iOS's navigation
 * controller; a swipe from the left edge drags the top page back and lets go to pop it or not.
 */
final class NavHost extends FrameLayout {
    private static final long DURATION = 380;
    /** iOS's navigation transition: quick out of the start, a long soft settle. */
    private static final PathInterpolator CURVE = new PathInterpolator(0.2f, 0.85f, 0.15f, 1f);

    private final Ui ui;
    private final List<Page> stack = new ArrayList<>();
    private Insets insets = Insets.NONE;
    /** The top page's way out: 0 shown, 1 off to the right. */
    private float t;
    private boolean moving;
    private ValueAnimator animator;
    private final int slop;
    private final int edge;
    private boolean edgeDown;
    private boolean swiping;
    private float downX;
    private float downY;
    private VelocityTracker velocity;

    NavHost(Ui ui) {
        super(ui.context);
        this.ui = ui;
        slop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        edge = ui.px(24);
        setBackgroundColor(ui.background);
        setOnApplyWindowInsetsListener((v, in) -> {
            insets = in.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            for (Page p : stack) p.applyInsets(insets);
            return in;
        });
    }

    List<Page> pages() {
        return stack;
    }

    Page top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    boolean busy() {
        return moving || swiping;
    }

    void push(Page page, boolean animate) {
        if (moving) return;
        page.applyInsets(insets);
        page.setOnBack(() -> pop());
        stack.add(page);
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (!animate || stack.size() < 2) {
            settle(0f);
            showOnlyTop();
            return;
        }
        page.setVisibility(View.VISIBLE);
        below().setVisibility(View.VISIBLE);
        place(1f);
        run(1f, 0f, DURATION, () -> showOnlyTop());
    }

    /** Pops the top page; false at the first one. */
    boolean pop() {
        if (stack.size() < 2) return false;
        if (moving) return true;
        below().setVisibility(View.VISIBLE);
        run(t, 1f, Math.max(120, Math.round(DURATION * (1f - t))), () -> removeTop());
        return true;
    }

    private Page below() {
        return stack.get(stack.size() - 2);
    }

    private void removeTop() {
        Page gone = stack.remove(stack.size() - 1);
        removeView(gone);
        settle(0f);
        showOnlyTop();
    }

    private void showOnlyTop() {
        for (int i = 0; i < stack.size(); i++) {
            Page p = stack.get(i);
            p.setVisibility(i == stack.size() - 1 ? View.VISIBLE : View.INVISIBLE);
            p.setTranslationX(0f);
        }
        invalidate();
    }

    private void settle(float v) {
        t = v;
        moving = false;
    }

    private void run(float from, float to, long ms, Runnable end) {
        if (animator != null) animator.cancel();
        moving = true;
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(ms);
        animator.setInterpolator(CURVE);
        animator.addUpdateListener(a -> place((Float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                moving = false;
                animator = null;
                if (!cancelled) end.run();
            }
        });
        animator.start();
    }

    /** Places the top page {@code v} of the way out and the one below in step. */
    private void place(float v) {
        t = v;
        int w = getWidth();
        Page top = top();
        if (top != null) top.setTranslationX(v * w);
        if (stack.size() >= 2) below().setTranslationX(-(1f - v) * w * 0.3f);
        invalidate();
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == top() && stack.size() >= 2 && (moving || swiping)) {
            // The page below dims under the one coming over it.
            canvas.drawColor(Ui.alpha(0xFF000000, (ui.night ? 0.3f : 0.12f) * (1f - t)));
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (stack.size() < 2 || moving) return moving;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeDown = e.getX() <= edge;
                downX = e.getX();
                downY = e.getY();
                swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!edgeDown) return false;
                float dx = e.getX() - downX;
                float dy = Math.abs(e.getY() - downY);
                if (dx > slop && dx > dy * 1.5f) {
                    startSwipe(e);
                    return true;
                }
                if (dy > slop) edgeDown = false;
                return false;
            default:
                edgeDown = false;
                return false;
        }
    }

    private void startSwipe(MotionEvent e) {
        swiping = true;
        downX = e.getX();
        below().setVisibility(View.VISIBLE);
        velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!swiping) {
            // A down on the edge with nothing under it to take it: watch it from here.
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && stack.size() >= 2
                    && !moving && e.getX() <= edge) {
                edgeDown = true;
                downX = e.getX();
                downY = e.getY();
                return true;
            }
            if (edgeDown && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - downX;
                if (dx > slop && dx > Math.abs(e.getY() - downY) * 1.5f) startSwipe(e);
                return true;
            }
            return edgeDown;
        }
        velocity.addMovement(e);
        int w = Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                place(Math.max(0f, Math.min(1f, (e.getX() - downX) / w)));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity();
                velocity.recycle();
                velocity = null;
                swiping = false;
                edgeDown = false;
                boolean done = e.getActionMasked() == MotionEvent.ACTION_UP
                        && (t > 0.5f || vx > ui.dp(500));
                if (done) {
                    run(t, 1f, Math.max(120, Math.round(DURATION * (1f - t))),
                            () -> removeTop());
                } else {
                    run(t, 0f, Math.max(120, Math.round(DURATION * t)), () -> showOnlyTop());
                }
                return true;
            default:
                return true;
        }
    }
}
