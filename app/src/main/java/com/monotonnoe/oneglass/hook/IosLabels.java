package com.monotonnoe.oneglass.hook;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.HomeScope;
import com.monotonnoe.oneglass.glass.Log;
import com.monotonnoe.oneglass.glass.Units;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * iOS 27 icon labels (Figma: "Label", SF Pro Text 12pt weight 500, white at 90%, drop shadow
 * y 2 / blur 25 / black 70%), with the system font in place of SF Pro. Applied to home screen
 * icons when they attach and kept when One UI sets its own size, colour or shadow again.
 */
final class IosLabels {

    private static Class<?> icon;
    private static boolean logged;

    private IosLabels() {
    }

    static void install(Class<?> iconClass) {
        icon = iconClass;
        if (iconClass == null) return;
        XposedBridge.hookAllMethods(TextView.class, "setTextSize", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                TextView tv = target(param.thisObject);
                if (tv != null && param.args.length == 2) {
                    param.args[0] = TypedValue.COMPLEX_UNIT_PX;
                    param.args[1] = 12f * Units.pt(tv);
                }
            }
        });
        XposedBridge.hookAllMethods(TextView.class, "setShadowLayer", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                TextView tv = target(param.thisObject);
                if (tv != null && param.args.length == 4) {
                    float pt = Units.pt(tv);
                    param.args[0] = 6f * pt;
                    param.args[1] = 0f;
                    param.args[2] = 1f * pt;
                    param.args[3] = 0x99000000;
                }
            }
        });
    }

    private static TextView target(Object o) {
        if (icon == null || o == null || o.getClass() != icon) return null;
        Config c = Config.current;
        if (!c.iosLabels) return null;
        TextView tv = (TextView) o;
        return tv.isAttachedToWindow() && HomeScope.isHome(tv) ? tv : null;
    }

    static void apply(View v) {
        if (!Config.current.iosLabels || !(v instanceof TextView) || !HomeScope.isHome(v)) return;
        TextView tv = (TextView) v;
        float pt = Units.pt(tv);
        try {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * pt);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setTextColor(0xE6FFFFFF);
            tv.setShadowLayer(6f * pt, 0f, 1f * pt, 0x99000000);
            tv.setCompoundDrawablePadding(Math.round(5f * pt));
            if (!logged) {
                logged = true;
                Log.i("iOS labels on " + tv.getClass().getName());
            }
        } catch (Throwable t) {
            Log.e("ios labels", t);
        }
    }
}
