package com.monotonnoe.oneglass;

import com.monotonnoe.oneglass.glass.Log;
import com.monotonnoe.oneglass.hook.LauncherHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point (assets/xposed_init). */
public final class Entry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Config.LAUNCHER.equals(lp.packageName) || !lp.isFirstApplication) {
            return;
        }
        try {
            LauncherHooks.install(lp.classLoader);
        } catch (Throwable t) {
            Log.e("could not hook One UI Home", t);
        }
    }
}
