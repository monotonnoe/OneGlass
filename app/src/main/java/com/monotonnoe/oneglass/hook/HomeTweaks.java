package com.monotonnoe.oneglass.hook;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.Backdrop;
import com.monotonnoe.oneglass.glass.GlassTracker;
import com.monotonnoe.oneglass.glass.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Smaller home screen behaviours BlurIcons also has, implemented here independently. */
final class HomeTweaks {

    private HomeTweaks() {
    }

    static void install(ClassLoader cl, Class<?> iconView) {
        try {
            hookBadges();
        } catch (Throwable t) {
            Log.e("badge hooks", t);
        }
        if (iconView != null) {
            try {
                hookDimEffect(iconView);
            } catch (Throwable t) {
                Log.e("dim effect hooks", t);
            }
        }
        try {
            hookWallpaperZoom(cl);
        } catch (Throwable t) {
            Log.e("wallpaper zoom hooks", t);
        }
    }

    /**
     * Work profile / clone / Monetize badges are added by PackageManager's "...Badged..." calls
     * that take the icon and return a badged copy; with "hide badges" the icon comes back as is.
     */
    private static void hookBadges() throws ClassNotFoundException {
        Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
        Set<String> names = new HashSet<>();
        int n = 0;
        for (Method m : apm.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().contains("Badged") || p.length == 0 || !Drawable.class.isAssignableFrom(p[0])
                    || !Drawable.class.isAssignableFrom(m.getReturnType()) || !names.add(m.getName())) {
                continue;
            }
            Set<XC_MethodHook.Unhook> h = XposedBridge.hookAllMethods(apm, m.getName(), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Config.current.hideBadges && param.args.length > 0 && param.args[0] instanceof Drawable) {
                        param.setResult(param.args[0]);
                    }
                }
            });
            n += h == null ? 0 : h.size();
        }
        Log.i("badge hooks: " + n + " " + names);
    }

    /** One UI dims icons in some states; iOS never does. */
    private static void hookDimEffect(Class<?> iconView) {
        int n = 0;
        for (Class<?> c = iconView; c != null && c != TextView.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) || m.getParameterTypes().length != 0
                        || m.getReturnType() != boolean.class) {
                    continue;
                }
                final boolean result;
                if (m.getName().equals("isSupportDimEffect")) {
                    result = false;
                } else if (m.getName().equals("getDisableDimEffect")) {
                    result = true;
                } else {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Config.current.noDim) {
                            param.setResult(result);
                        }
                    }
                });
                n++;
            }
        }
        Log.i("icon dim effect hooks: " + n);
    }

    /** One UI's wallpaper zoom (app launch, apps screen, recents): the glass backdrop follows. */
    private static void hookWallpaperZoom(ClassLoader cl) {
        Class<?> animator = XposedHelpers.findClassIfExists("com.honeyspace.transition.anim.WallpaperAnimator", cl);
        if (animator == null) {
            Log.i("no WallpaperAnimator: glass does not follow the wallpaper zoom");
            return;
        }
        Set<XC_MethodHook.Unhook> a = XposedBridge.hookAllMethods(animator, "setWallpaperScale", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args.length == 3 && args[1] instanceof Float && args[2] instanceof Rect) {
                    Rect r = (Rect) args[2];
                    Backdrop.setZoom((Float) args[1], r.exactCenterX(), r.exactCenterY());
                }
            }
        });
        Set<XC_MethodHook.Unhook> b = XposedBridge.hookAllMethods(animator, "destroyWallpaperSurface", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Backdrop.setZoom(1f, 0f, 0f);
                GlassTracker.refreshAll();
            }
        });
        Log.i("wallpaper zoom hooks: " + (a == null ? 0 : a.size()) + "+" + (b == null ? 0 : b.size()));
    }
}
