package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local weekly evidence of how often a group produces an approved offer. */
final class GroupQualityRepository {
    private static final String PREFS = "group_quality_preferences";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_APPROVED = "approved";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_HISTORY_REQUESTED = "history_requested";
    private static final long WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_EVENTS = 3000;

    private final SharedPreferences preferences;
    private final Context appContext;

    GroupQualityRepository(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void recordMessage(long chatId, long messageId, long observedAt) {
        record(KEY_MESSAGES, new Event(chatId + ":" + messageId, chatId, observedAt));
    }

    synchronized boolean prepareYesterdayHistory() {
        long now = System.currentTimeMillis();
        long yesterdayStart = now - (now % (24L * 60L * 60L * 1000L)) - 24L * 60L * 60L * 1000L;
        long startedAt = preferences.getLong(KEY_STARTED_AT, now);
        if (startedAt > yesterdayStart) {
            preferences.edit().putLong(KEY_STARTED_AT, yesterdayStart).apply();
        }
        if (preferences.getBoolean(KEY_HISTORY_REQUESTED, false)) {
            return false;
        }
        preferences.edit().putBoolean(KEY_HISTORY_REQUESTED, true).apply();
        return true;
    }

    synchronized void recordApprovedOffer(long chatId, long messageId, long observedAt) {
        if (record(KEY_APPROVED, new Event(chatId + ":" + messageId, chatId, observedAt))) {
            CloudSyncStore.markRankingChanged(appContext);
        }
    }

    synchronized void seedApprovedOffers(List<ObservedOffer> offers, List<TelegramGroup> groups,
                                         Set<String> selectedIds) {
        Map<String, Long> selectedGroupsByTitle = new HashMap<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                selectedGroupsByTitle.put(group.getTitle().trim().toLowerCase(), group.getId());
            }
        }
        for (ObservedOffer offer : offers) {
            Long chatId = selectedGroupsByTitle.get(offer.getSource().trim().toLowerCase());
            if (chatId != null) {
                record(KEY_APPROVED, new Event("offer:" + offer.getId(), chatId,
                        offer.getObservedAt()));
            }
        }
    }

    synchronized Map<Long, Stats> getStats(List<TelegramGroup> groups, Set<String> selectedIds) {
        long now = System.currentTimeMillis();
        long oldest = now - WINDOW_MS;
        Map<Long, Stats> result = new HashMap<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                result.put(group.getId(), new Stats(group.getId()));
            }
        }
        for (Event event : read(KEY_MESSAGES)) {
            Stats stats = result.get(event.chatId);
            if (stats != null && event.observedAt >= oldest) {
                stats.messages++;
            }
        }
        for (Event event : read(KEY_APPROVED)) {
            Stats stats = result.get(event.chatId);
            if (stats != null && event.observedAt >= oldest) {
                stats.approvedOffers++;
            }
        }
        long startedAt = preferences.getLong(KEY_STARTED_AT, now);
        int daysObserved = (int) Math.max(1L, Math.min(7L,
                1L + (now - startedAt) / (24L * 60L * 60L * 1000L)));
        for (Stats stats : result.values()) {
            stats.daysObserved = daysObserved;
        }
        return result;
    }

    private boolean record(String key, Event event) {
        long now = System.currentTimeMillis();
        if (!preferences.contains(KEY_STARTED_AT)) {
            preferences.edit().putLong(KEY_STARTED_AT, now).apply();
        }
        List<Event> events = read(key);
        for (Event item : events) {
            if (item.id.equals(event.id)) return false;
        }
        events.add(event);
        long oldest = now - WINDOW_MS;
        events.removeIf(item -> item.observedAt < oldest);
        if (events.size() > MAX_EVENTS) {
            events = new ArrayList<>(events.subList(events.size() - MAX_EVENTS, events.size()));
        }
        save(key, events);
        return true;
    }

    private List<Event> read(String key) {
        List<Event> events = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(key, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                events.add(new Event(item.getString("id"), item.getLong("chat_id"),
                        item.getLong("observed_at")));
            }
        } catch (Exception ignored) {
        }
        return events;
    }

    private void save(String key, List<Event> events) {
        JSONArray array = new JSONArray();
        try {
            for (Event event : events) {
                array.put(new JSONObject().put("id", event.id).put("chat_id", event.chatId)
                        .put("observed_at", event.observedAt));
            }
            preferences.edit().putString(key, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static final class Stats {
        private final long chatId;
        private int messages;
        private int approvedOffers;
        private int daysObserved;

        Stats(long chatId) { this.chatId = chatId; }
        int getMessages() { return messages; }
        int getApprovedOffers() { return approvedOffers; }
        int getDaysObserved() { return daysObserved; }
        int getPercent() { return messages == 0 ? 0 : Math.round(approvedOffers * 100f / messages); }
        void ensureApprovedOffers(int approvedOffers) {
            this.approvedOffers = Math.max(this.approvedOffers, approvedOffers);
        }
        boolean hasEnoughEvidence() { return daysObserved >= 7 && approvedOffers >= 3; }
        boolean hasLowQuality() { return daysObserved >= 7 && approvedOffers == 0; }
        boolean hasGoodQuality() { return hasEnoughEvidence(); }
        int getQualityOrder() {
            return hasGoodQuality() ? 3 : hasLowQuality() ? 0 : hasEnoughEvidence() ? 2 : 1;
        }
    }

    private static final class Event {
        final String id;
        final long chatId;
        final long observedAt;

        Event(String id, long chatId, long observedAt) {
            this.id = id;
            this.chatId = chatId;
            this.observedAt = observedAt;
        }
    }
}
