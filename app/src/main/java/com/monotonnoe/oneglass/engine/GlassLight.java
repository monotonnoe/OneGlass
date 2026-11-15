package com.monotonnoe.oneglass.engine;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The light on the glass rims, as on iOS 26's Home screen: one light for every rim (icons,
 * folders, widgets, search bars), turning with the phone's tilt and settling back as the phone
 * is held still. Only the module's own glass nodes are recorded again for it, on the steps the
 * light takes (a few degrees each, at most 25 a second), and only those on screen; nothing
 * runs while the phone is still, while One UI Home is not in front, or with the screen off.
 * At rest the light is where it always was (top and bottom), so 0 is the look of before.
 * Main thread only.
 */
final class GlassLight {
    private static final String HOME = "com.sec.android.app.launcher.Launcher";

    /** Where the light travels, in screen terms (x right, y down), and each end's share. */
    /**
     * At rest the light falls from the top, as on iOS 27's Home screen: the top and bottom
     * edges lit, the sides dark (6.88 and before: iOS 26's diagonal, 45 degrees).
     */
    static final float REST = 0f;
    static float dirX = (float) Math.sin(REST);
    static float dirY = (float) Math.cos(REST);
    static float near = 1f;
    static float far = 1f;
    /** Bumped for each step the light takes: a glass relights when its own value differs. */
    static int generation;
    static int steps;

    private static final int ANGLES = 96;
    private static final float STEP = (float) (2 * Math.PI / ANGLES);
    /** The same rate as the photo widget's motion asks for: the sensor serves both at once. */
    private static final int PERIOD_US = 33333;
    private static final long MIN_STEP_MS = 40;
    /** Tremor under this (in g) moves nothing. */
    private static final float DEAD = 0.012f;
    /** How fast the rest follows the way the phone is held (about 2 s back to rest). */
    private static final float REST_DRIFT = 0.015f;
    private static final float SMOOTH = 0.3f;
    /** Radians of light per g of roll, times the setting (0.6: 15 degrees, about 45). */
    private static final float ROLL_GAIN = 5f;
    private static final float MAX_ANGLE = 1.22f;
    /** Pitch, per g, that dims the far end fully at the default setting (about 10 degrees). */
    private static final float PITCH_GAIN = 10f;
    private static final float DIM = 0.6f;
    /** A step needs this much of a step's change: no flicker between two steps. */
    private static final float HYST = 0.75f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, Boolean> FOCUS_WATCHED = new WeakHashMap<>();
    private static SensorManager sensors;
    private static Sensor sensor;
    private static boolean installed;
    private static boolean resumed;
    private static boolean focused;
    private static boolean focusUnknown;
    private static boolean screenOn = true;
    private static boolean listening;
    /** Stopped because nothing answered its steps (Recents, a covered Home): until drawn. */
    private static boolean idle;
    private static boolean rested;
    private static int rotation;
    private static int angleIdx;
    private static int nearQ = 16;
    private static int farQ = 16;
    private static int misses;
    private static int answeredGen;
    private static int logLeft = 24;
    private static boolean signLogged;
    private static float restX;
    private static float restY;
    private static float smX;
    private static float smY;
    private static long lastStep;
    private static WeakReference<View> decor;
    private static WeakReference<Activity> home;

    private GlassLight() {
    }

    /** The launcher's own activity (or a subclass), not its settings or other screens. */
    static boolean isHome(Activity a) {
        Class<?> c = a.getClass();
        for (int i = 0; i < 5 && c != null; i++, c = c.getSuperclass()) {
            if (HOME.equals(c.getName())) return true;
        }
        return false;
    }

