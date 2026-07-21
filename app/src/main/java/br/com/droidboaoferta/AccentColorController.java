package br.com.droidboaoferta;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

final class AccentColorController {
    static final String MODE_BLUE = "blue";
    static final String MODE_GREEN = "green";
    static final String MODE_YELLOW = "yellow";
    static final String MODE_PURPLE = "purple";
    static final String MODE_ORANGE = "orange";
    static final String MODE_PINK = "pink";
    static final String MODE_TEAL = "teal";
    static final String MODE_RED = "red";
    static final String MODE_MATRIX = "matrix";
    static final String MODE_WHITE = "white";
    private static final String LEGACY_MODE_ELECTRIC = "electric";
    static final String MODE_INDIGO = "indigo";
    static final String MODE_MAGENTA = "magenta";

    private static final String PREFS = "app_preferences";
    private static final String ACCENT_COLOR = "accent_color";

    private AccentColorController() {
    }

    static String getSavedMode(Context context) {
        return normalize(prefs(context).getString(ACCENT_COLOR, MODE_BLUE));
    }

    static void saveMode(Context context, String mode) {
        String normalizedMode = normalize(mode);
        if (normalizedMode.equals(getSavedMode(context))) {
            return;
        }
        long changedAt = System.currentTimeMillis();
        prefs(context).edit().putString(ACCENT_COLOR, normalizedMode).apply();
        CloudSyncStore.rememberThemeChanged(context, changedAt);
        CloudSyncStore.markLocalChanged(context);
    }

    static void apply(Activity activity) {
        activity.getTheme().applyStyle(getStyleResource(getSavedMode(activity)), true);
    }

    static int getSummaryResource(String mode) {
        switch (normalize(mode)) {
            case MODE_GREEN:
                return R.string.accent_color_green;
            case MODE_YELLOW:
                return R.string.accent_color_yellow;
            case MODE_PURPLE:
                return R.string.accent_color_purple;
            case MODE_ORANGE:
                return R.string.accent_color_orange;
            case MODE_PINK:
                return R.string.accent_color_pink;
            case MODE_TEAL:
                return R.string.accent_color_teal;
            case MODE_RED:
                return R.string.accent_color_red;
            case MODE_MATRIX:
                return R.string.accent_color_matrix;
            case MODE_WHITE:
                return R.string.accent_color_white;
            case MODE_INDIGO:
                return R.string.accent_color_indigo;
            case MODE_MAGENTA:
                return R.string.accent_color_magenta;
            case MODE_BLUE:
            default:
                return R.string.accent_color_blue;
        }
    }

    static int getPrimaryColor(Context context, String mode) {
        return context.getColor(getPrimaryColorResource(mode));
    }

    static int getSoftColor(Context context, String mode) {
        return context.getColor(getSoftColorResource(mode));
    }

    static String normalize(String mode) {
        if (LEGACY_MODE_ELECTRIC.equals(mode)) {
            return MODE_WHITE;
        }
        if (MODE_GREEN.equals(mode)
                || MODE_YELLOW.equals(mode)
                || MODE_PURPLE.equals(mode)
                || MODE_ORANGE.equals(mode)
                || MODE_PINK.equals(mode)
                || MODE_TEAL.equals(mode)
                || MODE_RED.equals(mode)
                || MODE_MATRIX.equals(mode)
                || MODE_WHITE.equals(mode)
                || MODE_INDIGO.equals(mode)
                || MODE_MAGENTA.equals(mode)) {
            return mode;
        }
        return MODE_BLUE;
    }

    private static int getStyleResource(String mode) {
        switch (normalize(mode)) {
            case MODE_GREEN:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Green;
            case MODE_YELLOW:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Yellow;
            case MODE_PURPLE:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Purple;
            case MODE_ORANGE:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Orange;
            case MODE_PINK:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Pink;
            case MODE_TEAL:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Teal;
            case MODE_RED:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Red;
            case MODE_MATRIX:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Matrix;
            case MODE_WHITE:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_White;
            case MODE_INDIGO:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Indigo;
            case MODE_MAGENTA:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Magenta;
            case MODE_BLUE:
            default:
                return R.style.ThemeOverlay_DroidBoaOferta_Accent_Blue;
        }
    }

    private static int getPrimaryColorResource(String mode) {
        switch (normalize(mode)) {
            case MODE_GREEN:
                return R.color.action_green;
            case MODE_YELLOW:
                return R.color.action_yellow;
            case MODE_PURPLE:
                return R.color.action_purple;
            case MODE_ORANGE:
                return R.color.action_orange;
            case MODE_PINK:
                return R.color.action_pink;
            case MODE_TEAL:
                return R.color.action_teal;
            case MODE_RED:
                return R.color.action_red;
            case MODE_MATRIX:
                return R.color.action_matrix;
            case MODE_WHITE:
                return R.color.action_white;
            case MODE_INDIGO:
                return R.color.action_indigo;
            case MODE_MAGENTA:
                return R.color.action_magenta;
            case MODE_BLUE:
            default:
                return R.color.action_blue;
        }
    }

    private static int getSoftColorResource(String mode) {
        switch (normalize(mode)) {
            case MODE_GREEN:
                return R.color.action_green_soft;
            case MODE_YELLOW:
                return R.color.action_yellow_soft;
            case MODE_PURPLE:
                return R.color.action_purple_soft;
            case MODE_ORANGE:
                return R.color.action_orange_soft;
            case MODE_PINK:
                return R.color.action_pink_soft;
            case MODE_TEAL:
                return R.color.action_teal_soft;
            case MODE_RED:
                return R.color.action_red_soft;
            case MODE_MATRIX:
                return R.color.action_matrix_soft;
            case MODE_WHITE:
                return R.color.action_white_soft;
            case MODE_INDIGO:
                return R.color.action_indigo_soft;
            case MODE_MAGENTA:
                return R.color.action_magenta_soft;
            case MODE_BLUE:
            default:
                return R.color.action_blue_soft;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
