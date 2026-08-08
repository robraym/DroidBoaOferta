package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/** Stores a user decision that a promotion had ended, without deleting its history. */
final class GroupPromotionExpiryRepository {
    private static final String PREFS = "group_promotion_expiry";
    private static final String KEY_RULES = "rules";
    private final SharedPreferences preferences;

    GroupPromotionExpiryRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void markExpired(String product, long roundStartedAt, long cutoffAt) {
        Rule rule = read(product, roundStartedAt);
        rule.cutoffAt = cutoffAt;
        rule.resumedAt = 0L;
        write(product, roundStartedAt, rule);
    }

    synchronized void resume(String product, long roundStartedAt, long resumedAt) {
        Rule rule = read(product, roundStartedAt);
        if (rule.cutoffAt == 0L) return;
        rule.resumedAt = resumedAt;
        write(product, roundStartedAt, rule);
    }

    synchronized boolean resumeForOffer(String product, long observedAt, long resumedAt) {
        try {
            JSONObject all = new JSONObject(preferences.getString(KEY_RULES, "{}"));
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String itemKey = keys.next();
                int separator = itemKey.lastIndexOf('@');
                if (separator < 1 || !product.equals(itemKey.substring(0, separator))) continue;
                long startedAt = Long.parseLong(itemKey.substring(separator + 1));
                if (observedAt < startedAt || observedAt - startedAt > 12L * 60L * 60L * 1000L) continue;
                JSONObject item = all.optJSONObject(itemKey);
                if (item == null || item.optLong("cutoff_at") == 0L) continue;
                all.remove(itemKey);
                preferences.edit().putString(KEY_RULES, all.toString()).apply();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    synchronized boolean isExpiredAt(String product, long roundStartedAt, long observedAt) {
        Rule rule = read(product, roundStartedAt);
        return rule.cutoffAt > 0L && observedAt > rule.cutoffAt
                && (rule.resumedAt == 0L || observedAt < rule.resumedAt);
    }

    synchronized boolean isClosed(String product, long roundStartedAt) {
        Rule rule = read(product, roundStartedAt);
        return rule.cutoffAt > 0L && rule.resumedAt == 0L;
    }

    synchronized boolean isExpiredForOffer(String product, long observedAt) {
        try {
            JSONObject all = new JSONObject(preferences.getString(KEY_RULES, "{}"));
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String itemKey = keys.next();
                int separator = itemKey.lastIndexOf('@');
                if (separator < 1 || !product.equals(itemKey.substring(0, separator))) continue;
                long startedAt = Long.parseLong(itemKey.substring(separator + 1));
                if (observedAt < startedAt || observedAt - startedAt > 12L * 60L * 60L * 1000L) continue;
                JSONObject item = all.optJSONObject(itemKey);
                if (item == null) continue;
                long cutoffAt = item.optLong("cutoff_at");
                long resumedAt = item.optLong("resumed_at");
                if (cutoffAt > 0L && observedAt > cutoffAt
                        && (resumedAt == 0L || observedAt < resumedAt)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private Rule read(String product, long roundStartedAt) {
        try {
            JSONObject all = new JSONObject(preferences.getString(KEY_RULES, "{}"));
            JSONObject item = all.optJSONObject(key(product, roundStartedAt));
            return item == null ? new Rule() : new Rule(item.optLong("cutoff_at"), item.optLong("resumed_at"));
        } catch (Exception ignored) {
            return new Rule();
        }
    }

    private void write(String product, long roundStartedAt, Rule rule) {
        try {
            JSONObject all = new JSONObject(preferences.getString(KEY_RULES, "{}"));
            all.put(key(product, roundStartedAt), new JSONObject()
                    .put("cutoff_at", rule.cutoffAt).put("resumed_at", rule.resumedAt));
            preferences.edit().putString(KEY_RULES, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private String key(String product, long roundStartedAt) {
        return product + "@" + roundStartedAt;
    }

    private static final class Rule {
        long cutoffAt;
        long resumedAt;
        Rule() { }
        Rule(long cutoffAt, long resumedAt) { this.cutoffAt = cutoffAt; this.resumedAt = resumedAt; }
    }
}
