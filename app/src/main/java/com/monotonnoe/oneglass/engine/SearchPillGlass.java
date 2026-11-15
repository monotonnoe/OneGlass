package com.monotonnoe.oneglass.engine;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;

import com.monotonnoe.oneglass.glass.Units;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The iOS 27 "Search" pill that replaces the page dots above the dock. Figma ("iOS 27 Widgets",
 * Playground, Home Screen Layout): 78x30pt, fully round, 11pt side padding, an 11pt magnifying
 * glass, 4pt gap, "Search" in 12pt medium, content at 90% white. The glass is the engine's, with
 * the lens fitted to Figma's render (refraction 6.84pt over a 30pt pill).
 * The magnifier is drawn in code (no Apple glyphs are shipped); the label is the system font.
 */
public final class SearchPillGlass {

    private static final float H = 30f;
    private static final float PAD = 11f;
    private static final float GLYPH = 11f;
    private static final float GAP = 4f;
    private static final float TEXT = 12f;
    private static final float LENS_AMOUNT = 6.84f / H;

    private static final WeakHashMap<View, Item> HOSTS = new WeakHashMap<>();
    /** Indicators we replaced, with the alpha One UI last asked for. */
    private static final WeakHashMap<View, Float> INDICATORS = new WeakHashMap<>();
    private static final Set<Method> HOOKED = new HashSet<>();
    private static boolean alphaHooked;

    private static final Paint TEXT_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint GLYPH_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF RECT = new RectF();
    private static final RectF TMP = new RectF();

    static {
        TEXT_PAINT.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TEXT_PAINT.setColor(0xFFFFFFFF);
        GLYPH_PAINT.setStyle(Paint.Style.STROKE);
        GLYPH_PAINT.setStrokeCap(Paint.Cap.ROUND);
        GLYPH_PAINT.setColor(0xFFFFFFFF);
    }

    private static final class Item {
        final WeakReference<View> indicator;
        final LiquidGlass.Glass glass = new LiquidGlass.Glass();

        Item(View indicator, View host) {
            this.indicator = new WeakReference<>(indicator);
            glass.anchor = new WeakReference<>(host);
            glass.lensHeight = 0.5f;
            glass.lensAmount = LENS_AMOUNT;
        }
    }

    private SearchPillGlass() {
    }

