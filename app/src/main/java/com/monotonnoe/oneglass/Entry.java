package com.monotonnoe.oneglass;

import com.monotonnoe.oneglass.glass.Log;
import com.monotonnoe.oneglass.hook.LauncherHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point (assets/xposed_init).
 *  1. the liquid glass engine (from BlurIcons): Home screen icons, folders, widgets, search pill,
 *     lock screen clock, notifications, buttons, navigation handle, fingerprint icon;
 *  2. OneGlass' additions in One UI Home: the iOS 27 dock platter and the optional iOS grid.
 */
public final class Entry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        try {
            new com.monotonnoe.oneglass.engine.Entry().handleLoadPackage(lp);
        } catch (Throwable t) {
            Log.e("glass engine failed in " + lp.packageName, t);
        }
        if (Config.LAUNCHER.equals(lp.packageName) && Config.LAUNCHER.equals(lp.processName)
                && lp.isFirstApplication) {
            try {
                LauncherHooks.install(lp.classLoader);
            } catch (Throwable t) {
                Log.e("could not hook One UI Home", t);
            }
        }
    }
}
