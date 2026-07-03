package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

final class ThemeController {
    static final String MODE_SYSTEM = "system";
    static final String MODE_LIGHT = "light";
    static final String MODE_DARK = "dark";

    private static final String PREFS = "app_preferences";
    private static final String THEME_MODE = "theme_mode";

    private ThemeController() {
    }

    static String getSavedMode(Context context) {
        return prefs(context).getString(THEME_MODE, MODE_SYSTEM);
    }

    static void saveMode(Context context, String mode) {
        prefs(context).edit()
                .putString(THEME_MODE, mode)
                .apply();
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
                return R.string.theme_dark;
            case MODE_SYSTEM:
            default:
                return R.string.theme_system;
        }
    }

    static int getDialogIndex(String mode) {
        switch (mode) {
            case MODE_LIGHT:
                return 1;
            case MODE_DARK:
                return 2;
            case MODE_SYSTEM:
            default:
                return 0;
        }
    }

    static String modeFromDialogIndex(int index) {
        if (index == 1) {
            return MODE_LIGHT;
        }
        if (index == 2) {
            return MODE_DARK;
        }
        return MODE_SYSTEM;
    }

    private static void applyMode(String mode) {
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case MODE_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
