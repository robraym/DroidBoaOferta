package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores completed weekly group rankings locally, without changing the live ranking. */
final class GroupWeeklyHistoryRepository {
    private static final String PREFS = "group_weekly_history";
    private static final String KEY_WEEKS = "weeks";
    private static final String KEY_WEEK_STARTED_AT = "week_started_at";
    private static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_WEEKS = 24;

    private final SharedPreferences preferences;
    private final Context appContext;

    GroupWeeklyHistoryRepository(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void captureCompletedWeekIfDue(List<GroupSpeedRepository.Ranking> ranking) {
        long now = System.currentTimeMillis();
        long startedAt = preferences.getLong(KEY_WEEK_STARTED_AT, now);
        if (!preferences.contains(KEY_WEEK_STARTED_AT)) {
            preferences.edit().putLong(KEY_WEEK_STARTED_AT, now).apply();
            return;
        }
        if (now - startedAt < WEEK_MS || ranking.isEmpty()) {
            return;
        }
        JSONArray weeks = readWeeks();
        JSONArray standings = new JSONArray();
        try {
            int position = 1;
            for (GroupSpeedRepository.Ranking item : ranking) {
                if (item.getPoints() > 0) {
                    standings.put(new JSONObject().put("chat_id", item.getChatId())
                            .put("position", position).put("points", item.getPoints()));
                    position++;
                }
            }
            if (standings.length() > 0) {
                weeks.put(new JSONObject().put("started_at", startedAt).put("standings", standings));
            }
        } catch (Exception ignored) {
            return;
        }
        while (weeks.length() > MAX_WEEKS) {
            JSONArray trimmed = new JSONArray();
            for (int index = weeks.length() - MAX_WEEKS; index < weeks.length(); index++) {
                trimmed.put(weeks.opt(index));
            }
            weeks = trimmed;
        }
        preferences.edit().putString(KEY_WEEKS, weeks.toString())
                .putLong(KEY_WEEK_STARTED_AT, now).apply();
    }

    synchronized Map<Long, Awards> getAwards() {
        Map<Long, Awards> awards = new HashMap<>();
        JSONArray weeks = readWeeks();
        for (int index = 0; index < weeks.length(); index++) {
            JSONArray standings = weeks.optJSONObject(index) == null ? null
                    : weeks.optJSONObject(index).optJSONArray("standings");
            if (standings == null) continue;
            for (int entry = 0; entry < standings.length(); entry++) {
                JSONObject item = standings.optJSONObject(entry);
                if (item == null) continue;
                Awards award = awards.computeIfAbsent(item.optLong("chat_id"), ignored -> new Awards());
                int position = item.optInt("position");
                if (position == 1) award.championships++;
                if (position > 0 && position <= 3) award.topThree++;
            }
        }
        return awards;
    }

    private JSONArray readWeeks() {
        try { return new JSONArray(preferences.getString(KEY_WEEKS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static final class Awards {
        private int championships;
        private int topThree;
        int getChampionships() { return championships; }
        int getTopThree() { return topThree; }
    }
}
