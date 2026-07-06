package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AppErrorStore {
    static final String ACTION_ERRORS_CHANGED =
            BuildConfig.APPLICATION_ID + ".action.ERRORS_CHANGED";

    private static final String PREFS = "app_error_history";
    private static final String KEY_ERRORS = "errors";
    private static final int MAX_ERRORS = 20;

    private AppErrorStore() {
    }

    static void recordSerious(Context context, String source, String message) {
        if (context == null || isIgnorable(message)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray previous = readArray(preferences.getString(KEY_ERRORS, "[]"));
        String cleanMessage = message.trim();
        if (previous.length() > 0) {
            JSONObject latest = previous.optJSONObject(0);
            if (latest != null
                    && cleanMessage.equals(latest.optString("message"))
                    && source.equals(latest.optString("source"))) {
                return;
            }
        }
        JSONArray updated = new JSONArray();
        try {
            updated.put(new JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("source", source)
                    .put("message", cleanMessage));
            for (int index = 0; index < previous.length() && updated.length() < MAX_ERRORS; index++) {
                JSONObject error = previous.optJSONObject(index);
                if (error != null) {
                    updated.put(error);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        preferences.edit().putString(KEY_ERRORS, updated.toString()).apply();
        notifyChanged(appContext);
    }

    static List<Entry> getAll(Context context) {
        JSONArray stored = readArray(context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ERRORS, "[]"));
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < stored.length(); index++) {
            JSONObject item = stored.optJSONObject(index);
            if (item != null) {
                entries.add(new Entry(
                        item.optLong("timestamp", 0L),
                        item.optString("source", "Alertou"),
                        item.optString("message", "")
                ));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    static void clear(Context context) {
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ERRORS)
                .apply();
        notifyChanged(appContext);
    }

    private static boolean isIgnorable(String message) {
        if (message == null || message.trim().isEmpty()) {
            return true;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("chat not found")
                || normalized.contains("chat_not_found");
    }

    private static JSONArray readArray(String value) {
        try {
            return new JSONArray(value == null ? "[]" : value);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void notifyChanged(Context context) {
        context.sendBroadcast(new android.content.Intent(ACTION_ERRORS_CHANGED)
                .setPackage(context.getPackageName()));
    }

    static final class Entry {
        final long timestamp;
        final String source;
        final String message;

        Entry(long timestamp, String source, String message) {
            this.timestamp = timestamp;
            this.source = source;
            this.message = message;
        }
    }
}