    /** Launcher side, once the Application exists (LiquidGlass.install). */
    static void install(Context app) {
        if (installed) return;
        installed = true;
        sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (sensor == null) sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        PowerManager pm = app.getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
                reconsider("screen");
            }
        }, f, null, MAIN, Context.RECEIVER_NOT_EXPORTED);
        app.registerComponentCallbacks(new android.content.ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(android.content.res.Configuration c) {
                readRotation();
            }

            @Override
            public void onLowMemory() {
            }
        });
    }

    private static void readRotation() {
        Activity a = home != null ? home.get() : null;
        if (a == null) return;
        try {
            android.view.Display d = a.getDisplay();
            if (d != null) rotation = d.getRotation();
        } catch (Throwable ignored) {
        }
    }

    static void onResumed(Activity a) {
        if (!isHome(a)) return;
        resumed = true;
        idle = false;
        misses = 0;
        answeredGen = generation;
        home = new WeakReference<>(a);
        readRotation();
        View d = a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (d != null) {
            decor = new WeakReference<>(d);
            if (!FOCUS_WATCHED.containsKey(d)) {
                FOCUS_WATCHED.put(d, Boolean.TRUE);
                try {
                    d.getViewTreeObserver().addOnWindowFocusChangeListener(
                            (ViewTreeObserver.OnWindowFocusChangeListener) has -> {
                                focused = has;
                                reconsider("focus");
                            });
                } catch (Throwable t) {
                    focusUnknown = true;
                }
            }
        }
        focused = a.hasWindowFocus();
        reconsider("resumed");
    }

    static void onPaused(Activity a) {
        if (!isHome(a)) return;
        resumed = false;
        reconsider("paused");
    }

    static void onDestroyed(Activity a) {
        if (!isHome(a)) return;
        Activity h = home != null ? home.get() : null;
        if (h == a) {
            resumed = false;
            home = null;
            decor = null;
            reconsider("destroyed");
        }
    }

    /** Starts or stops the sensor as the conditions now say. */
    static void reconsider(String why) {
        boolean want = installed && resumed && (focused || focusUnknown) && screenOn && !idle
                && Config.blur && Config.glassLight > 0f && Config.glassRim > 0f
                && Settings.style == Settings.STYLE_LIQUID && LiquidGlass.available();
        if (want == listening) return;
        if (want) {
            start(why);
        } else {
            stop(why);
        }
    }

    private static void start(String why) {
        if (sensors == null || sensor == null) {
            if (logLeft > 0) {
                logLeft = 0;
                XLog.i("rim light: no gravity sensor, it stays still");
            }
            return;
        }
        try {
            listening = sensors.registerListener(LISTENER, sensor, PERIOD_US, MAIN);
        } catch (Throwable t) {
            listening = false;
            XLog.e("rim light sensor", t);
        }
        rested = false;
        misses = 0;
        answeredGen = generation;
        if (!listening) XLog.i("rim light: the sensor refused");
        log("on (" + why + ")" + (listening ? "" : ", the sensor refused"));
    }

    private static void stop(String why) {
        if (!listening) return;
        try {
            sensors.unregisterListener(LISTENER);
        } catch (Throwable ignored) {
        }
        listening = false;
        rested = false;
        // The light stays where it was: the next start rests at the hold of then, and its
        // first steps bring the light back to rest.
        log("off (" + why + ")");
    }

    /** The light going on and off, a few times per launch: only with {@link Config#DEBUG}. */
    private static void log(String s) {
        if (!Config.DEBUG || logLeft <= 0) return;
        logLeft--;
        XLog.debug("rim light " + s);
    }

    private static float dead(float v) {
        return Math.signum(v) * Math.max(0f, Math.abs(v) - DEAD);
    }

    private static int quantise(float value, int now) {
        return Math.abs(value - now) > HYST ? Math.round(value) : now;
    }

    private static final SensorEventListener LISTENER = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            if (!listening) return;
            float x = e.values[0] / SensorManager.GRAVITY_EARTH;
            float y = e.values[1] / SensorManager.GRAVITY_EARTH;
            float sx;
            float sy;
            switch (rotation) {
                case Surface.ROTATION_90:
                    sx = -y;
                    sy = x;
                    break;
                case Surface.ROTATION_180:
                    sx = -x;
                    sy = -y;
                    break;
                case Surface.ROTATION_270:
                    sx = y;
                    sy = -x;
                    break;
                default:
                    sx = x;
                    sy = y;
            }
            if (!rested) {
                restX = sx;
                restY = sy;
                smX = 0f;
                smY = 0f;
                rested = true;
            }
            restX += (sx - restX) * REST_DRIFT;
            restY += (sy - restY) * REST_DRIFT;
            smX += (dead(sx - restX) - smX) * SMOOTH;
            smY += (dead(sy - restY) - smY) * SMOOTH;
            float k = Config.glassLight;
            float theta = Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, -ROLL_GAIN * k * smX));
            float p = Math.max(-1f, Math.min(1f, -PITCH_GAIN * k * smY));
            int idx = quantise(theta / STEP, angleIdx);
            int nf = quantise(16f * (1f - DIM * Math.max(0f, p)), farQ);
            int nn = quantise(16f * (1f - DIM * Math.max(0f, -p)), nearQ);
            if (idx == angleIdx && nf == farQ && nn == nearQ) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastStep < MIN_STEP_MS) return;
            if (generation - answeredGen >= 3) {
                // Three steps and no frame drew any: nothing of the glass is on screen.
                idle = true;
                stop("unanswered");
                return;
            }
            angleIdx = idx;
            farQ = nf;
            nearQ = nn;
            apply();
            lastStep = now;
            if (Config.DEBUG && !signLogged) {
                signLogged = true;
                XLog.debug(String.format(java.util.Locale.ROOT,
                        "rim light first step: roll %.3f g pitch %.3f g -> angle %d near %d"
                                + " far %d", smX, smY, idx, nn, nf));
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {
        }
    };

    /** The light as the quantised state says, and a frame for the glass to take it. */
    private static void apply() {
        float a = REST + angleIdx * STEP;
        dirX = (float) Math.sin(a);
        dirY = (float) Math.cos(a);
        far = farQ / 16f;
        near = nearQ / 16f;
        generation++;
        steps++;
        View d = decor != null ? decor.get() : null;
        if (d != null && d.isAttachedToWindow()) d.invalidate();
    }

    /**
     * After each pre-draw pass: {@code answered} glasses took the light (or will, after a
     * zoom). A step nothing on screen took is a miss; three in a row stop the sensor until a
     * glass is drawn again (wake).
     */
    static void afterSync(int answered) {
        if (!listening || generation == answeredGen) return;
        answeredGen = generation;
        if (answered > 0) {
            misses = 0;
        } else if (++misses >= 3) {
            idle = true;
            stop("idle");
        }
    }

    /** A glass was drawn or recorded: the light runs again if it stopped for want of one. */
    static void wake() {
        if (idle) {
            idle = false;
            misses = 0;
            reconsider("glass drawn");
        }
    }

    /** A setting changed: the light back at rest, and on or off as the settings say. */
    static void onSettingsChanged() {
        boolean atRest = angleIdx == 0 && nearQ == 16 && farQ == 16;
        angleIdx = 0;
        nearQ = 16;
        farQ = 16;
        rested = false;
        if (!atRest) apply();
        idle = false;
        reconsider("setting");
    }
}