    /** Puts the pill in place of One UI's page indicator (Home screen only). */
    public static void attach(final View indicator) {
        if (!(indicator.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup host = (ViewGroup) indicator.getParent();
        Item item = HOSTS.get(host);
        if (item != null && item.indicator.get() == indicator) {
            return;
        }
        HOSTS.put(host, new Item(indicator, host));
        INDICATORS.put(indicator, indicator.getAlpha());
        hookAlpha();
        indicator.setAlpha(indicator.getAlpha()); // goes through the hook: dots hidden
        indicator.setOnClickListener(v -> openSearch(v.getContext()));
        host.setWillNotDraw(false);
        hookDispatchDraw(host.getClass());
        host.invalidate();
        XLog.i("search pill drawn by " + host.getClass().getName() + " over " + indicator.getClass().getName());
    }

    /** Another module (SearchPill) already shows a search pill. */
    public static volatile boolean suppressed;

    private static boolean active() {
        return com.monotonnoe.oneglass.Config.current.pill && !suppressed;
    }

    /** One UI fades the dots in and out: remember that alpha for the pill, keep the dots hidden. */
    private static void hookAlpha() {
        if (alphaHooked) return;
        alphaHooked = true;
        XposedBridge.hookAllMethods(View.class, "setAlpha", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !(param.args[0] instanceof Float)
                        || !INDICATORS.containsKey(param.thisObject)) {
                    return;
                }
                INDICATORS.put((View) param.thisObject, (Float) param.args[0]);
                if (active()) {
                    param.args[0] = 0f;
                }
                View parent = (View) ((View) param.thisObject).getParent();
                if (parent != null) parent.invalidate();
            }
        });
    }

    private static void hookDispatchDraw(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Method m;
            try {
                m = c.getDeclaredMethod("dispatchDraw", Canvas.class);
            } catch (NoSuchMethodException e) {
                continue;
            }
            synchronized (HOOKED) {
                if (!HOOKED.add(m)) return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Item item = HOSTS.get(param.thisObject);
                    if (item == null || !(param.args[0] instanceof Canvas)) return;
                    try {
                        draw((Canvas) param.args[0], (View) param.thisObject, item);
                    } catch (Throwable t) {
                        XLog.e("search pill", t);
                    }
                }
            });
            return;
        }
    }

    private static String label(Context c) {
        String lang = Locale.getDefault().getLanguage();
        if ("ru".equals(lang) || "uk".equals(lang) || "be".equals(lang)) return "Поиск";
        if ("de".equals(lang)) return "Suchen";
        if ("es".equals(lang)) return "Buscar";
        if ("fr".equals(lang)) return "Rechercher";
        return "Search";
    }

    private static void draw(Canvas canvas, View host, Item item) {
        View indicator = item.indicator.get();
        if (!active() || indicator == null || indicator.getParent() != host
                || indicator.getVisibility() != View.VISIBLE) {
            return;
        }
        Float requested = INDICATORS.get(indicator);
        float alpha = requested == null ? 1f : requested;
        if (alpha <= 0.01f) return;
        float pt = Units.pt(host);
        String text = label(host.getContext());
        TEXT_PAINT.setTextSize(TEXT * pt);
        float textW = TEXT_PAINT.measureText(text);
        float w = PAD * pt + GLYPH * pt + GAP * pt + textW + PAD * pt;
        float h = H * pt;
        // centre of the indicator in the host (view coordinates)
        TMP.set(0, 0, indicator.getWidth(), indicator.getHeight());
        indicator.getMatrix().mapRect(TMP);
        TMP.offset(indicator.getLeft() - host.getScrollX(), indicator.getTop() - host.getScrollY());
        float cx = TMP.centerX();
        float cy = TMP.centerY();
        RECT.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);

        canvas.save();
        try {
            canvas.translate(host.getScrollX(), host.getScrollY());
            float corner = h / 2f;
            int tint = Settings.backgroundAlpha;
            int argb = IconBlur.autoTint(host, tint);
            item.glass.tint = argb;
            if (LiquidGlass.available() && item.glass.draw(canvas, host, RECT, corner, alpha, Config.glassBlur)) {
                LiquidGlass.onDrawn(host.getContext());
                if (argb == 0 || !item.glass.tintDrawn) {
                    IconBlur.drawTint(canvas, host, RECT, corner, tint);
                }
            } else {
                item.glass.markBare();
                IconBlur.drawTint(canvas, host, RECT, corner, Math.max(tint, 60));
            }
            CellGlass.watch(indicator, host, item.glass);

            int a = Math.round(255 * 0.9f * alpha);
            // magnifying glass: 11pt box, ring + handle
            float gx = RECT.left + PAD * pt;
            float gy = RECT.centerY() - GLYPH * pt / 2f;
            GLYPH_PAINT.setAlpha(a);
            GLYPH_PAINT.setStrokeWidth(1.45f * pt);
            float r = 3.7f * pt;
            float ccx = gx + 4.6f * pt;
            float ccy = gy + 4.6f * pt;
            canvas.drawCircle(ccx, ccy, r, GLYPH_PAINT);
            GLYPH_PAINT.setStrokeWidth(1.7f * pt);
            float d = r * 0.7071f;
            canvas.drawLine(ccx + d + 0.6f * pt, ccy + d + 0.6f * pt,
                    gx + 10.3f * pt, gy + 10.3f * pt, GLYPH_PAINT);
            // label
            TEXT_PAINT.setAlpha(a);
            Paint.FontMetrics fm = TEXT_PAINT.getFontMetrics();
            float baseline = RECT.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, gx + GLYPH * pt + GAP * pt, baseline, TEXT_PAINT);
        } finally {
            canvas.restore();
        }
    }

    private static void openSearch(Context c) {
        String[] pkgs = {"com.samsung.android.app.galaxyfinder", "com.google.android.googlequicksearchbox"};
        for (String p : pkgs) {
            try {
                Intent i = c.getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                    return;
                }
            } catch (Throwable ignored) {
                // next
            }
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_WEB_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            XLog.e("search", t);
        }
    }
}
