package com.agy.theme;

import android.content.Context;

/**
 * Dynamic launcher icon switcher.
 *
 * NOTE: Dynamic switching of <activity-alias> launcher components at runtime via
 * PackageManager.setComponentEnabledSetting() is intentionally disabled.
 * On Android 12+ (particularly Transsion HiOS, Xiaomi MIUI, ColorOS), mutating
 * launcher component state causes ActivityManagerService to terminate the
 * application process and sever the language server parent pipe when backgrounded.
 */
public final class LauncherIconSwitcher {

    private LauncherIconSwitcher() {
    }

    public static synchronized void scheduleSwitch(boolean dark) {
        // Disabled to prevent OS process terminations on backgrounding.
    }

    public static synchronized void apply(Context context, boolean dark) {
        // Disabled to prevent OS process terminations on backgrounding.
    }

    public static synchronized void onAppBackgrounded(Context context) {
        // Disabled to prevent OS process terminations on backgrounding.
    }
}
