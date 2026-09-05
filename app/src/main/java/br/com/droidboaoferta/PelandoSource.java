package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

final class PelandoSource {
    static final String DEFAULT_URL = "https://www.pelando.com.br/recentes";
    private static final String PREFS = "external_offer_sources";
    private static final String KEY_URL = "pelando_url";
    private static final String KEY_LAST_SUCCESS = "pelando_last_success";
    private static final String KEY_LAST_FAILURE = "pelando_last_failure";
    private static final String KEY_CHECK_INTERVAL_SECONDS = "pelando_check_interval_seconds";
    private static final String KEY_LAST_MODIFIED = "pelando_last_modified";
    static final int DEFAULT_CHECK_INTERVAL_SECONDS = 30;

    private PelandoSource() {
    }

    static String getUrl(Context context) {
        return preferences(context).getString(KEY_URL, "");
    }

    static boolean isConfigured(Context context) {
        return normalizeUrl(getUrl(context)) != null;
    }

    static void save(Context context, String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized == null) {
            throw new IllegalArgumentException("Unsupported Pelando URL");
        }
        preferences(context).edit().putString(KEY_URL, normalized).apply();
    }

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (!"https".equals(scheme)
                    || (!"www.pelando.com.br".equals(host) && !"pelando.com.br".equals(host))
                    || !"/recentes".equals(path)) {
                return null;
            }
            return DEFAULT_URL;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static void markSuccessfulCheck(Context context, String lastModified) {
        SharedPreferences.Editor editor = preferences(context).edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis());
        if (lastModified != null && !lastModified.trim().isEmpty()) {
            editor.putString(KEY_LAST_MODIFIED, lastModified.trim());
        }
        editor.apply();
    }

    static void markFailedCheck(Context context) {
        preferences(context).edit().putLong(KEY_LAST_FAILURE, System.currentTimeMillis()).apply();
    }

    static boolean hasLastCheckFailed(Context context) {
        SharedPreferences preferences = preferences(context);
        return preferences.getLong(KEY_LAST_FAILURE, 0L)
                > preferences.getLong(KEY_LAST_SUCCESS, 0L);
    }

    static boolean hasSuccessfulCheck(Context context) {
        return preferences(context).getLong(KEY_LAST_SUCCESS, 0L) > 0L;
    }

    static long getLastSuccessfulCheckAt(Context context) {
        return preferences(context).getLong(KEY_LAST_SUCCESS, 0L);
    }

    static String getLastModified(Context context) {
        return preferences(context).getString(KEY_LAST_MODIFIED, "");
    }

    static int getCheckIntervalSeconds(Context context) {
        int saved = preferences(context).getInt(
                KEY_CHECK_INTERVAL_SECONDS,
                DEFAULT_CHECK_INTERVAL_SECONDS
        );
        return isSupportedCheckInterval(saved) ? saved : DEFAULT_CHECK_INTERVAL_SECONDS;
    }

    static void saveCheckIntervalSeconds(Context context, int seconds) {
        if (!isSupportedCheckInterval(seconds)) {
            throw new IllegalArgumentException("Unsupported Pelando check interval");
        }
        preferences(context).edit().putInt(KEY_CHECK_INTERVAL_SECONDS, seconds).apply();
    }

    private static boolean isSupportedCheckInterval(int seconds) {
        return seconds == 30 || seconds == 60 || seconds == 120 || seconds == 300;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
