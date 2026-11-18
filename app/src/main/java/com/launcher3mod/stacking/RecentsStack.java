package com.launcher3mod.stacking;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Point d'entree Vector/LSPosed.
 * Vise le Launcher3 de la ROM (Pixel Launcher / NexusLauncherRelease) et AOSP Launcher3.
 * L'empilement des recents est applique par Stacker.
 */
public class RecentsStack implements IXposedHookLoadPackage {

    public static final String TAG = "Launcher3Mod";

    // Paquets Launcher3 rencontres (Pixel Launcher = com.google.android.apps.nexuslauncher).
    private static final String PIXEL = "com.google.android.apps.nexuslauncher";
    private static final String AOSP = "com.android.launcher3";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!PIXEL.equals(pkg) && !AOSP.equals(pkg)) return;

        XposedBridge.log(TAG + ": cible " + pkg);
        try {
            Stacker stacker = new Stacker(lpparam.classLoader);
            stacker.install();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": erreur initialisation " + android.util.Log.getStackTraceString(t));
        }
    }
}