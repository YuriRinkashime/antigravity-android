package com.agy.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Controls token-saving runtime behavior.
 *
 * <p>Background sidecars (`--enable_sidecars`, used for background Automations)
 * are disabled by default to prevent silent background LLM token consumption
 * and extra RAM usage. Users can opt in anytime from QuickControlSheet.
 */
public final class TokenSaverPrefs {

    private static final String PREFS_NAME = "agy_token_saver";
    private static final String KEY_SIDECARS_ENABLED = "sidecars_enabled";

    private TokenSaverPrefs() {
    }

    /** Defaults to false (Token-Saver mode: no silent background sidecars). */
    public static boolean isSidecarsEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_SIDECARS_ENABLED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setSidecarsEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_SIDECARS_ENABLED, enabled).apply();
        } catch (Throwable ignored) {
        }
    }
}
