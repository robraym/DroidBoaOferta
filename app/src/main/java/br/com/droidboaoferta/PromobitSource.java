package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

final class PromobitSource {
    static final String DEFAULT_URL = "https://www.promobit.com.br/promocoes/recentes/";
    private static final String PREFS = "external_offer_sources";
    private static final String KEY_URL = "promobit_url";
    private static final String KEY_LAST_SUCCESS = "promobit_last_success";
    private static final String KEY_LAST_FAILURE = "promobit_last_failure";
    private static final String KEY_CHECK_INTERVAL_SECONDS = "promobit_check_interval_seconds";
    static final int DEFAULT_CHECK_INTERVAL_SECONDS = 30;

    private PromobitSource() {
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
            throw new IllegalArgumentException("Unsupported Promobit URL");
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
                    || (!"www.promobit.com.br".equals(host) && !"promobit.com.br".equals(host))
                    || !"/promocoes/recentes".equals(path)) {
                return null;
            }
            return DEFAULT_URL;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static void markSuccessfulCheck(Context context) {
        preferences(context).edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .apply();
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

    static int getCheckIntervalSeconds(Context context) {
        int saved = preferences(context).getInt(
                KEY_CHECK_INTERVAL_SECONDS,
                DEFAULT_CHECK_INTERVAL_SECONDS
        );
        return isSupportedCheckInterval(saved) ? saved : DEFAULT_CHECK_INTERVAL_SECONDS;
    }

    static void saveCheckIntervalSeconds(Context context, int seconds) {
        if (!isSupportedCheckInterval(seconds)) {
            throw new IllegalArgumentException("Unsupported Promobit check interval");
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
