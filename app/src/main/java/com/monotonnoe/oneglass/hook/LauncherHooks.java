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
import com.monotonnoe.oneglass.glass.GlyphIconDrawable;
import com.monotonnoe.oneglass.glass.HomeScope;
import com.monotonnoe.oneglass.glass.Log;
import com.monotonnoe.oneglass.glass.Units;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

/**
 * One UI Home ("honeyspace") hooks, using the same integration points as BlurIcons:
 *  - icons: the drawable set through IconViewImpl#setCompoundDrawable is wrapped, and the glass
 *    is drawn by that wrapper in the icon's own canvas (exact bounds, nothing to overwrite),
 *  - folder icons: glass drawn before IconViewImpl#onDraw,
 *  - dock, widgets, search pill: glass drawn by the parent's dispatchDraw before its children,
 *  - open folder panel: its background is replaced by glass and kept on setBackgroundDrawable.
 * A hook on View#onAttachedToWindow finds the views by class name. The apps screen is untouched.
 *
 * Class names below are the ones One UI 7 / 8 / 8.5 use.
 */
public final class LauncherHooks {

    static final String HOTSEAT = "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatCellLayout";
    static final String ICON = "com.honeyspace.ui.common.iconview.IconViewImpl";
    static final String FOLDER_ICON = "com.honeyspace.ui.common.iconview.FolderIconViewImpl";
    static final String PAGE = "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayout";

    private static final int K_NONE = 0;
    private static final int K_HOTSEAT = 1;
    private static final int K_ICON = 2;
    private static final int K_FOLDER_ICON = 3;
    private static final int K_WIDGET = 4;
    private static final int K_INDICATOR = 5;
    private static final int K_PAGE = 6;

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

        hookIconDrawables(cl);
        hookFolderIcons(cl);
        FolderPanels.hookBackgrounds();

