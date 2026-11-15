package com.monotonnoe.oneglass.glass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import com.monotonnoe.oneglass.Config;

/**
 * Lets the glass highlight follow the phone's tilt, like iOS 26/27 (and BlurIcons' GlassLight).
 * Upright the light sits at the angle fitted from the Figma render; tilting the phone sideways
 * swings it by the roll angle. Rate-limited to 20 updates per second.
 */
public final class LightSensor implements SensorEventListener {

    private static final long MIN_INTERVAL_MS = 50;
    private static final float MIN_STEP_DEG = 1.5f;
    private static final float MAX_SWING_DEG = 60f;

    private static volatile float offsetDeg;
    private static LightSensor instance;

    private float applied;
    private long last;

    private LightSensor() {
    }

    /** Degrees to add to the base light angle. */
    public static float offset() {
        return Config.current.lightTilt ? offsetDeg : 0f;
    }

    public static void start(Context context) {
        if (instance != null) {
            return;
        }
        try {
            SensorManager sm = context.getSystemService(SensorManager.class);
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (s == null) {
                s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (s == null) {
                return;
            }
            instance = new LightSensor();
            sm.registerListener(instance, s, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            Log.e("light sensor", t);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!Config.current.lightTilt) {
            return;
        }
        float gx = e.values[0];
        float gy = e.values[1];
        if (Math.abs(gx) + Math.abs(gy) < 1f) {
            return; // lying flat: keep the last angle
        }
        float roll = (float) Math.toDegrees(Math.atan2(gx, gy));
        roll = Math.max(-MAX_SWING_DEG, Math.min(MAX_SWING_DEG, roll));
        long now = SystemClock.uptimeMillis();
        if (Math.abs(roll - applied) < MIN_STEP_DEG || now - last < MIN_INTERVAL_MS) {
            return;
        }
        applied = roll;
        last = now;
        offsetDeg = roll;
        GlassTracker.refreshAll();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
