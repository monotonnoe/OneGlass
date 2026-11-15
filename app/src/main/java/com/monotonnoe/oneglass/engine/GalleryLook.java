package com.monotonnoe.oneglass.engine;

import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The photo widget's look, as the iPhone offers: full colour, monochrome or tinted with the
 * glass colour, and the "spatial scene": the photo a little larger than its frame, sliding
 * with the phone's tilt so it seems to sit behind the glass. Both go straight onto the
 * ImageView that holds the picture: a colour filter, and its scale and translation. One UI
 * never sets those on a widget's content, only on the host round it.
 */
final class GalleryLook {
    static final int FULL = 0;
    static final int MONO = 1;
    static final int TINTED = 2;

    private static final float SCALE = 1.08f;
    /**
     * Monochrome and tinted photos let the glass through where they are dark, as on the
     * iPhone: a point's opacity follows its brightness, from this floor for black up to full
     * for white.
     */
    private static final float DARK_ALPHA = 0.12f;
    /** How far the photo may slide, as a part of its width, per g of tilt from rest. */
    private static final float SLIDE = 0.06f;
    /** How fast the photo follows the tilt: about 63% of the way in this time. */
    private static final float FOLLOW_MS = 80f;
    /** Sensor events between two checks that a photo widget is still on screen. */
    private static final int VISIBLE_EVERY = 10;

    /**
     * The hosts with pictures, and their picture views as last found (no walk per tilt). The
     * views are held weakly: they reach their host through their parents, and a strong list
     * would keep every host (and its activity) alive.
     */
    private static final Map<View, List<WeakReference<ImageView>>> HOSTS = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final android.graphics.Rect VISIBLE = new android.graphics.Rect();
    private static int appliedLook = -1;
    private static int appliedTint;
    private static float appliedLight;
    private static ColorFilter lookFilter;
    private static SensorManager sensors;
    private static Context appContext;
    private static boolean listening;
    private static boolean homeResumed = true;
    private static int sinceVisibleCheck;
    private static float restX;
    private static float restY;
    private static boolean rested;
    /** Where the photo heads (from the sensor) and where it is (stepped each frame). */
    private static float targetX;
    private static float targetY;
    private static float shownX;
    private static float shownY;
    private static long lastFrameNanos;
    private static boolean framePosted;
    private static boolean described;

    private GalleryLook() {
    }

