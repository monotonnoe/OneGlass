package com.monotonnoe.oneglass.engine;

import com.monotonnoe.oneglass.R;

import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One screen of the settings, as an iOS 26 navigation page: a large title that collapses into
 * the bar as the page scrolls, the bar's scroll edge effect (what scrolls under it blurred and
 * faded into the background, Kyant's progressive blur) and, below the first page, a glass back
 * button that refracts what scrolls under it.
 *
 * <p>The scrolled content is recorded once more into a RenderNode each frame it is drawn
 * (Kyant's layerBackdrop): the bar and the back button draw that node again with their
 * effects. Android 12 and before, or if a node cannot be drawn, the bar fades to the
 * background colour instead.
 */
final class Page extends FrameLayout {
    /** Kyant's progressive blur mask: the blurred content fades out towards the bar's bottom. */
    private static final String EDGE_MASK = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "layout(color) uniform half4 tint;\n"
            + "uniform float tintIntensity;\n"
            + "half4 main(float2 coord) {\n"
            + "    float a = smoothstep(size.y, size.y * 0.5, coord.y);\n"
            + "    return mix(content.eval(coord) * half(a), tint * half(a), half(tintIntensity));\n"
            + "}\n";

    private static boolean nodesBroken = Build.VERSION.SDK_INT < 31;

    final Ui ui;
    final String title;
    final String id;
    final ScrollView scroll;
    final LinearLayout content;
    private final TextView largeTitle;
    private final FrameLayout bar;
    private final TextView smallTitle;
    final GlassButton back;
    private final Paint fade = new Paint();
    private RenderNode contentNode;
    private RenderNode edgeNode;
    private RuntimeShader edgeMask;
    private boolean contentRecorded;
    private int insetTop;
    private int lastFadeH = -1;
    private Runnable onBack;

    Page(Ui ui, String id, String title, boolean root) {
        super(ui.context);
        this.ui = ui;
        this.id = id;
        this.title = title;
        setBackgroundColor(ui.background);
        setClipChildren(false);

        scroll = new ScrollView(ui.context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = new LinearLayout(ui.context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        largeTitle = new TextView(ui.context);
        largeTitle.setText(title);
        largeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.LARGE_TITLE);
        largeTitle.setTypeface(ui.bold);
        largeTitle.setTextColor(ui.label);
        largeTitle.setSingleLine(true);
        largeTitle.setEllipsize(TextUtils.TruncateAt.END);
        largeTitle.setPadding(ui.px(20), ui.px(4), ui.px(20), ui.px(10));
        largeTitle.setAccessibilityHeading(true);
        content.addView(largeTitle);

        bar = new FrameLayout(ui.context);
        bar.setClipChildren(false);
        smallTitle = new TextView(ui.context);
        smallTitle.setText(title);
        smallTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.TITLE);
        smallTitle.setTypeface(ui.semibold);
        smallTitle.setTextColor(ui.label);
        smallTitle.setSingleLine(true);
        smallTitle.setEllipsize(TextUtils.TruncateAt.END);
        smallTitle.setGravity(Gravity.CENTER);
        smallTitle.setAlpha(0f);
        smallTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.px(52), Gravity.BOTTOM);
        tp.leftMargin = ui.px(64);
        tp.rightMargin = ui.px(64);
        bar.addView(smallTitle, tp);
        if (root) {
            back = null;
        } else {
            back = new GlassButton(ui, null, GlassButton.GLYPH_BACK, true);
            back.setContentDescription(ui.context.getString(R.string.back));
            // Anonymous classes, not lambdas: Retrolambda loads a class with lambdas against
            // Android 9's framework, which has no RecordingCanvas for this one's verification.
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (onBack != null) onBack.run();
                }
            });
            back.setBackdrop(new GlassPaint.Backdrop() {
                @Override
                public void draw(Canvas c) {
                    drawBackdropFor(c, back);
                }
            });
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            bp.leftMargin = ui.px(12);
            bp.bottomMargin = ui.px(0);
            bar.addView(back, bp);
        }
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, ui.px(52)));

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                onScrolled();
            }
        });
        applyInsets(Insets.NONE);
    }

    void setOnBack(Runnable r) {
        onBack = r;
    }

    /** The system bars' and cutout's insets: the content and the bar keep clear of them. */
    void applyInsets(Insets in) {
        insetTop = in.top;
        int side = ui.px(0);
        content.setPadding(in.left + side, in.top + ui.px(52), in.right + side,
                in.bottom + ui.px(40));
        LayoutParams lp = (LayoutParams) bar.getLayoutParams();
        lp.height = in.top + ui.px(52);
        lp.leftMargin = in.left;
        lp.rightMargin = in.right;
        bar.setLayoutParams(lp);
        onScrolled();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        applyInsets(insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout()));
        return insets;
    }

    /** The small title fades in as the large one passes under the bar; the edge effect too. */
    private void onScrolled() {
        int y = scroll.getScrollY();
        float t = (y - largeTitle.getHeight() * 0.45f) / ui.dp(12);
        smallTitle.setAlpha(Math.max(0f, Math.min(1f, t)));
        invalidate();
        if (back != null) back.invalidate();
    }

    private float edgeProgress() {
        return Math.max(0f, Math.min(1f, scroll.getScrollY() / ui.dp(10)));
    }

    /** Draws what is behind {@code v} (a view in the bar), in its coordinates. */
    private void drawBackdropFor(Canvas c, View v) {
        c.save();
        c.translate(-(v.getLeft() + bar.getLeft()), -(v.getTop() + bar.getTop()));
        c.drawColor(ui.background);
        if (contentRecorded && contentNode != null) {
            ((RecordingCanvas) c).drawRenderNode(contentNode);
        }
        c.restore();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        contentRecorded = false;
        if (nodesBroken || !canvas.isHardwareAccelerated()
                || !(canvas instanceof RecordingCanvas)) {
            super.dispatchDraw(canvas);
            return;
        }
        long time = getDrawingTime();
        try {
            if (contentNode == null) contentNode = new RenderNode("BlurIconsPage");
            contentNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = contentNode.beginRecording(getWidth(), getHeight());
            try {
                drawChild(rc, scroll, time);
            } finally {
                contentNode.endRecording();
            }
            contentRecorded = true;
        } catch (Throwable t) {
            nodesBroken = true;
            XLog.e("settings page backdrop", t);
            super.dispatchDraw(canvas);
            return;
        }
        ((RecordingCanvas) canvas).drawRenderNode(contentNode);
        drawEdge((RecordingCanvas) canvas);
        drawChild(canvas, bar, time);
    }

    /** iOS 26's scroll edge effect under the bar. */
    private void drawEdge(RecordingCanvas canvas) {
        float p = edgeProgress();
        if (p <= 0f) return;
        int h = insetTop + ui.px(52) + ui.px(18);
        int w = getWidth();
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (edgeNode == null) edgeNode = new RenderNode("BlurIconsEdge");
                edgeNode.setPosition(0, 0, w, h);
                RecordingCanvas rc = edgeNode.beginRecording(w, h);
                try {
                    rc.drawColor(ui.background);
                    rc.drawRenderNode(contentNode);
                } finally {
                    edgeNode.endRecording();
                }
                if (edgeMask == null) edgeMask = new RuntimeShader(EDGE_MASK);
                edgeMask.setFloatUniform("size", w, h);
                edgeMask.setColorUniform("tint", ui.background);
                edgeMask.setFloatUniform("tintIntensity", 0.55f);
                float blur = ui.dp(6f);
                edgeNode.setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(edgeMask, "content"),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                edgeNode.setAlpha(p);
                canvas.drawRenderNode(edgeNode);
                return;
            } catch (Throwable t) {
                XLog.e("settings scroll edge", t);
            }
        }
        drawFade(canvas, w, h, p);
    }

    private void drawFade(Canvas canvas, int w, int h, float p) {
        if (lastFadeH != h) {
            lastFadeH = h;
            int bg = ui.background;
            fade.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Ui.alpha(bg, 0.96f), Ui.alpha(bg, 0.9f), Ui.alpha(bg, 0f)},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }
        fade.setAlpha(Math.round(255 * p));
        canvas.drawRect(0, 0, w, h, fade);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == bar && !contentRecorded) {
            // The flat path: the fade under the bar, then the bar.
            float p = edgeProgress();
            if (p > 0f) drawFade(canvas, getWidth(), insetTop + ui.px(70), p);
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    /** The page's scroll position, kept across a rebuild of its rows. */
    int scrollY() {
        return scroll.getScrollY();
    }

    /** Empties the page below its large title, for its rows to be built again. */
    void clearRows() {
        while (content.getChildCount() > 1) content.removeViewAt(1);
    }
}
