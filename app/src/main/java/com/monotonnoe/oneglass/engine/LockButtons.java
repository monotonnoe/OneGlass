package com.monotonnoe.oneglass.engine;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * The lock screen's two shortcut buttons as iOS 26's: a round glass button (the picture behind
 * it, blurred and bent at its edge, a soft dark tone and a lit rim) with a white glyph inside,
 * where iOS places them (measured on an iPhone screenshot of the same aspect).
 *
 * <p>The place is set in the button's layout (View#layout), not by its translation: One UI
 * moves and resizes the buttons by their translation and size when the Now Bar (the music
 * player) shows, and 6.73-6.74 setting the translation from every draw fought those springs on
 * every frame (stutter, buttons jumping). The glass follows the button's size as One UI
 * animates it. The glyph: One UI's own for its actions (flashlight, …), the app's themed
 * glyph, a camera glyph of the module's own for the camera, else the app's icon, smaller.
 */
final class LockButtons {
    private static final String VIEW = "com.android.systemui.statusbar.KeyguardSecAffordanceView";
    /** The buttons' centres, as parts of the screen, and their diameter (part of the width). */
    private static final float LEFT_X = 0.190f;
    private static final float RIGHT_X = 0.808f;
    private static final float CENTER_Y = 0.9077f;
    private static final float DIAMETER = 0.146f;
    /** An app icon without a glyph, as a part of the diameter. */
    private static final float ICON = 0.5f;
    /** The glyph's box, as a part of the diameter (iOS's flashlight and camera). */
    private static final float GLYPH_SIZE = 0.46f;

    private static final Map<View, LockCard> GLASS = new WeakHashMap<>();
    /** Each button's size at rest (the largest seen): One UI shrinks it for the Now Bar. */
    private static final Map<View, int[]> REST = new WeakHashMap<>();
    private static final Paint TONE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint RIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint WHITE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path CAMERA = new Path();
    private static final PorterDuffColorFilter TO_WHITE =
            new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
    private static Field right;
    private static Field nowBarField;
    private static Field manager;
    private static Method component;
    private static Method monotone;
    private static Method camera;
    /** Per side: the component last seen and the app's glyph for it (null: none). */
    private static final String[] GLYPH_FOR = new String[2];
    private static final Drawable[] GLYPH = new Drawable[2];
    private static boolean skipped;
    private static int saved = -1;
    private static float rimFor = -1f;
    private static float cameraFor = -1f;

    private LockButtons() {
    }

