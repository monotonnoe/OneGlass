package com.monotonnoe.oneglass.engine;

import com.monotonnoe.oneglass.R;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The settings' inset grouped lists, as iOS 26 Settings draws them: sections with a header and
 * a footer in secondary text, cards with continuous 12 dp corners inset 16 dp from the screen's
 * edges, rows of at least 50 dp with a title (and a value, a chevron or a control), and
 * separators inset to the text.
 */
final class Rows {
    static final int GLYPH_HOME = 1;
    static final int GLYPH_LOCK = 2;
    static final int GLYPH_SLIDERS = 3;
    static final int GLYPH_BACKUP = 4;

    final Ui ui;

    Rows(Ui ui) {
        this.ui = ui;
    }

    /** A section: its header and footer (either may be null) around a card for its rows. */
    static final class Section {
        Card card;
        TextView header;
        TextView footer;
    }

    Section section(LinearLayout parent, String header, String footer) {
        Section s = new Section();
        if (header != null) {
            TextView h = new TextView(ui.context);
            h.setText(header);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.FOOTNOTE);
            h.setTypeface(ui.regular);
            h.setAllCaps(true);
            h.setLetterSpacing(0.02f);
            h.setTextColor(ui.secondaryLabel);
            h.setPadding(ui.px(32), ui.px(22), ui.px(32), ui.px(7));
            h.setAccessibilityHeading(true);
            parent.addView(h);
            s.header = h;
        }
        Card card = new Card(ui);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ui.px(16);
        lp.rightMargin = ui.px(16);
        lp.topMargin = header != null ? 0 : ui.px(22);
        parent.addView(card, lp);
        s.card = card;
        if (footer != null) s.footer = footer(parent, footer);
        return s;
    }

    TextView footer(LinearLayout parent, String text) {
        TextView f = new TextView(ui.context);
        f.setText(text);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.FOOTNOTE);
        f.setTypeface(ui.regular);
        f.setTextColor(ui.secondaryLabel);
        f.setLineSpacing(0f, 1.08f);
        f.setPadding(ui.px(32), ui.px(7), ui.px(32), 0);
        parent.addView(f);
        return f;
    }

    /** A card of rows: the background with continuous corners, separators between rows. */
    static final class Card extends LinearLayout {
        private final Ui ui;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint();
        private final Path path = new Path();
        private final RectF rect = new RectF();

        Card(Ui ui) {
            super(ui.context);
            this.ui = ui;
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClipChildren(false);
            fill.setColor(ui.card);
            line.setColor(ui.separator);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ui.dp(12));
                }
            });
            setClipToOutline(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            rect.set(0, 0, getWidth(), getHeight());
            c.drawPath(ContinuousCorners.path(path, rect, ui.dp(12)), fill);
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            float h = Math.max(1f, ui.dp(0.5f));
            View prev = null;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() != VISIBLE) continue;
                if (prev != null) {
                    float inset = prev instanceof Row ? ((Row) prev).separatorInset : ui.dp(16);
                    float y = prev.getBottom();
                    c.drawRect(inset, y - h, getWidth(), y, line);
                }
                prev = v;
            }
        }
    }

    /** A row: an optional icon, the title (and a subtitle), a value, and its accessory. */
    static final class Row extends LinearLayout {
        TextView title;
        TextView subtitle;
        TextView value;
        float separatorInset;

        Row(Ui ui) {
            super(ui.context);
        }
    }

    Row row(Card card, String title, String subtitle) {
        Row r = new Row(ui);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.px(50));
        r.setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(8));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.separatorInset = ui.dp(16);
        LinearLayout texts = new LinearLayout(ui.context);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.title = text(title, Ui.BODY, ui.label);
        texts.addView(r.title);
        if (subtitle != null) {
            r.subtitle = text(subtitle, Ui.FOOTNOTE, ui.secondaryLabel);
            r.subtitle.setPadding(0, ui.px(2), 0, 0);
            texts.addView(r.subtitle);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        card.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    TextView text(String s, float size, int color) {
        TextView t = new TextView(ui.context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTypeface(ui.regular);
        t.setTextColor(color);
        return t;
    }

    /** The row reacts to taps: grey while pressed, as iOS's cells. */
    void tappable(Row r, Runnable onClick) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(ui.cardPressed));
        bg.addState(new int[0], new ColorDrawable(0));
        r.setBackground(bg);
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(v -> onClick.run());
    }

    /** A row with a value on its right; with a chevron when it opens something. */
    Row valueRow(Card card, String title, String value, boolean chevron, Runnable onClick) {
        Row r = row(card, title, null);
        r.value = text(value != null ? value : "", Ui.BODY, ui.secondaryLabel);
        r.value.setSingleLine(true);
        r.value.setEllipsize(TextUtils.TruncateAt.END);
        r.value.setMaxWidth(ui.px(180));
        r.value.setGravity(Gravity.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.leftMargin = ui.px(8);
        r.addView(r.value, vp);
        if (chevron) addChevron(r);
        if (onClick != null) tappable(r, onClick);
        return r;
    }

    /** A row that opens a page: an icon, the title, an optional value and a chevron. */
    Row navRow(Card card, int color, int glyph, String title, String value, Runnable onClick) {
        Row r = valueRow(card, title, value, true, onClick);
        View icon = new View(ui.context);
        icon.setBackground(new IconDrawable(ui, color, glyph));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.px(30), ui.px(30));
        ip.rightMargin = ui.px(14);
        r.addView(icon, 0, ip);
        r.separatorInset = ui.dp(60);
        return r;
    }

    /** A row that acts: its title in the accent colour (or red), centred or not. */
    Row actionRow(Card card, String title, boolean destructive, Runnable onClick) {
        Row r = row(card, title, null);
        r.title.setTextColor(destructive ? ui.red : ui.accent);
        tappable(r, onClick);
        return r;
    }

    Row toggleRow(Card card, String title, String subtitle, boolean on,
                  GlassToggle.Listener l) {
        Row r = row(card, title, subtitle);
        GlassToggle t = new GlassToggle(ui, on);
        t.setListener(l);
        t.setContentDescription(title);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = ui.px(8);
        r.addView(t, tp);
        r.setTag(t);
        return r;
    }

    /** A cell with a title and its value over a slider. */
    static final class SliderCell extends LinearLayout {
        TextView title;
        TextView value;
        GlassSlider slider;

        SliderCell(Ui ui) {
            super(ui.context);
        }
    }

    SliderCell sliderCell(Card card, String title, GlassSlider slider) {
        SliderCell c = new SliderCell(ui);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(4));
        c.setClipChildren(false);
        c.setClipToPadding(false);
        if (title != null) {
            LinearLayout line = new LinearLayout(ui.context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            c.title = text(title, Ui.BODY, ui.label);
            line.addView(c.title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            c.value = text("", Ui.BODY, ui.secondaryLabel);
            c.value.setGravity(Gravity.END);
            line.addView(c.value);
            c.addView(line);
            slider.setContentDescription(title);
        }
        c.slider = slider;
        c.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** A cell holding one control (a segmented control, a row of colours) with the margins. */
    LinearLayout cell(Card card, View control) {
        LinearLayout c = new LinearLayout(ui.context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.px(16), ui.px(6), ui.px(16), ui.px(6));
        c.setClipChildren(false);
        c.setClipToPadding(false);
        c.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void addChevron(Row r) {
        View chev = new View(ui.context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override
            protected void onDraw(Canvas c) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(ui.dp(2f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(ui.tertiaryLabel);
                float w = getWidth();
                float h = getHeight();
                path.reset();
                path.moveTo(w * 0.3f, h * 0.22f);
                path.lineTo(w * 0.72f, h * 0.5f);
                path.lineTo(w * 0.3f, h * 0.78f);
                c.drawPath(path, p);
            }
        };
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.px(10), ui.px(16));
        cp.leftMargin = ui.px(8);
        r.addView(chev, cp);
    }

    /** iOS Settings' row icon: a white glyph on a coloured rounded square. */
    static final class IconDrawable extends Drawable {
        private final Ui ui;
        private final int color;
        private final int glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconDrawable(Ui ui, int color, int glyph) {
            this.ui = ui;
            this.color = color;
            this.glyph = glyph;
        }

        @Override
        public void draw(Canvas c) {
            r.set(getBounds());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawPath(ContinuousCorners.path(path, r, r.width() * 0.225f), paint);
            float cx = r.centerX();
            float cy = r.centerY();
            float s = r.width();
            paint.setColor(0xFFFFFFFF);
            switch (glyph) {
                case GLYPH_HOME: {
                    float g = s * 0.17f;
                    float gap = s * 0.06f;
                    for (int i = 0; i < 4; i++) {
                        float x = cx + (i % 2 == 0 ? -gap / 2 - g : gap / 2);
                        float y = cy + (i < 2 ? -gap / 2 - g : gap / 2);
                        c.drawRoundRect(x, y, x + g, y + g, g * 0.3f, g * 0.3f, paint);
                    }
                    break;
                }
                case GLYPH_LOCK: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.075f);
                    c.drawArc(cx - s * 0.14f, cy - s * 0.3f, cx + s * 0.14f, cy + s * 0.02f,
                            180f, 180f, false, paint);
                    c.drawLine(cx - s * 0.14f, cy - s * 0.14f, cx - s * 0.14f, cy - s * 0.02f,
                            paint);
                    c.drawLine(cx + s * 0.14f, cy - s * 0.14f, cx + s * 0.14f, cy - s * 0.02f,
                            paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(cx - s * 0.23f, cy - s * 0.04f, cx + s * 0.23f,
                            cy + s * 0.27f, s * 0.05f, s * 0.05f, paint);
                    break;
                }
                case GLYPH_SLIDERS: {
                    paint.setStrokeWidth(s * 0.07f);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    float[] knob = {0.62f, 0.38f, 0.55f};
                    for (int i = 0; i < 3; i++) {
                        float y = cy + (i - 1) * s * 0.2f;
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(cx - s * 0.26f, y, cx + s * 0.26f, y, paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx - s * 0.26f + s * 0.52f * knob[i], y, s * 0.075f,
                                paint);
                    }
                    break;
                }
                case GLYPH_BACKUP: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.07f);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    path.reset();
                    path.moveTo(cx - s * 0.24f, cy);
                    path.lineTo(cx - s * 0.24f, cy + s * 0.24f);
                    path.lineTo(cx + s * 0.24f, cy + s * 0.24f);
                    path.lineTo(cx + s * 0.24f, cy);
                    c.drawPath(path, paint);
                    path.reset();
                    path.moveTo(cx - s * 0.12f, cy - s * 0.15f);
                    path.lineTo(cx, cy - s * 0.27f);
                    path.lineTo(cx + s * 0.12f, cy - s * 0.15f);
                    c.drawPath(path, paint);
                    c.drawLine(cx, cy - s * 0.26f, cx, cy + s * 0.1f, paint);
                    break;
                }
                default:
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
