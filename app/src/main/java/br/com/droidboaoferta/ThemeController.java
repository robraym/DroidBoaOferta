package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;

final class ThemeController {
    static final String MODE_LIGHT = "light";
    static final String MODE_DARK = "dark";

    private static final String PREFS = "app_preferences";
    private static final String THEME_MODE = "theme_mode";

    private ThemeController() {
    }

    static String getSavedMode(Context context) {
        return prefs(context).getString(THEME_MODE, MODE_DARK);
    }

    static void saveMode(Context context, String mode) {
        long changedAt = System.currentTimeMillis();
        prefs(context).edit()
                .putString(THEME_MODE, mode)
                .apply();
        CloudSyncStore.rememberThemeChanged(context, changedAt);
        CloudSyncStore.markLocalChanged(context);
        applyMode(mode);
    }

    static void applySavedTheme(Context context) {
        applyMode(getSavedMode(context));
    }

    static int getSummaryResource(String mode) {
        switch (mode) {
            case MODE_LIGHT:
                return R.string.theme_light;
            case MODE_DARK:
            default:
                return R.string.theme_dark;
        }
    }

    static int getDialogIndex(String mode) {
        return MODE_LIGHT.equals(mode) ? 0 : 1;
    }

    private static void applyMode(String mode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> applyMode(mode));
            return;
        }
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