    /** Called when the cell layout drew a photo widget host: keeps its look and motion right. */
    static void drawn(View host) {
        List<ImageView> images = images(host);
        if (images.isEmpty()) {
            if (!described) {
                described = true;
                XLog.i("gallery look: no picture view in " + describe(host, 0));
            }
            return;
        }
        boolean spatial = Config.gallerySpatial;
        int look = Config.galleryLook;
        if (!HOSTS.containsKey(host)) {
            XLog.i("gallery look on " + images.size() + " picture view(s): "
                    + images.get(0).getClass().getName() + " " + images.get(0).getWidth() + "x"
                    + images.get(0).getHeight());
        }
        List<WeakReference<ImageView>> refs = HOSTS.get(host);
        if (!same(refs, images)) {
            refs = new ArrayList<>(images.size());
            for (ImageView i : images) refs.add(new WeakReference<>(i));
            HOSTS.put(host, refs);
        }
        final ColorFilter filter = filter(look);
        final float scale = spatial ? SCALE : 1f;
        final float alpha = 1f;
        for (final ImageView image : images) {
            final boolean colour = image.getColorFilter() != filter
                    || Math.abs(image.getAlpha() - alpha) > 0.01f;
            final boolean size = Math.abs(image.getScaleX() - scale) > 0.001f;
            if (!colour && !size) continue;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (colour) {
                        image.setColorFilter(filter);
                        image.setAlpha(alpha);
                    }
                    if (size) {
                        image.setScaleX(scale);
                        image.setScaleY(scale);
                        if (scale == 1f) {
                            image.setTranslationX(0f);
                            image.setTranslationY(0f);
                        }
                    }
                }
            });
        }
        if (spatial && homeResumed) listen(host.getContext());
    }

    private static boolean same(List<WeakReference<ImageView>> refs, List<ImageView> images) {
        if (refs == null || refs.size() != images.size()) return false;
        for (int i = 0; i < images.size(); i++) {
            if (refs.get(i).get() != images.get(i)) return false;
        }
        return true;
    }

    /**
     * Whether a photo widget is on screen now: attached, shown, not on a page scrolled away,
     * and not faded out with its page (the Apps screen over Home, a folder open).
     */
    private static boolean anyVisible() {
        for (View host : HOSTS.keySet()) {
            if (host == null || !host.isAttachedToWindow() || !host.isShown()) continue;
            if (!host.getGlobalVisibleRect(VISIBLE) || VISIBLE.isEmpty()) continue;
            float alpha = 1f;
            for (Object v = host; v instanceof View && alpha > 0.01f;
                    v = ((View) v).getParent()) {
                alpha *= ((View) v).getAlpha();
            }
            if (alpha > 0.01f) return true;
        }
        return false;
    }

    /** The photo views inside the host: the widget's picture, however it is wrapped. */
    private static List<ImageView> images(View host) {
        List<ImageView> out = new ArrayList<>();
        collect(host, out, 0);
        return out;
    }

    private static void collect(View v, List<ImageView> out, int depth) {
        if (v instanceof ImageView) {
            ImageView i = (ImageView) v;
            // Only pictures of a size: not buttons or badges.
            if (i.getWidth() > 64 && i.getHeight() > 64 && i.getDrawable() != null) out.add(i);
            return;
        }
        if (depth >= 6 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int k = 0; k < g.getChildCount(); k++) collect(g.getChildAt(k), out, depth + 1);
    }

    private static String describe(View v, int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("  ");
        b.append(v.getClass().getName()).append(' ').append(v.getWidth()).append('x')
                .append(v.getHeight()).append('\n');
        if (depth < 6 && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount() && k < 8; k++) {
                b.append(describe(g.getChildAt(k), depth + 1));
            }
        }
        return b.toString();
    }

    private static ColorFilter filter(int look) {
        int tint = look == TINTED ? (Settings.tintColor != 0 ? Settings.tintColor
                : LiquidGlass.wallpaperTone()) : 0;
        float light = Config.galleryLight;
        if (look == appliedLook && tint == appliedTint && light == appliedLight) return lookFilter;
        appliedLook = look;
        appliedTint = tint;
        appliedLight = light;
        if (look == FULL) {
            lookFilter = null;
            return null;
        }
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        // After that every channel holds the brightness: the alpha row reads it from red.
        float[] scale = {1f, 1f, 1f};
        float[] offset = {0f, 0f, 0f};
        if (look == TINTED) {
            // A duotone, as the iPhone's tinted photos: the shadows in a deep shade of the
            // colour, the lights in a pale one, every grey between the two.
            float r = ((tint >> 16) & 0xFF) / 255f;
            float g = ((tint >> 8) & 0xFF) / 255f;
            float b = (tint & 0xFF) / 255f;
            // Past 100% the lights are white and the shadows lift towards the colour too, so
            // the whole picture brightens; past 170% (the shadows at the full colour) the
            // shadows go on towards white, up to 90% of the way at 400%.
            float lt = Math.min(1f, light);
            float lift = Math.min(1f, 0.16f + Math.max(0f, light - 1f) * 1.2f);
            float pale = Math.max(0f, Math.min(1f, (light - 1.7f) / 2.3f)) * 0.9f;
            float[] dark = {r * lift, g * lift, b * lift};
            for (int i = 0; i < 3; i++) dark[i] += (1f - dark[i]) * pale;
            float[] lights = {r + (1f - r) * lt, g + (1f - g) * lt, b + (1f - b) * lt};
            for (int i = 0; i < 3; i++) {
                scale[i] = lights[i] - dark[i];
                offset[i] = dark[i] * 255f;
            }
        }
        ColorMatrix d = new ColorMatrix(new float[]{
                scale[0], 0, 0, 0, offset[0],
                0, scale[1], 0, 0, offset[1],
                0, 0, scale[2], 0, offset[2],
                1f - DARK_ALPHA, 0, 0, 0, DARK_ALPHA * 255f});
        m.postConcat(d);
        lookFilter = new ColorMatrixColorFilter(m);
        return lookFilter;
    }

    private static final SensorEventListener LISTENER = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            if (!listening) return;
            if (++sinceVisibleCheck >= VISIBLE_EVERY) {
                sinceVisibleCheck = 0;
                if (!anyVisible()) {
                    // Nothing of ours on screen: stop until a widget is drawn again.
                    stop();
                    return;
                }
            }
            float gx = e.values[0] / SensorManager.GRAVITY_EARTH;
            float gy = e.values[1] / SensorManager.GRAVITY_EARTH;
            if (!rested) {
                restX = gx;
                restY = gy;
                rested = true;
            }
            // The rest position drifts slowly towards how the phone is held, so the photo
            // settles back to centre instead of staying pushed to one side.
            restX += (gx - restX) * 0.005f;
            restY += (gy - restY) * 0.005f;
            targetX = clamp((gx - restX) * SLIDE, -SLIDE, SLIDE);
            targetY = clamp((restY - gy) * SLIDE, -SLIDE, SLIDE);
            postFrame();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /**
     * Moves the photo once per display frame towards where the tilt wants it, by time rather
     * than per sensor event: it glides at the panel's rate (120 Hz) instead of stepping at the
     * sensor's.
     */
    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            framePosted = false;
            long last = lastFrameNanos;
            lastFrameNanos = nanos;
            float dt = last == 0L ? 8f : Math.min(50f, Math.max(0f, (nanos - last) / 1e6f));
            float k = 1f - (float) Math.exp(-dt / FOLLOW_MS);
            shownX += (targetX - shownX) * k;
            shownY += (targetY - shownY) * k;
            boolean moving = false;
            for (Map.Entry<View, List<WeakReference<ImageView>>> entry : HOSTS.entrySet()) {
                View host = entry.getKey();
                if (host == null || !host.isShown()) continue;
                float dx = -shownX * host.getWidth();
                float dy = -shownY * host.getHeight();
                for (WeakReference<ImageView> ref : entry.getValue()) {
                    ImageView c = ref.get();
                    if (c == null) continue;
                    if (Math.abs(c.getTranslationX() - dx) < 0.05f
                            && Math.abs(c.getTranslationY() - dy) < 0.05f) {
                        continue;
                    }
                    c.setTranslationX(dx);
                    c.setTranslationY(dy);
                    moving = true;
                }
            }
            // On until the photo reaches where the tilt wants it; the next sensor event
            // posts again.
            if (moving || Math.abs(targetX - shownX) > 1e-4f
                    || Math.abs(targetY - shownY) > 1e-4f) {
                postFrame();
            } else {
                lastFrameNanos = 0L;
            }
        }
    };

    private static void postFrame() {
        if (framePosted || !Config.gallerySpatial) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(FRAME);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void listen(Context context) {
        if (listening) return;
        if (appContext == null) appContext = context.getApplicationContext();
        if (sensors == null) {
            sensors = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        }
        if (sensors == null) return;
        Sensor gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravity == null) gravity = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gravity == null) return;
        // The game rate (~50 Hz): the photo itself moves per display frame, by FRAME.
        listening = sensors.registerListener(LISTENER, gravity, SensorManager.SENSOR_DELAY_GAME,
                MAIN);
        sinceVisibleCheck = 0;
    }

    private static void stop() {
        if (!listening || sensors == null) return;
        sensors.unregisterListener(LISTENER);
        listening = false;
        rested = false;
        // The photo stays where it is: the next start rests at the new hold and glides back
        // to centre.
        targetX = shownX;
        targetY = shownY;
    }

    /** One UI Home's activity went to the background (an app, the screen off). */
    static void onHomePaused() {
        homeResumed = false;
        stop();
    }

    /** One UI Home is in front again: the motion restarts if a photo widget is on screen. */
    static void onHomeResumed() {
        homeResumed = true;
        if (!Config.gallerySpatial || appContext == null || listening || HOSTS.isEmpty()) return;
        MAIN.post(() -> {
            if (homeResumed && Config.gallerySpatial && !listening && anyVisible()) {
                listen(appContext);
            }
        });
    }

    /** When the settings change: the colour is re-made, the motion stopped if turned off. */
    static void onSettingsChanged() {
        appliedLook = -1;
        if (!Config.gallerySpatial) stop();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<View, List<WeakReference<ImageView>>> e : HOSTS.entrySet()) {
                    View host = e.getKey();
                    if (host == null) continue;
                    if (!Config.gallerySpatial) {
                        targetX = targetY = shownX = shownY = 0f;
                        for (WeakReference<ImageView> ref : e.getValue()) {
                            ImageView c = ref.get();
                            if (c == null) continue;
                            c.setScaleX(1f);
                            c.setScaleY(1f);
                            c.setTranslationX(0f);
                            c.setTranslationY(0f);
                        }
                    }
                    host.invalidate();
                }
            }
        });
    }
}
