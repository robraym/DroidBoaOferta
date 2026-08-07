package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps a local, explainable ranking of groups that published the same offer first. */
final class GroupSpeedRepository {
    private static final String PREFS = "group_speed_preferences";
    private static final String KEY_EVENTS = "promotion_events";
    private static final long WINDOW_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final long RACE_WINDOW_MS = 3L * 60L * 60L * 1000L;
    private static final int MAX_EVENTS = 800;

    private final SharedPreferences preferences;

    GroupSpeedRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void record(long chatId, String title, Interest interest, double price,
                             long observedAt, String link) {
        record(chatId, title, interest.getTerm(), interest.getId(), price, observedAt, link);
    }

    synchronized void seedFromOffers(List<ObservedOffer> offers, List<TelegramGroup> groups,
                                     Set<String> selectedIds) {
        Map<String, TelegramGroup> selectedGroupsByTitle = new HashMap<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                selectedGroupsByTitle.put(normalizeTitle(group.getTitle()), group);
            }
        }
        for (ObservedOffer offer : offers) {
            TelegramGroup group = selectedGroupsByTitle.get(normalizeTitle(offer.getSource()));
            if (group != null) {
                record(group.getId(), group.getTitle(), offer.getInterest(), offer.getInterestId(),
                        offer.getPrice(), offer.getObservedAt(), offer.getLink());
            }
        }
    }

    private void record(long chatId, String title, String interest, long interestId, double price,
                        long observedAt, String link) {
        List<Event> events = readEvents();
        String eventId = chatId + ":" + interestId + ":" + observedAt + ":" + link;
        events.removeIf(event -> event.id.equals(eventId));
        events.add(new Event(eventId, chatId, title, signature(interest, price, link), observedAt));
        long oldest = System.currentTimeMillis() - WINDOW_MS;
        events.removeIf(event -> event.observedAt < oldest);
        events.sort(Comparator.comparingLong((Event event) -> event.observedAt).reversed());
        if (events.size() > MAX_EVENTS) {
            events = new ArrayList<>(events.subList(0, MAX_EVENTS));
        }
        saveEvents(events);
    }

    synchronized List<Ranking> getRanking(List<TelegramGroup> groups, Set<String> selectedIds) {
        Map<Long, Ranking> ranking = new HashMap<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                ranking.put(group.getId(), new Ranking(group.getId(), group.getTitle()));
            }
        }
        Map<String, List<Event>> races = new HashMap<>();
        long oldest = System.currentTimeMillis() - WINDOW_MS;
        for (Event event : readEvents()) {
            if (event.observedAt >= oldest && ranking.containsKey(event.chatId)) {
                races.computeIfAbsent(event.signature, ignored -> new ArrayList<>()).add(event);
            }
        }
        for (List<Event> events : races.values()) {
            events.sort(Comparator.comparingLong(event -> event.observedAt));
            List<Event> race = new ArrayList<>();
            long previousObservedAt = 0L;
            for (Event event : events) {
                if (!race.isEmpty() && event.observedAt - previousObservedAt > RACE_WINDOW_MS) {
                    awardRace(race, ranking);
                    race.clear();
                }
                race.add(event);
                previousObservedAt = event.observedAt;
            }
            awardRace(race, ranking);
        }
        List<Ranking> result = new ArrayList<>(ranking.values());
        result.sort(Comparator.comparingInt(Ranking::getPoints).reversed()
                .thenComparing(Comparator.comparingInt(Ranking::getFirstPlaces).reversed())
                .thenComparing(Ranking::getTitle, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void awardRace(List<Event> race, Map<Long, Ranking> ranking) {
        if (race.isEmpty()) {
            return;
        }
        race.sort(Comparator.comparingLong(event -> event.observedAt));
        Set<Long> seenGroups = new HashSet<>();
        List<Event> arrivals = new ArrayList<>();
        for (Event event : race) {
            if (seenGroups.add(event.chatId)) {
                arrivals.add(event);
            }
        }
        if (arrivals.size() < 2) {
            return;
        }
        for (int index = 0; index < arrivals.size(); index++) {
            Ranking item = ranking.get(arrivals.get(index).chatId);
            item.participations++;
            item.points += index == 0 ? 10 : index == 1 ? 6 : index == 2 ? 3 : 1;
            if (index == 0) {
                item.firstPlaces++;
            }
        }
    }

    private String signature(String interest, double price, String link) {
        String product = interest == null ? "" : OfferTextParser.normalize(interest);
        return product + "|" + String.format(Locale.ROOT, "%.2f", price);
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    private List<Event> readEvents() {
        List<Event> events = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_EVENTS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                events.add(new Event(item.getString("id"), item.getLong("chat_id"),
                        item.optString("title"), item.getString("signature"), item.getLong("observed_at")));
            }
        } catch (Exception ignored) {
        }
        return events;
    }

    private void saveEvents(List<Event> events) {
        JSONArray array = new JSONArray();
        try {
            for (Event event : events) {
                array.put(new JSONObject().put("id", event.id).put("chat_id", event.chatId)
                        .put("title", event.title).put("signature", event.signature)
                        .put("observed_at", event.observedAt));
            }
        } catch (Exception ignored) {
            return;
        }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply();
    }

    static final class Ranking {
        private final long chatId;
        private final String title;
        private int points;
        private int firstPlaces;
        private int participations;

        Ranking(long chatId, String title) { this.chatId = chatId; this.title = title; }
        long getChatId() { return chatId; }
        String getTitle() { return title; }
        int getPoints() { return points; }
        int getFirstPlaces() { return firstPlaces; }
        int getParticipations() { return participations; }
    }

    private static final class Event {
        final String id; final long chatId; final String title; final String signature; final long observedAt;
        Event(String id, long chatId, String title, String signature, long observedAt) {
            this.id = id; this.chatId = chatId; this.title = title; this.signature = signature; this.observedAt = observedAt;
        }
    }
}

