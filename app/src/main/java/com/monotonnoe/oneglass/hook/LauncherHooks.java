package com.monotonnoe.oneglass.hook;

import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;

import com.monotonnoe.oneglass.Config;
import com.monotonnoe.oneglass.glass.Backdrop;
import com.monotonnoe.oneglass.glass.ContinuousPath;
import com.monotonnoe.oneglass.glass.GlassDrawable;
import com.monotonnoe.oneglass.glass.GlassSpec;
import com.monotonnoe.oneglass.glass.GlassTracker;
import com.monotonnoe.oneglass.glass.IconRimDrawable;
import com.monotonnoe.oneglass.glass.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

/**
 * One UI Home ("honeyspace") hooks. Instead of hooking each launcher class' drawing methods
 * (their names change between One UI builds), a single hook on View#onAttachedToWindow spots
 * the views we care about by class name and decorates them with public APIs: a glass
 * background, a view overlay, an outline clip.
 *
 * Class names below are the ones One UI 7 / 8 / 8.5 use.
 */
public final class LauncherHooks {

    static final String HOTSEAT = "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatCellLayout";
    static final String ICON = "com.honeyspace.ui.common.iconview.IconViewImpl";
    static final String FOLDER_ICON = "com.honeyspace.ui.common.iconview.FolderIconViewImpl";

    private static final int K_NONE = 0;
    private static final int K_HOTSEAT = 1;
    private static final int K_ICON = 2;
    private static final int K_FOLDER_ICON = 3;
    private static final int K_WIDGET = 4;
    private static final int K_INDICATOR = 5;

    private static final Map<Class<?>, Integer> KINDS = new HashMap<>();
    private static final Set<View> DONE = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static XSharedPreferences prefs;
    private static boolean started;
    private static boolean dumped;

    private LauncherHooks() {
    }

    public static void install(ClassLoader cl) {
        loadConfig();

        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    onAttached((View) param.thisObject);
                } catch (Throwable t) {
                    Log.e("attach", t);
                }
            }
        });

        // The launcher reports how far the pages are scrolled; the wallpaper (and so our
        // backdrop) follows that offset when wallpaper scrolling is on.
        XposedHelpers.findAndHookMethod(WallpaperManager.class, "setWallpaperOffsets",
                IBinder.class, float.class, float.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Backdrop.setOffsets((float) param.args[1], (float) param.args[2]);
                    }
                });

        Log.i("hooked One UI Home; hotseat class "
                + (XposedHelpers.findClassIfExists(HOTSEAT, cl) != null ? "found" : "MISSING")
                + ", icon class " + (XposedHelpers.findClassIfExists(ICON, cl) != null ? "found" : "MISSING"));
    }

    private static void loadConfig() {
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(Config.class.getPackage().getName(), Config.PREFS);
            } else {
                prefs.reload();
            }
            Config c = Config.read(prefs);
            c.generation = Config.current.generation + 1;
            Config.current = c;
            if (!prefs.getFile().canRead()) {
                Log.i("settings file not readable yet, using defaults: " + prefs.getFile());
            }
        } catch (Throwable t) {
            Log.e("settings", t);
        }
    }

    private static void start(Context context) {
        Backdrop.ensure(context);
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    loadConfig();
                    Backdrop.onConfigChanged();
                    GlassTracker.refreshAll();
                    Log.i("settings reloaded");
                }
            }, new IntentFilter(Config.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.e("reload receiver", t);
        }
    }

    private static void onAttached(View v) {
        if (!started) {
            started = true;
            start(v.getContext());
        }
        if (!dumped && Config.current.diag && v.getParent() != null && !(v.getParent() instanceof View)) {
            dumped = true;
            final View root = v;
            MAIN.postDelayed(() -> Diagnostics.dump(root), 4000);
        }
        int kind = kindOf(v.getClass());
        if (kind == K_NONE || !DONE.add(v)) {
            return;
        }
        switch (kind) {
            case K_HOTSEAT:
                applyDock(v);
                break;
            case K_WIDGET:
                applyWidget(v);
                break;
            case K_INDICATOR:
                applyBackground(v, new GlassDrawable(GlassSpec.ROLE_PILL, Shapes.PILL));
                break;
            case K_FOLDER_ICON:
                applyBackground(v, new GlassDrawable(GlassSpec.ROLE_FOLDER, Shapes.ICON));
                applyRim(v);
                break;
            case K_ICON:
                applyRim(v);
                break;
            default:
                break;
        }
    }

    private static int kindOf(Class<?> cls) {
        Integer k = KINDS.get(cls);
        if (k != null) {
            return k;
        }
        int kind = K_NONE;
        for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals(HOTSEAT) || n.endsWith(".HotseatCellLayout")) {
                kind = K_HOTSEAT;
            } else if (n.equals(FOLDER_ICON) || n.endsWith(".FolderIconViewImpl")) {
                kind = K_FOLDER_ICON;
            } else if (n.equals(ICON) || n.endsWith(".IconViewImpl")) {
                kind = K_ICON;
            } else if (c == AppWidgetHostView.class) {
                kind = K_WIDGET;
            } else if (n.startsWith("com.honeyspace.") && c.getSimpleName().contains("PageIndicator")) {
                kind = K_INDICATOR;
            }
            if (kind != K_NONE) {
                break;
            }
        }
        KINDS.put(cls, kind);
        if (kind != K_NONE) {
            Log.i("glass target " + cls.getName() + " -> kind " + kind);
        }
        return kind;
    }

    private static void applyBackground(View v, GlassDrawable glass) {
        Drawable old = v.getBackground();
        if (old instanceof GlassDrawable) {
            return;
        }
        if (old == null) {
            v.setBackground(glass);
        } else {
            v.setBackground(new LayerDrawable(new Drawable[]{glass, old}));
        }
        GlassTracker.track(v, glass);
    }

    private static void applyDock(View hotseat) {
        ViewParent p = hotseat.getParent();
        if (!(p instanceof ViewGroup)) {
            Log.i("hotseat has no parent yet");
            DONE.remove(hotseat);
            return;
        }
        ViewGroup parent = (ViewGroup) p;
        if (DONE.contains(parent) && parent.getBackground() != null) {
            return;
        }
        DONE.add(parent);
        applyBackground(parent, new GlassDrawable(GlassSpec.ROLE_DOCK, new Shapes.Hotseat(hotseat)));
        Log.i("dock glass on " + parent.getClass().getName());
    }

    private static void applyWidget(final View host) {
        applyBackground(host, new GlassDrawable(GlassSpec.ROLE_WIDGET, Shapes.FILL));
        final Path path = new Path();
        final RectF r = new RectF();
        final ViewOutlineProvider stock = host.getOutlineProvider();
        host.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                Config c = Config.current;
                if (!c.widgets || !c.widgetClip || view.getWidth() <= 0) {
                    if (stock != null) {
                        stock.getOutline(view, outline);
                    }
                    return;
                }
                float dp = view.getResources().getDisplayMetrics().density;
                r.set(0, 0, view.getWidth(), view.getHeight());
                ContinuousPath.build(path, r, c.widgetRadius * dp, ContinuousPath.IOS_SMOOTHING);
                outline.setPath(path);
            }
        });
        Config c = Config.current;
        host.setClipToOutline(c.widgets && c.widgetClip);
        host.invalidateOutline();
    }

    private static void applyRim(final View v) {
        final IconRimDrawable rim = new IconRimDrawable(v);
        rim.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.getOverlay().add(rim);
        v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) ->
                rim.setBounds(0, 0, r - l, b - t));
    }
}
