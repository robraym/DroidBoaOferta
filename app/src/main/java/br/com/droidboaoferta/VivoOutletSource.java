package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

final class VivoOutletSource {
    static final String DEFAULT_URL = "https://store.vivo.com.br/outlet-geral-30off/c";
    private static final String PREFS = "external_offer_sources";
    private static final String KEY_URL = "vivo_outlet_url";
    private static final String KEY_LAST_SUCCESS = "vivo_outlet_last_success";
    private static final String KEY_LAST_FAILURE = "vivo_outlet_last_failure";

    private VivoOutletSource() {
    }

    static String getUrl(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, "");
    }

    static boolean isConfigured(Context context) {
        return normalizeUrl(getUrl(context)) != null;
    }

    static void save(Context context, String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized == null) {
            throw new IllegalArgumentException("Unsupported Vivo outlet URL");
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, normalized)
                .apply();
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
            String[] parts = path.split("/");
            if (!"https".equals(scheme)
                    || !"store.vivo.com.br".equals(host)
                    || parts.length != 3
                    || !"c".equalsIgnoreCase(parts[2])
                    || !parts[1].matches("[A-Za-z0-9-]+")) {
                return null;
            }
            return "https://store.vivo.com.br/" + parts[1].toLowerCase(Locale.ROOT) + "/c";
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String getCategoryCode(String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized == null) {
            return null;
        }
        return normalized.substring("https://store.vivo.com.br/".length(), normalized.length() - 2);
    }

    static void markSuccessfulCheck(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .apply();
    }

    static void markFailedCheck(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_FAILURE, System.currentTimeMillis())
                .apply();
    }

    static boolean hasLastCheckFailed(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return preferences.getLong(KEY_LAST_FAILURE, 0L)
                > preferences.getLong(KEY_LAST_SUCCESS, 0L);
    }

    static boolean hasSuccessfulCheck(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SUCCESS, 0L) > 0L;
    }

    static long getLastSuccessfulCheckAt(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SUCCESS, 0L);
    }
}
