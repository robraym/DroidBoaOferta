package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps the explainable ranking events that are merged across connected devices. */
final class GroupSpeedRepository {
    private static final String PREFS = "group_speed_preferences";
    private static final String KEY_EVENTS = "promotion_events";
    private static final String KEY_STARTED_AT = "started_at";
    private static final long WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long RACE_WINDOW_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_EVENTS = 800;

    private final SharedPreferences preferences;
    private final GroupPromotionExpiryRepository expiryRepository;
    private final Context appContext;

    GroupSpeedRepository(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        expiryRepository = new GroupPromotionExpiryRepository(appContext);
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

    synchronized void clearForInterest(String interest) {
        String signature = signature(interest, 0.0d, "");
        List<Event> events = readEvents();
        List<Event> removed = new ArrayList<>();
        for (Event event : events) {
            if (signature.equals(event.signature)) {
                removed.add(event);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        events.removeAll(removed);
        saveEvents(events);
        for (Event event : removed) {
            CloudSyncStore.markRankingSpeedRemoved(appContext, event.chatId, event.signature,
                    event.observedAt);
        }
    }

    private void record(long chatId, String title, String interest, long interestId, double price,
                        long observedAt, String link) {
        long startedAt = preferences.getLong(KEY_STARTED_AT, 0L);
        if (startedAt > 0L && observedAt < startedAt) {
            return;
        }
        List<Event> events = readEvents();
        String product = signature(interest, price, link);
        String eventId = chatId + ":" + product + ":" + observedAt;
        for (Event event : events) {
            if (event.id.equals(eventId)) return;
        }
        events.add(new Event(eventId, chatId, title, product, observedAt));
        long oldest = System.currentTimeMillis() - WINDOW_MS;
        events.removeIf(event -> event.observedAt < oldest);
        events.sort(Comparator.comparingLong((Event event) -> event.observedAt).reversed());
        if (events.size() > MAX_EVENTS) {
            events = new ArrayList<>(events.subList(0, MAX_EVENTS));
        }
        saveEvents(events);
        CloudSyncStore.markRankingSpeedChanged(appContext, chatId, product, observedAt);
    }

    synchronized List<Ranking> getRanking(List<TelegramGroup> groups, Set<String> selectedIds) {
        long now = System.currentTimeMillis();
        return getRanking(groups, selectedIds, now - WINDOW_MS, now);
    }

    synchronized List<Ranking> getRanking(List<TelegramGroup> groups, Set<String> selectedIds,
                                          long windowStartedAt, long windowEndedAt) {
        Map<Long, Ranking> ranking = new HashMap<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                ranking.put(group.getId(), new Ranking(group.getId(), group.getTitle()));
            }
        }
        Map<String, List<Event>> races = new HashMap<>();
        for (Event event : readEvents()) {
            if (event.observedAt >= windowStartedAt && event.observedAt < windowEndedAt
                    && ranking.containsKey(event.chatId)) {
                races.computeIfAbsent(event.signature, ignored -> new ArrayList<>()).add(event);
            }
        }
        for (List<Event> events : races.values()) {
            events.sort(Comparator.comparingLong(event -> event.observedAt));
            List<Event> race = new ArrayList<>();
            long raceStartedAt = 0L;
            for (Event event : events) {
                if (!race.isEmpty() && event.observedAt - raceStartedAt > RACE_WINDOW_MS) {
                    awardRace(race, ranking);
                    race.clear();
                }
                if (race.isEmpty()) {
                    raceStartedAt = event.observedAt;
                }
                race.add(event);
            }
            awardRace(race, ranking);
        }
        List<Ranking> result = new ArrayList<>(ranking.values());
        result.sort(Comparator.comparingInt(Ranking::getPoints).reversed()
                .thenComparing(Comparator.comparingInt(Ranking::getFirstPlaces).reversed())
                .thenComparing(Ranking::getTitle, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    synchronized boolean isOfferExpired(ObservedOffer offer) {
        return expiryRepository.isExpiredForOffer(signature(offer.getInterest(), offer.getPrice(), offer.getLink()),
                offer.getObservedAt());
    }

    synchronized long getRoundStartedAt(ObservedOffer offer) {
        String product = signature(offer.getInterest(), offer.getPrice(), offer.getLink());
        List<Event> matching = new ArrayList<>();
        for (Event event : readEvents()) if (product.equals(event.signature)) matching.add(event);
        matching.sort(Comparator.comparingLong(event -> event.observedAt));
        long startedAt = offer.getObservedAt();
        for (Event event : matching) {
            if (event.observedAt > offer.getObservedAt()) break;
            if (event.observedAt - startedAt > RACE_WINDOW_MS) startedAt = event.observedAt;
            if (startedAt == offer.getObservedAt() || event.observedAt <= offer.getObservedAt()) startedAt = Math.min(startedAt, event.observedAt);
        }
        return startedAt;
    }

    synchronized List<RankingDetail> getDetails(List<TelegramGroup> groups, Set<String> selectedIds,
                                                 long targetChatId) {
        long now = System.currentTimeMillis();
        return getDetails(groups, selectedIds, targetChatId, now - WINDOW_MS, now);
    }

    synchronized List<RankingDetail> getDetails(List<TelegramGroup> groups, Set<String> selectedIds,
                                                 long targetChatId, long windowStartedAt,
                                                 long windowEndedAt) {
        Set<Long> selectedChatIds = new HashSet<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                selectedChatIds.add(group.getId());
            }
        }
        Map<String, List<Event>> races = new HashMap<>();
        for (Event event : readEvents()) {
            if (event.observedAt >= windowStartedAt && event.observedAt < windowEndedAt
                    && selectedChatIds.contains(event.chatId)) {
                races.computeIfAbsent(event.signature, ignored -> new ArrayList<>()).add(event);
            }
        }
        List<RankingDetail> details = new ArrayList<>();
        for (List<Event> events : races.values()) {
            events.sort(Comparator.comparingLong(event -> event.observedAt));
            List<Event> race = new ArrayList<>();
            long raceStartedAt = 0L;
            for (Event event : events) {
                if (!race.isEmpty() && event.observedAt - raceStartedAt > RACE_WINDOW_MS) {
                    addDetailsForRace(race, targetChatId, details);
                    race.clear();
                }
                if (race.isEmpty()) {
                    raceStartedAt = event.observedAt;
                }
                race.add(event);
            }
            addDetailsForRace(race, targetChatId, details);
        }
        details.sort(Comparator.comparingLong(RankingDetail::getObservedAt).reversed());
        return details;
    }

    synchronized Map<Long, Integer> getApprovedOfferCounts(List<TelegramGroup> groups,
                                                            Set<String> selectedIds) {
        long now = System.currentTimeMillis();
        return getApprovedOfferCounts(groups, selectedIds, now - WINDOW_MS, now);
    }

    synchronized Map<Long, Integer> getApprovedOfferCounts(List<TelegramGroup> groups,
                                                            Set<String> selectedIds,
                                                            long windowStartedAt,
                                                            long windowEndedAt) {
        Set<Long> selectedChatIds = new HashSet<>();
        for (TelegramGroup group : groups) {
            if (selectedIds.contains(Long.toString(group.getId()))) {
                selectedChatIds.add(group.getId());
            }
        }
        Map<Long, Set<String>> uniqueOffers = new HashMap<>();
        Map<String, Long> raceStarts = new HashMap<>();
        for (Event event : readEvents()) {
            Long first = raceStarts.get(event.signature);
            if (first == null || event.observedAt < first) raceStarts.put(event.signature, event.observedAt);
        }
        for (Event event : readEvents()) {
            if (event.observedAt >= windowStartedAt && event.observedAt < windowEndedAt
                    && selectedChatIds.contains(event.chatId)) {
                long roundStartedAt = raceStarts.containsKey(event.signature)
                        ? raceStarts.get(event.signature) : event.observedAt;
                if (!expiryRepository.isExpiredAt(event.signature, roundStartedAt, event.observedAt)) {
                    uniqueOffers.computeIfAbsent(event.chatId, ignored -> new HashSet<>()).add(event.id);
                }
            }
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Map.Entry<Long, Set<String>> item : uniqueOffers.entrySet()) {
            counts.put(item.getKey(), item.getValue().size());
        }
        return counts;
    }

    private void addDetailsForRace(List<Event> race, long targetChatId,
                                   List<RankingDetail> details) {
        if (race.isEmpty()) return;
        race.sort(Comparator.comparingLong(event -> event.observedAt));
        long roundStartedAt = race.get(0).observedAt;
        Map<Long, Event> firstByGroup = new HashMap<>();
        for (Event event : race) {
            if (!expiryRepository.isExpiredAt(event.signature, roundStartedAt, event.observedAt)) {
                firstByGroup.putIfAbsent(event.chatId, event);
            }
        }
        List<Event> arrivals = new ArrayList<>(firstByGroup.values());
        arrivals.sort(Comparator.comparingLong(event -> event.observedAt));
        Event targetEvent = null;
        for (Event event : race) if (event.chatId == targetChatId) { targetEvent = event; break; }
        if (targetEvent == null) return;
        boolean expired = expiryRepository.isExpiredAt(targetEvent.signature, roundStartedAt, targetEvent.observedAt);
        if (expired) {
            details.add(new RankingDetail(targetEvent.signature, targetEvent.observedAt,
                    roundStartedAt, 0, 0, expired));
            return;
        }
        for (int index = 0; index < arrivals.size(); index++) {
            Event event = arrivals.get(index);
            if (event.chatId == targetChatId) {
                details.add(new RankingDetail(event.signature, event.observedAt,
                        roundStartedAt, index + 1, pointsForPosition(index), false));
                return;
            }
        }
    }

    private void awardRace(List<Event> race, Map<Long, Ranking> ranking) {
        if (race.isEmpty()) {
            return;
        }
        race.sort(Comparator.comparingLong(event -> event.observedAt));
        long roundStartedAt = race.get(0).observedAt;
        Set<Long> seenGroups = new HashSet<>();
        List<Event> arrivals = new ArrayList<>();
        for (Event event : race) {
            if (!expiryRepository.isExpiredAt(event.signature, roundStartedAt, event.observedAt)
                    && seenGroups.add(event.chatId)) {
                arrivals.add(event);
            }
        }
        for (int index = 0; index < arrivals.size(); index++) {
            Ranking item = ranking.get(arrivals.get(index).chatId);
            item.participations++;
            item.points += pointsForPosition(index);
            if (index == 0) {
                item.firstPlaces++;
            }
        }
    }

    private int pointsForPosition(int position) {
        if (position == 0) return 10;
        if (position == 1) return 8;
        if (position == 2) return 6;
        if (position == 3) return 5;
        if (position == 4) return 4;
        if (position == 5) return 3;
        return position < 10 ? 2 : 1;
    }

    private String signature(String interest, double price, String link) {
        String product = interest == null ? "" : OfferTextParser.normalize(interest);
        return product;
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
                        item.optString("title"), normalizeSignature(item.getString("signature")),
                        item.getLong("observed_at")));
            }
        } catch (Exception ignored) {
        }
        return events;
    }

    private String normalizeSignature(String value) {
        int separator = value == null ? -1 : value.indexOf('|');
        return separator < 0 ? value : value.substring(0, separator);
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

    static final class RankingDetail {
        private final String product;
        private final long observedAt;
        private final long roundStartedAt;
        private final int position;
        private final int points;
        private final boolean expired;

        RankingDetail(String product, long observedAt, long roundStartedAt, int position, int points,
                      boolean expired) {
            this.product = product;
            this.observedAt = observedAt;
            this.roundStartedAt = roundStartedAt;
            this.position = position;
            this.points = points;
            this.expired = expired;
        }

        String getProduct() { return product; }
        long getObservedAt() { return observedAt; }
        long getRoundStartedAt() { return roundStartedAt; }
        int getPosition() { return position; }
        int getPoints() { return points; }
        boolean isExpired() { return expired; }
    }

    private static final class Event {
        final String id; final long chatId; final String title; final String signature; final long observedAt;
        Event(String id, long chatId, String title, String signature, long observedAt) {
            this.id = id; this.chatId = chatId; this.title = title; this.signature = signature; this.observedAt = observedAt;
        }
    }
}