    static void install(ClassLoader cl) throws Exception {
        Class<?> c = Class.forName(VIEW, false, cl);
        right = field(c, "mRight");
        manager = field(c, "mShortcutManager");
        if (manager != null) {
            Class<?> m = manager.getType();
            component = method(m, "getComponentNameForSALogging");
            monotone = method(m, "isMonotoneIcon");
            camera = method(m, "isShortcutForCamera");
        }
        int n = XposedBridge.hookAllMethods(c, "onDraw", new SafeHook("lock button") {
            @Override
            protected void before(MethodHookParam p) {
                if (p.args.length != 1 || !(p.args[0] instanceof Canvas)) return;
                draw((View) p.thisObject, (Canvas) p.args[0]);
            }

            @Override
            protected void after(MethodHookParam p) {
                if (saved >= 0 && p.args.length == 1 && p.args[0] instanceof Canvas) {
                    ((Canvas) p.args[0]).restoreToCount(saved);
                }
                saved = -1;
            }
        }).size();
        // The place: the frame One UI lays out, moved after its own onLayout (every layout
        // sets One UI's frame again, which runs onLayout again), so its translation and size
        // springs act on top. Only this class's onLayout: View#layout runs for every view.
        n += XposedBridge.hookAllMethods(c, "onLayout", new SafeHook("lock button place") {
            @Override
            protected void after(MethodHookParam p) {
                place((View) p.thisObject);
            }
        }).size();
        // The Now Bar (music, the flashlight on, …): One UI's places while it shows.
        nowBarField = field(c, "mIsNowBarVisible");
        for (String m : new String[]{"setNowBarVisibility", "setNowBarExpandMode"}) {
            n += XposedBridge.hookAllMethods(c, m, new SafeHook("lock button now bar") {
                @Override
                protected void after(MethodHookParam p) {
                    retarget((View) p.thisObject);
                }
            }).size();
        }
        XLog.i("lock buttons hooks: " + n + (component != null ? "" : ", no shortcut info"));
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            XLog.i("lock buttons: no " + c.getSimpleName() + "." + name);
            return null;
        }
    }

    private static Method method(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name, int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            XLog.i("lock buttons: no " + c.getSimpleName() + "#" + name);
            return null;
        }
    }

    private static int side(View v) {
        try {
            return right != null && right.getBoolean(v) ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Per button: the offset in its frame now and where it heads (iOS's place, or none). */
    private static final class Place {
        float curX;
        float curY;
        float targetX;
        float targetY;
        /** What is added to One UI's frame now (whole pixels). */
        int appliedX;
        int appliedY;
        android.animation.ValueAnimator anim;
        boolean known;
    }

    private static final Map<View, Place> PLACES = new WeakHashMap<>();
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * One UI's frame was just laid out (any offset of ours is gone): our offset goes back on,
     * and heads for iOS's place, or for none while the Now Bar shows (One UI then moves the
     * buttons apart for it, and iOS's places would put them under it).
     */
    private static void place(View v) {
        Place pl = PLACES.get(v);
        if (pl == null) {
            pl = new Place();
            PLACES.put(v, pl);
        }
        pl.appliedX = 0;
        pl.appliedY = 0;
        float[] t = target(v, v.getLeft(), v.getTop());
        if (t == null) return;
        if (!pl.known) {
            pl.known = true;
            pl.curX = t[0];
            pl.curY = t[1];
        }
        apply(v, pl);
        head(v, pl, t);
    }

    /** The Now Bar came or went: the buttons glide to their places for it. */
    private static void retarget(View v) {
        Place pl = PLACES.get(v);
        if (pl == null || !pl.known) return;
        float[] t = target(v, v.getLeft() - pl.appliedX, v.getTop() - pl.appliedY);
        if (t != null) head(v, pl, t);
    }

    /** The offset from One UI's frame (left, top) to iOS's place, or none with the Now Bar. */
    private static float[] target(View v, int left, int top) {
        View root = v.getRootView();
        int sw = root.getWidth();
        int sh = root.getHeight();
        ViewParent parent = v.getParent();
        int w = v.getWidth();
        int h = v.getHeight();
        if (sw <= 0 || sh <= sw || w <= 0 || h <= 0 || !(parent instanceof View)) return null;
        if (nowBar(v)) return new float[]{0f, 0f};
        float px = 0f;
        float py = 0f;
        for (View x = (View) parent; x != null; ) {
            px += x.getLeft();
            py += x.getTop();
            ViewParent q = x.getParent();
            if (!(q instanceof View)) break;
            px -= ((View) q).getScrollX();
            py -= ((View) q).getScrollY();
            x = (View) q;
        }
        boolean isRight = right != null ? side(v) == 1 : px + left + w / 2f > sw / 2f;
        return new float[]{(isRight ? RIGHT_X : LEFT_X) * sw - px - w / 2f - left,
                CENTER_Y * sh - py - h / 2f - top};
    }

    private static boolean nowBar(View v) {
        if (nowBarField == null) return false;
        try {
            return nowBarField.getBoolean(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Moves the frame to the current offset (by the whole pixels it differs). */
    private static void apply(View v, Place pl) {
        int x = Math.round(pl.curX);
        int y = Math.round(pl.curY);
        if (x != pl.appliedX) v.offsetLeftAndRight(x - pl.appliedX);
        if (y != pl.appliedY) v.offsetTopAndBottom(y - pl.appliedY);
        pl.appliedX = x;
        pl.appliedY = y;
    }

    /** Glides the offset to {@code t} over 350 ms (the frame only: no translation of ours). */
    private static void head(View v, Place pl, float[] t) {
        if (Math.abs(t[0] - pl.targetX) < 0.5f && Math.abs(t[1] - pl.targetY) < 0.5f
                && (pl.anim != null && pl.anim.isRunning()
                || (Math.abs(pl.curX - t[0]) < 0.5f && Math.abs(pl.curY - t[1]) < 0.5f))) {
            return;
        }
        pl.targetX = t[0];
        pl.targetY = t[1];
        if (pl.anim != null) pl.anim.cancel();
        if (Math.abs(pl.curX - t[0]) < 0.5f && Math.abs(pl.curY - t[1]) < 0.5f) {
            pl.curX = t[0];
            pl.curY = t[1];
            apply(v, pl);
            return;
        }
        final float fromX = pl.curX;
        final float fromY = pl.curY;
        final float toX = t[0];
        final float toY = t[1];
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(350);
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> {
            float f = (Float) an.getAnimatedValue();
            pl.curX = fromX + (toX - fromX) * f;
            pl.curY = fromY + (toY - fromY) * f;
            apply(v, pl);
        });
        pl.anim = a;
        a.start();
    }

    private static void draw(View v, Canvas canvas) {
        saved = -1;
        skipped = false;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0 || v.getVisibility() != View.VISIBLE) return;
        unclip(v);
        float cx = w / 2f;
        float cy = h / 2f;
        // iOS's size at rest; One UI's shrinking of the button (the Now Bar) shrinks it too.
        int[] rest = REST.get(v);
        if (rest == null) {
            rest = new int[]{w};
            REST.put(v, rest);
        } else if (w > rest[0]) {
            rest[0] = w;
        }
        float d = DIAMETER * v.getRootView().getWidth() * w / (float) rest[0];
        float r = d / 2f;
        LockCard g = GLASS.get(v);
        if (g == null) {
            g = new LockCard();
            g.round = true;
            GLASS.put(v, g);
        }
        int[] st = LockGlass.settings;
        float density = v.getResources().getDisplayMetrics().density;
        int blur = st.length > LockNotifications.BLUR
                ? LockNotifications.blurRadius(st[LockNotifications.BLUR], density) : 20;
        try {
            int size = Math.round(d);
            if (g.draw(canvas, v, Math.round(cx - r), Math.round(cy - r), size, size, r, blur,
                    60)) {
                material(canvas, cx, cy, r, density);
            } else if (LockGlass.picture() == null) {
                LockGlass.requestPicture();
            }
        } catch (Throwable t) {
            XLog.e("lock button glass", t);
        }
        glyph(v, canvas, cx, cy, d);
        if (skipped) {
            // One UI's own drawing (its icon and circles) runs, for its launch animation's
            // side effects, but shows nothing: the glass and the glyph above are the button.
            saved = canvas.save();
            canvas.clipRect(0, 0, 0, 0);
        }
    }

    private static final Paint GLOW = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * ionic-theme-ios27's dark glass button (its ion-fab-button in the dark theme): a fill of
     * rgb(62, 62, 62) over the blurred backdrop, a 0.5 dp rim lit at the top and the bottom
     * and clear at the sides (white 24 % to nothing at 35 % and from 65 %), and a glow inside
     * along the top and the bottom (white 14 %, 4 % at 1 dp, nothing at 6 dp). Measured on
     * the iPhone's lock screen: fill 28 over a background of 15, rim 55, the sides bare.
     */
    private static void material(Canvas canvas, float cx, float cy, float r, float dp) {
        if (rimFor != r) {
            rimFor = r;
            TONE.setColor(0x613E3E3E);
            float d = 2f * r;
            GLOW.setShader(new LinearGradient(0f, -r, 0f, r,
                    new int[]{0x24FFFFFF, 0x0AFFFFFF, 0, 0, 0x0AFFFFFF, 0x24FFFFFF},
                    new float[]{0f, dp / d, 6f * dp / d, 1f - 6f * dp / d, 1f - dp / d, 1f},
                    Shader.TileMode.CLAMP));
            RIM.setStyle(Paint.Style.STROKE);
            RIM.setStrokeWidth(Math.max(1f, 0.5f * dp));
            RIM.setShader(new LinearGradient(0f, -r, 0f, r,
                    new int[]{0x3DFFFFFF, 0, 0, 0x3DFFFFFF},
                    new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawCircle(0f, 0f, r, TONE);
        canvas.drawCircle(0f, 0f, r, GLOW);
        canvas.drawCircle(0f, 0f, r - RIM.getStrokeWidth() / 2f, RIM);
        canvas.restore();
    }

    /** The glyph inside the glass, white; or One UI's icon, smaller (then drawn by One UI). */
    private static void glyph(View v, Canvas canvas, float cx, float cy, float d) {
        int side = side(v);
        Object m = null;
        try {
            m = manager != null ? manager.get(v) : null;
        } catch (Throwable ignored) {
        }
        float box = d * GLYPH_SIZE;
        // The flashlight and the camera, as iOS's lock screen draws them (measured).
        String name = name(m, side);
        if ("Flashlight".equalsIgnoreCase(name)) {
            drawFlashlight(canvas, cx, cy, d);
            skipped = true;
            return;
        }
        if (call(camera, m, side) || (name != null && name.toLowerCase(java.util.Locale.ROOT)
                .contains("camera"))) {
            drawCamera(canvas, cx, cy, d);
            skipped = true;
            return;
        }
        // One UI's other actions (do not disturb, …) carry a one-colour glyph already.
        if (call(monotone, m, side) && v instanceof android.widget.ImageView) {
            Drawable own = ((android.widget.ImageView) v).getDrawable();
            if (own != null && drawWhite(canvas, own, cx, cy, box)) {
                skipped = true;
                return;
            }
        }
        Drawable app = appGlyph(v, m, side);
        // A themed icon's layer is 108 dp with its glyph in the middle 66.
        if (app != null && drawWhite(canvas, app, cx, cy, box * 108f / 66f)) {
            skipped = true;
            return;
        }
        // An app without a glyph: its icon, smaller, inside the glass.
        if (v instanceof android.widget.ImageView) {
            Drawable icon = ((android.widget.ImageView) v).getDrawable();
            if (icon != null && fit(canvas, icon, cx, cy, d * ICON, null)) skipped = true;
        }
    }

    private static boolean call(Method method, Object target, int side) {
        if (method == null || target == null) return false;
        try {
            Object r = method.invoke(target, side);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Paint WHITE_LAYER = new Paint();
    private static final android.graphics.RectF LAYER = new android.graphics.RectF();

    static {
        WHITE_LAYER.setColorFilter(TO_WHITE);
    }

    /** A drawable as a white glyph, fitted (by its aspect) into a box of {@code box}. */
    private static boolean drawWhite(Canvas canvas, Drawable dr, float cx, float cy,
                                     float box) {
        return fit(canvas, dr, cx, cy, box, WHITE_LAYER);
    }

    /**
     * Draws {@code dr} fitted into a box of {@code box} round (cx, cy), through {@code layer}
     * (a colour filter) when given. Only the canvas is moved: the drawable's bounds, alpha and
     * filter are One UI's and stay untouched (setting them invalidates the button, which would
     * draw again and again).
     */
    private static boolean fit(Canvas canvas, Drawable dr, float cx, float cy, float box,
                               Paint layer) {
        android.graphics.Rect b = dr.getBounds();
        if (b.isEmpty()) {
            int iw = dr.getIntrinsicWidth();
            int ih = dr.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0 || dr.getCallback() != null) return false;
            // A drawable of the module's own (a themed glyph): its bounds are free to set.
            dr.setBounds(0, 0, iw, ih);
            b = dr.getBounds();
        }
        float s = box / Math.max(b.width(), b.height());
        int save;
        if (layer != null) {
            LAYER.set(cx - box / 2f - 1f, cy - box / 2f - 1f, cx + box / 2f + 1f,
                    cy + box / 2f + 1f);
            save = canvas.saveLayer(LAYER, layer);
        } else {
            save = canvas.save();
        }
        canvas.translate(cx, cy);
        canvas.scale(s, s);
        canvas.translate(-b.exactCenterX(), -b.exactCenterY());
        dr.draw(canvas);
        canvas.restoreToCount(save);
        return true;
    }

    /** The shortcut's name: a component, or One UI's action ("Flashlight", "Empty"). */
    private static String name(Object m, int side) {
        if (component == null || m == null) return null;
        try {
            Object c = component.invoke(m, side);
            return c != null ? c.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app's themed-icon glyph (its monochrome layer); null when it has none. */
    private static Drawable appGlyph(View v, Object m, int side) {
        if (component == null || m == null) return null;
        String name;
        try {
            Object c = component.invoke(m, side);
            name = c != null ? c.toString() : null;
        } catch (Throwable t) {
            return null;
        }
        // One UI's own actions are named without a package ("Flashlight", "Empty").
        if (name == null || name.indexOf('/') < 0) return null;
        if (name.equals(GLYPH_FOR[side])) return GLYPH[side];
        GLYPH_FOR[side] = name;
        GLYPH[side] = null;
        try {
            android.content.ComponentName cn =
                    android.content.ComponentName.unflattenFromString(name);
            if (cn == null) return null;
            Drawable icon = v.getContext().getPackageManager().getActivityIcon(cn);
            if (icon instanceof AdaptiveIconDrawable) {
                Drawable mono = ((AdaptiveIconDrawable) icon).getMonochrome();
                if (mono != null) {
                    mono = mono.mutate();
                    mono.setCallback(null);
                    mono.setBounds(0, 0, Math.max(1, mono.getIntrinsicWidth()),
                            Math.max(1, mono.getIntrinsicHeight()));
                    GLYPH[side] = mono;
                }
            }
            XLog.i("lock button " + side + ": " + name + (GLYPH[side] != null
                    ? ", its themed glyph" : ", no themed glyph"));
        } catch (Throwable t) {
            XLog.i("lock button glyph for " + name + ": " + t);
        }
        return GLYPH[side];
    }

    private static final Path FLASHLIGHT = new Path();
    private static float flashlightFor = -1f;

    /**
     * iOS's lock screen flashlight, measured on its screenshot (88 px tall in a 158 px
     * button): a head 36 wide in two parts with a fine line between, narrowing to a body 22
     * wide with a window (12 wide, rounded) and a dot in it. Drawn by the module, in units of
     * the glyph's height.
     */
    private static void drawFlashlight(Canvas canvas, float cx, float cy, float d) {
        float h = d * 88f / 158f;
        if (flashlightFor != h) {
            flashlightFor = h;
            float u = h / 88f;
            Path p = FLASHLIGHT;
            p.reset();
            // The head: 0..9.5 and 12.5..23, 36 wide, its top corners rounded.
            p.addRoundRect(-18f * u, -44f * u, 18f * u, -34.5f * u,
                    new float[]{4f * u, 4f * u, 4f * u, 4f * u, 0f, 0f, 0f, 0f},
                    Path.Direction.CW);
            Path lower = new Path();
            lower.moveTo(-18f * u, -31.5f * u);
            lower.lineTo(18f * u, -31.5f * u);
            lower.lineTo(18f * u, -21f * u);
            // Narrowing to the body over 23..34.
            lower.lineTo(11f * u, -10f * u);
            lower.lineTo(-11f * u, -10f * u);
            lower.lineTo(-18f * u, -21f * u);
            lower.close();
            p.op(lower, Path.Op.UNION);
            Path body = new Path();
            body.addRoundRect(-11f * u, -11f * u, 11f * u, 44f * u, 4f * u, 4f * u,
                    Path.Direction.CW);
            p.op(body, Path.Op.UNION);
            // The window, 12 wide over 34..58, and the dot in it.
            Path window = new Path();
            window.addRoundRect(-6f * u, -10f * u, 6f * u, 14f * u, 6f * u, 6f * u,
                    Path.Direction.CW);
            p.op(window, Path.Op.DIFFERENCE);
            Path dot = new Path();
            dot.addCircle(0f, 8f * u, 4.5f * u, Path.Direction.CW);
            p.op(dot, Path.Op.UNION);
            WHITE.setColor(0xFFFFFFFF);
            WHITE.setStyle(Paint.Style.FILL);
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(FLASHLIGHT, WHITE);
        canvas.restore();
    }

    /**
     * iOS's lock screen camera, measured on its screenshot (80 x 61 px in a 158 px button):
     * the body with round corners, the raised top a little left of centre, the lens (a dark
     * ring, 16.5 and 11 px round, its glass white) and a small dark flash at the top right.
     */
    private static void drawCamera(Canvas canvas, float cx, float cy, float d) {
        float w = d * 80f / 158f;
        if (cameraFor != w) {
            cameraFor = w;
            float u = w / 80f;
            Path p = CAMERA;
            p.reset();
            // Origin at the glyph's centre: 80 x 61, the body over 7..61 of its height.
            float top = -30.5f * u;
            p.addRoundRect(-40f * u, top + 7f * u, 40f * u, top + 61f * u, 11f * u, 11f * u,
                    Path.Direction.CW);
            Path bump = new Path();
            bump.moveTo(-12.5f * u, top);
            bump.lineTo(12.5f * u, top);
            bump.lineTo(16.5f * u, top + 9f * u);
            bump.lineTo(-16.5f * u, top + 9f * u);
            bump.close();
            p.op(bump, Path.Op.UNION);
            float ly = top + 34f * u;
            Path ring = new Path();
            ring.addCircle(0f, ly, 16.5f * u, Path.Direction.CW);
            Path lens = new Path();
            lens.addCircle(0f, ly, 11f * u, Path.Direction.CW);
            ring.op(lens, Path.Op.DIFFERENCE);
            p.op(ring, Path.Op.DIFFERENCE);
            Path flash = new Path();
            flash.addCircle(26f * u, top + 20.5f * u, 4f * u, Path.Direction.CW);
            p.op(flash, Path.Op.DIFFERENCE);
            WHITE.setColor(0xFFFFFFFF);
            WHITE.setStyle(Paint.Style.FILL);
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(CAMERA, WHITE);
        canvas.restore();
    }

    /** The parents up to the window let the button draw past its bounds. */
    private static void unclip(View v) {
        ViewParent p = v.getParent();
        for (int i = 0; i < 4 && p instanceof ViewGroup; i++, p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (g.getClipChildren()) g.setClipChildren(false);
        }
    }
}
