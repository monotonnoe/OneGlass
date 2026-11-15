package com.monotonnoe.oneglass.engine;

import android.app.Application;
import android.app.WallpaperManager;
import android.app.Instrumentation;
import android.content.pm.LauncherActivityInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Entry implements IXposedHookLoadPackage {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final String ICON_VIEW = "com.honeyspace.ui.common.iconview.IconViewImpl";
    private static final String ACTIVITY_INFO_WRAPPER =
            "com.honeyspace.common.wrapper.LauncherActivityInfoWrapper";

    private static boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (FingerprintIcon.PACKAGE.equals(lpparam.packageName)) {
            try {
                FingerprintIcon.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("fingerprint icon install failed", t);
            }
            return;
        }
        if (LockScreenDiag.SYSTEMUI.equals(lpparam.packageName)
                && LockScreenDiag.SYSTEMUI.equals(lpparam.processName)) {
            try {
                LockScreenDiag.install(lpparam);
                LockGlass.install(lpparam);
                LockNotifications.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("lock screen install failed", t);
            }
            try {
                LockBottom.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("lock bottom install failed", t);
            }
            try {
                LockButtons.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("lock buttons install failed", t);
            }
            try {
                NavHandle.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("navigation handle install failed", t);
            }
            return;
        }
        if (!LAUNCHER.equals(lpparam.packageName) || !LAUNCHER.equals(lpparam.processName)
                || installed) {
            return;
        }
        installed = true;
        try {
            File dataDir = new File(lpparam.appInfo.dataDir);
            Config.load(new File(dataDir, "files/oneglass.conf"));
            Settings.loadLocal(dataDir);
            resetIconCacheIfStale(dataDir);
            hookBadges();
            if (Config.glassIcons) hookIconFactory(lpparam.classLoader);
            if (Config.blur) {
                hookIconView(lpparam.classLoader);
                hookWallpaperOffsets();
                hookWallpaperZoom(lpparam.classLoader);
                CellGlass.install(lpparam.classLoader);
                hookSearchBars();
            }
            XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate",
                    SafeHook.after("callApplicationOnCreate", p -> {
                        if (p.args.length > 0 && p.args[0] instanceof Application) {
                            Settings.install((Application) p.args[0]);
                            if (Config.blur) {
                                LockLink.install((Application) p.args[0]);
                                LiquidGlass.install((Application) p.args[0]);
                            }
                        }
                    }));
            XLog.i("installed in " + lpparam.processName + " [" + Config.VERSION + "]");
        } catch (Throwable t) {
            XLog.e("install failed", t);
        }
    }

    /**
     * One UI Home keeps rendered icons in databases/Icon.db. When the glass recipe or its
     * settings change, drop that cache before the launcher opens it so every icon is rebuilt.
     */
    private static void resetIconCacheIfStale(File dataDir) {
        File sig = new File(dataDir, "files/oneglass.sig");
        String want = Config.signature();
        try {
            if (sig.isFile()) {
                String have = new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8);
                if (want.equals(have)) return;
            }
            File db = new File(dataDir, "databases");
            int removed = 0;
            for (String name : new String[]{"Icon.db", "Icon.db-wal", "Icon.db-shm",
                    "Icon.db-journal"}) {
                File f = new File(db, name);
                if (f.exists() && f.delete()) removed++;
            }
            File parent = sig.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(sig)) {
                out.write(want.getBytes(StandardCharsets.UTF_8));
            }
            XLog.i("icon cache reset (" + removed + " files), signature " + want);
        } catch (Throwable t) {
            XLog.e("icon cache reset failed", t);
        }
    }

    /** Every app icon One UI Home renders goes through LauncherActivityInfoWrapper. */
    private static void hookIconFactory(ClassLoader cl) throws ClassNotFoundException {
        Class<?> wrapper = Class.forName(ACTIVITY_INFO_WRAPPER, false, cl);
        // hookAllMethods: hookMethod's return type differs between LSPosed builds.
        Set<?> hooks = XposedBridge.hookAllMethods(wrapper, "semGetBadgedIconForIconTray",
                SafeHook.after("semGetBadgedIconForIconTray", p -> {
                Object result = p.getResult();
                if (!(result instanceof Drawable) || !(p.args[0] instanceof LauncherActivityInfo)) {
                    return;
                }
                LauncherActivityInfo info = (LauncherActivityInfo) p.args[0];
                int density = p.args.length > 1 && p.args[1] instanceof Integer
                        ? (Integer) p.args[1] : 0;
                Drawable glass = glassIcon(info, density, (Drawable) result);
                if (glass != null) p.setResult(glass);
            }));
        XLog.i("icon factory hooks: " + (hooks == null ? 0 : hooks.size()));
    }

    private static Drawable glassIcon(LauncherActivityInfo info, int density, Drawable themed) {
        Drawable raw;
        try {
            raw = info.getIcon(density);
        } catch (Throwable t) {
            return null;
        }
        GlassIconDrawable glass;
        if (raw instanceof AdaptiveIconDrawable) {
            glass = new GlassIconDrawable(themed, (AdaptiveIconDrawable) raw);
        } else if (raw != null && Config.legacyGlass) {
            // Legacy bitmap icons have no separable foreground: shown see-through instead.
            glass = new GlassIconDrawable(themed, raw);
        } else {
            return null;
        }
        try {
            glass.tag = String.valueOf(info.getLabel());
        } catch (Throwable ignored) {
            // The name is only for the log.
        }
        return glass;
    }

    /**
     * The badge in the corner of a Secure Folder or work profile app's icon is put there by
     * the package manager (getUserBadgedIcon and its kin, Samsung's icon tray variants
     * included): with the setting on, those return the icon as it came.
     */
    private static void hookBadges() {
        try {
            Class<?> pm = Class.forName("android.app.ApplicationPackageManager");
            StringBuilder names = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int n = 0;
            for (java.lang.reflect.Method m : pm.getDeclaredMethods()) {
                if (!m.getName().contains("Badged") || !seen.add(m.getName())) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length == 0 || !Drawable.class.isAssignableFrom(types[0])
                        || !Drawable.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                Set<?> h = XposedBridge.hookAllMethods(pm, m.getName(),
                        SafeHook.before(m.getName(), p -> {
                            if (Config.hideBadges && p.args.length > 0
                                    && p.args[0] instanceof Drawable) {
                                p.setResult(p.args[0]);
                            }
                        }));
                names.append(' ').append(m.getName());
                n += h == null ? 0 : h.size();
            }
            XLog.i("badge hooks: " + n + names);
        } catch (Throwable t) {
            XLog.e("badge hooks", t);
        }
    }

    /**
     * One UI dims an icon while it is touched (its "dim effect") and undims it when the touch
     * ends. A touch that turns into a swipe, or a launch, can end without the undim, and the
     * icon stays grey. iOS does not dim icons that way: the effect is turned off.
     */
    private static void hookDimEffect(Class<?> iconView) {
        int n = 0;
        for (Class<?> k = iconView; k != null && k != TextView.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                        || m.getParameterTypes().length != 0
                        || m.getReturnType() != boolean.class) {
                    continue;
                }
                String name = m.getName();
                final boolean result;
                if (name.equals("isSupportDimEffect")) {
                    result = false;
                } else if (name.equals("getDisableDimEffect")) {
                    result = true;
                } else {
                    continue;
                }
                Set<?> h = XposedBridge.hookAllMethods(k, name, SafeHook.before(name, p -> {
                    if (p.args.length == 0) p.setResult(result);
                }));
                n += h == null ? 0 : h.size();
            }
        }
        XLog.i("icon dim effect hooks: " + n);
    }

    /** Search bar backgrounds become gray liquid glass (View#setBackground ends up here). */
    private static void hookSearchBars() {
        XposedBridge.hookAllMethods(View.class, "setBackgroundDrawable",
                SafeHook.before("setBackgroundDrawable", p -> {
                    if (p.args.length == 1 && (p.args[0] == null
                            || p.args[0] instanceof Drawable)) {
                        View v = (View) p.thisObject;
                        p.args[0] = FolderGlass.wrap(v, SearchGlass.wrap(v, (Drawable) p.args[0]));
                    }
                }));
    }

    /** The liquid glass maps the wallpaper the way it is scrolled. */
    private static void hookWallpaperOffsets() {
        XposedBridge.hookAllMethods(WallpaperManager.class, "setWallpaperOffsets",
                SafeHook.after("setWallpaperOffsets", p -> {
                    if (p.args.length == 3 && p.args[1] instanceof Float
                            && p.args[2] instanceof Float) {
                        LiquidGlass.onWallpaperOffsets((Float) p.args[1], (Float) p.args[2]);
                    }
                }));
    }

    /**
     * One UI zooms the wallpaper (1 to 1.3) as an app opens and closes and on the swipe home:
     * WallpaperAnimator#setWallpaperScale(transaction, scale, bounds) puts the scale on the
     * wallpaper's surface, about the bounds' centre, each frame. The glass follows it, or the
     * picture inside it does not line up with the wallpaper round it. destroyWallpaperSurface
     * ends every such animation (done, skipped, timed out): the wallpaper is back at 1.
     */
    private static void hookWallpaperZoom(ClassLoader cl) {
        Class<?> animator;
        try {
            animator = Class.forName("com.honeyspace.transition.anim.WallpaperAnimator", false,
                    cl);
        } catch (Throwable t) {
            XLog.i("no wallpaper zoom to follow: " + t);
            return;
        }
        java.lang.reflect.Field surfaceField = null;
        try {
            surfaceField = animator.getDeclaredField("wallpaperSurface");
            surfaceField.setAccessible(true);
        } catch (Throwable t) {
            XLog.i("no WallpaperAnimator.wallpaperSurface: " + t);
        }
        final java.lang.reflect.Field surface = surfaceField;
        Set<?> scale = XposedBridge.hookAllMethods(animator, "setWallpaperScale",
                SafeHook.before("setWallpaperScale", p -> {
                    if (p.args.length != 3 || !(p.args[1] instanceof Float)
                            || !(p.args[2] instanceof android.graphics.Rect)) {
                        return;
                    }
                    // No surface: One UI scales nothing either.
                    if (surface != null && surface.get(p.thisObject) == null) return;
                    android.graphics.Rect b = (android.graphics.Rect) p.args[2];
                    // Rect#centerX/Y, as One UI's Matrix#setScale pivot.
                    LiquidGlass.onWallpaperZoom((Float) p.args[1], b.centerX(), b.centerY());
                }));
        Set<?> end = XposedBridge.hookAllMethods(animator, "destroyWallpaperSurface",
                SafeHook.after("destroyWallpaperSurface",
                        p -> LiquidGlass.onWallpaperZoom(1f, 0f, 0f)));
        XLog.i("wallpaper zoom hooks: " + (scale == null ? 0 : scale.size()) + "+"
                + (end == null ? 0 : end.size()));
    }

    /**
     * IconViewImpl#setCompoundDrawable(Drawable) is the single place the icon reaches the view
     * on One UI 8.5. Where that method is not there (One UI 8.0), the view still ends up in
     * TextView#setCompoundDrawables or #setCompoundDrawablesRelative with the icon on top, so
     * those are hooked for the exact class instead.
     */
    /**
     * The glass under folder icons on the Home screen: FolderIconViewImpl's own onDraw if it
     * has one, else IconViewImpl's for that class. Before the icon draws itself.
     */
    private static void hookFolderIcon(ClassLoader cl, Class<?> iconView) {
        Class<?> folder = null;
        try {
            folder = Class.forName("com.honeyspace.ui.common.iconview.FolderIconViewImpl", false,
                    cl);
        } catch (ClassNotFoundException ignored) {
        }
        Class<?> target = folder;
        Set<?> hooks = folder != null ? XposedBridge.hookAllMethods(folder, "onDraw",
                SafeHook.before("folder onDraw", p -> drawFolderGlass(p))) : null;
        if (hooks == null || hooks.isEmpty()) {
            target = iconView;
            hooks = XposedBridge.hookAllMethods(iconView, "onDraw",
                    SafeHook.before("icon onDraw", p -> {
                        if (p.thisObject.getClass().getSimpleName().startsWith("FolderIcon")) {
                            drawFolderGlass(p);
                        }
                    }));
        }
        XLog.i("folder icon glass hooks: " + (hooks == null ? 0 : hooks.size()) + " on "
                + (target == null ? "nothing" : target.getName()));
    }

    private static void drawFolderGlass(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
        if (p.args.length == 1 && p.args[0] instanceof android.graphics.Canvas
                && p.thisObject instanceof TextView) {
            IconBlur.drawFolderIconGlass((TextView) p.thisObject,
                    (android.graphics.Canvas) p.args[0]);
        }
    }

    private static void hookIconView(ClassLoader cl) throws ClassNotFoundException {
        Class<?> iconView = Class.forName(ICON_VIEW, false, cl);
        Set<?> hooks = XposedBridge.hookAllMethods(iconView, "setCompoundDrawable",
                SafeHook.before("setCompoundDrawable", p -> {
                    // Exact class only: FolderIconViewImpl has a launcher-managed background.
                    if (p.thisObject.getClass() != iconView || p.args.length != 1) return;
                    if (p.args[0] instanceof Drawable) {
                        p.args[0] = IconBlur.wrap((TextView) p.thisObject, (Drawable) p.args[0]);
                    }
                }));
        int n = hooks == null ? 0 : hooks.size();
        XLog.i("icon view hooks: " + n);
        try {
            hookFolderIcon(cl, iconView);
        } catch (Throwable t) {
            XLog.e("folder icon glass", t);
        }
        try {
            hookDimEffect(iconView);
        } catch (Throwable t) {
            XLog.e("icon dim effect", t);
        }
        if (n > 0) return;

        // What the class has instead, for the log: its own methods taking a Drawable.
        StringBuilder b = new StringBuilder();
        for (Class<?> k = iconView; k != null && k != TextView.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                for (Class<?> t : m.getParameterTypes()) {
                    if (Drawable.class.isAssignableFrom(t)) {
                        b.append(' ').append(k.getSimpleName()).append('#').append(m.getName())
                                .append('/').append(m.getParameterTypes().length);
                        break;
                    }
                }
            }
        }
        XLog.i("no setCompoundDrawable in " + ICON_VIEW + "; methods with a Drawable:" + b);

        int fallback = 0;
        for (String name : new String[]{"setCompoundDrawables", "setCompoundDrawablesRelative"}) {
            Set<?> h = XposedBridge.hookAllMethods(TextView.class, name,
                    SafeHook.before(name, p -> {
                        if (p.thisObject.getClass() != iconView || p.args.length != 4) return;
                        // The icon sits on top of the label; failing that, wherever it is.
                        int at = p.args[1] instanceof Drawable ? 1 : -1;
                        for (int i = 0; at < 0 && i < 4; i++) {
                            if (p.args[i] instanceof Drawable) at = i;
                        }
                        if (at < 0) return;
                        // A wrapper put back is handled by wrap() too (the icon shows again).
                        p.args[at] = IconBlur.wrap((TextView) p.thisObject, (Drawable) p.args[at]);
                    }));
            fallback += h == null ? 0 : h.size();
        }
        XLog.i("icon view fallback hooks on TextView: " + fallback);
    }
}