        Log.i("hooked One UI Home; hotseat class "
                + (XposedHelpers.findClassIfExists(HOTSEAT, cl) != null ? "found" : "MISSING")
                + ", icon class " + (XposedHelpers.findClassIfExists(ICON, cl) != null ? "found" : "MISSING"));
    }

    /**
     * Home screen icons go through {@link GlyphIconDrawable} so the iOS 27 Clear / Tinted symbol
     * can be drawn (BlurIcons hooks the same setter). The apps screen keeps the original icon.
     */
    private static void hookIconDrawables(ClassLoader cl) {
        Class<?> icon = XposedHelpers.findClassIfExists(ICON, cl);
        if (icon == null) {
            Log.i("no " + ICON + ": clear / tinted icons unavailable");
            return;
        }
        final Class<?> exact = icon;
        java.util.Set<de.robv.android.xposed.XC_MethodHook.Unhook> h =
                de.robv.android.xposed.XposedBridge.hookAllMethods(icon, "setCompoundDrawable", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject.getClass() == exact) {
                            wrapArgs(param.args);
                        }
                    }
                });
        int n = h == null ? 0 : h.size();
        if (n == 0) {
            for (String m : new String[]{"setCompoundDrawables", "setCompoundDrawablesRelative"}) {
                h = de.robv.android.xposed.XposedBridge.hookAllMethods(android.widget.TextView.class, m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject.getClass() == exact && param.args.length == 4) {
                            wrapArgs(param.args);
                        }
                    }
                });
                n += h == null ? 0 : h.size();
            }
        }
        Log.i("icon drawable hooks: " + n);
    }

    private static void wrapArgs(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Drawable && !(args[i] instanceof GlyphIconDrawable)
                    && !(args[i] instanceof android.graphics.drawable.ColorDrawable)) {
                args[i] = new GlyphIconDrawable((Drawable) args[i]);
                return;
            }
        }
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
        com.monotonnoe.oneglass.glass.LightSensor.start(context);
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    loadConfig();
                    if (intent.getBooleanExtra("picture", false)) {
                        Backdrop.reload();
                    } else {
                        Backdrop.onConfigChanged();
                    }
                    applyWidgetClips();
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
        if (!Shapes.otherPill && v.getClass().getName().startsWith("dev.flexy.searchpill.")) {
            // SearchPill already adds an iOS search pill: do not draw a second one
            Shapes.otherPill = true;
            Log.i("SearchPill module detected: OneGlass' own pill is off");
            GlassTracker.refreshAll();
        }
        int kind = kindOf(v.getClass());
        if (kind == K_NONE || !DONE.add(v)) {
            return;
        }
        ViewParent p = v.getParent();
        switch (kind) {
            case K_HOTSEAT:
                IosGrid.hookLayout(v.getClass(), true);
                if (p instanceof ViewGroup) {
                    GlassHosts.add((ViewGroup) p, new GlassDrawable(GlassSpec.ROLE_DOCK, new Shapes.Hotseat(v)));
                    Log.i("dock glass drawn by " + p.getClass().getName());
                }
                if (Config.current.iosGrid) {
                    v.requestLayout();
                }
                break;
            case K_PAGE:
                IosGrid.hookLayout(v.getClass(), false);
                if (Config.current.iosGrid) {
                    v.requestLayout();
                }
                break;
            case K_WIDGET:
                if (HomeScope.isHome(v) && p instanceof ViewGroup) {
                    GlassHosts.add((ViewGroup) p, new GlassDrawable(GlassSpec.ROLE_WIDGET, new Shapes.Child(v, false)));
                    applyWidgetClip(v);
                } else {
                    DONE.remove(v);
                }
                break;
            case K_INDICATOR:
                if (!HomeScope.inAppsScreen(v) && p instanceof ViewGroup) {
                    GlassHosts.add((ViewGroup) p, new GlassDrawable(GlassSpec.ROLE_PILL, new Shapes.Child(v, true)));
                }
                break;
            case K_ICON:
                // glass is drawn by the icon's drawable wrapper; icons inside an open folder
                // lead us to the folder panel
                FolderPanels.onIcon(v);
                DONE.remove(v);
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
            } else if (n.equals(PAGE) || n.endsWith(".WorkspaceCellLayout")) {
                kind = K_PAGE;
            } else if (n.equals(FOLDER_ICON) || n.endsWith(".FolderIconViewImpl")) {
                kind = K_FOLDER_ICON;
            } else if (n.equals(ICON) || n.endsWith(".IconViewImpl")) {
                kind = K_ICON;
            } else if (c == AppWidgetHostView.class) {
                kind = K_WIDGET;
            } else if (n.startsWith("com.honeyspace.") && c.getSimpleName().equals("PageIndicatorView")) {
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

    /** Folder icons: glass drawn right before the icon draws itself (BlurIcons: same hook). */
    private static void hookFolderIcons(ClassLoader cl) {
        Class<?> folder = XposedHelpers.findClassIfExists(FOLDER_ICON, cl);
        Class<?> icon = XposedHelpers.findClassIfExists(ICON, cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 1 && param.args[0] instanceof android.graphics.Canvas
                        && param.thisObject.getClass().getSimpleName().startsWith("FolderIcon")) {
                    try {
                        FolderPanels.drawFolderIcon((View) param.thisObject, (android.graphics.Canvas) param.args[0]);
                    } catch (Throwable t) {
                        Log.e("folder icon glass", t);
                    }
                }
            }
        };
        java.util.Set<XC_MethodHook.Unhook> h = folder == null ? null
                : de.robv.android.xposed.XposedBridge.hookAllMethods(folder, "onDraw", hook);
        String on = folder == null ? "-" : folder.getName();
        if ((h == null || h.isEmpty()) && icon != null) {
            h = de.robv.android.xposed.XposedBridge.hookAllMethods(icon, "onDraw", hook);
            on = icon.getName();
        }
        Log.i("folder icon glass hooks: " + (h == null ? 0 : h.size()) + " on " + on);
    }

    private static final Set<View> WIDGETS = Collections.newSetFromMap(new WeakHashMap<>());

    private static void applyWidgetClip(final View host) {
        WIDGETS.add(host);
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
                float pt = Units.pt(view);
                r.set(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getWidth() - view.getPaddingRight(), view.getHeight() - view.getPaddingBottom());
                ContinuousPath.build(path, r, c.widgetRadius * pt, ContinuousPath.IOS_SMOOTHING);
                outline.setPath(path);
            }
        });
        Config c = Config.current;
        host.setClipToOutline(c.widgets && c.widgetClip);
        host.invalidateOutline();
    }

    private static void applyWidgetClips() {
        Config c = Config.current;
        for (View w : WIDGETS) {
            if (w != null) {
                w.setClipToOutline(c.widgets && c.widgetClip);
                w.invalidateOutline();
            }
        }
    }
}
