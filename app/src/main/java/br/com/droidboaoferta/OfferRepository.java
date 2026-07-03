package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class OfferRepository {
    private static final String PREFS = "offer_preferences";
    private static final String KEY_OFFERS = "recent_offers";
    private static final String KEY_ARCHIVED_OFFERS = "archived_offers";
    private static final String KEY_TRASHED_OFFERS = "trashed_offers";
    private static final String KEY_PROCESSED_MESSAGES = "processed_messages";
    private static final int MAX_OFFERS = 30;
    private static final int MAX_PROCESSED_MESSAGES = 500;

    private final Context context;
    private final SharedPreferences preferences;

    OfferRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean markOfferProcessed(long chatId, long messageId, long interestId) {
        String key = chatId + ":" + messageId + ":" + interestId;
        List<String> processed = readProcessedMessages();
        if (processed.contains(key)) {
            return false;
        }
        processed.add(0, key);
        if (processed.size() > MAX_PROCESSED_MESSAGES) {
            processed = new ArrayList<>(processed.subList(0, MAX_PROCESSED_MESSAGES));
        }
        preferences.edit().putString(KEY_PROCESSED_MESSAGES, new JSONArray(processed).toString()).apply();
        return true;
    }

    synchronized void add(ObservedOffer offer) {
        List<ObservedOffer> offers = new ArrayList<>(getRecent());
        offers.removeIf(item -> isSameObservedOffer(item, offer));
        offers.add(0, offer);
        saveOffers(KEY_OFFERS, trimOffers(sortByObservedAt(offers)));
    }

    synchronized void clearProcessedForInterest(long interestId) {
        List<String> processed = new ArrayList<>(readProcessedMessages());
        String suffix = ":" + interestId;
        processed.removeIf(item -> item.endsWith(suffix));
        preferences.edit().putString(KEY_PROCESSED_MESSAGES, new JSONArray(processed).toString()).apply();
    }

    synchronized void reconcileRecentWithInterests(List<Interest> interests) {
        List<ObservedOffer> recent = new ArrayList<>(readOffers(KEY_OFFERS));
        List<ObservedOffer> reconciled = new ArrayList<>();
        for (ObservedOffer offer : recent) {
            Interest matchingInterest = findMatchingInterest(offer, interests);
            if (matchingInterest == null || offer.getPrice() > matchingInterest.getMaximumPrice()) {
                continue;
            }
            reconciled.add(new ObservedOffer(
                    offer.getId(),
                    matchingInterest.getId(),
                    matchingInterest.getTerm(),
                    offer.getSource(),
                    offer.getPrice(),
                    matchingInterest.getMaximumPrice(),
                    offer.getObservedAt(),
                    offer.getLink()
            ));
        }
        saveOffers(KEY_OFFERS, trimOffers(sortByObservedAt(reconciled)));
    }

    synchronized void archive(String id) {
        if (moveOffer(id, KEY_OFFERS, KEY_ARCHIVED_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void unarchive(String id) {
        if (moveOffer(id, KEY_ARCHIVED_OFFERS, KEY_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void trash(String id) {
        if (moveOffer(id, KEY_OFFERS, KEY_TRASHED_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized boolean trashAllRecent() {
        List<ObservedOffer> recent = new ArrayList<>(readOffers(KEY_OFFERS));
        if (recent.isEmpty()) {
            return false;
        }
        List<ObservedOffer> trashed = new ArrayList<>(readOffers(KEY_TRASHED_OFFERS));
        for (ObservedOffer offer : recent) {
            trashed.removeIf(item -> item.getId().equals(offer.getId()));
            trashed.add(0, offer);
        }
        saveOffers(KEY_OFFERS, new ArrayList<>());
        saveOffers(KEY_TRASHED_OFFERS, trimOffers(sortByObservedAt(trashed)));
        CloudSyncStore.markLocalChanged(context);
        return true;
    }

    synchronized void trashArchived(String id) {
        if (moveOffer(id, KEY_ARCHIVED_OFFERS, KEY_TRASHED_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void restoreTrashed(String id) {
        if (moveOffer(id, KEY_TRASHED_OFFERS, KEY_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void restoreAllTrashed() {
        List<ObservedOffer> trashed = new ArrayList<>(readOffers(KEY_TRASHED_OFFERS));
        if (trashed.isEmpty()) {
            return;
        }
        List<ObservedOffer> recent = new ArrayList<>(readOffers(KEY_OFFERS));
        for (ObservedOffer offer : trashed) {
            recent.removeIf(item -> item.getId().equals(offer.getId()));
            recent.add(0, offer);
        }
        saveOffers(KEY_OFFERS, trimOffers(sortByObservedAt(recent)));
        saveOffers(KEY_TRASHED_OFFERS, new ArrayList<>());
        CloudSyncStore.markLocalChanged(context);
    }

    synchronized void deleteArchived(String id) {
        if (removeOffer(id, KEY_ARCHIVED_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void deleteTrashed(String id) {
        if (removeOffer(id, KEY_TRASHED_OFFERS)) {
            CloudSyncStore.markLocalChanged(context);
        }
    }

    synchronized void clearTrashed() {
        saveOffers(KEY_TRASHED_OFFERS, new ArrayList<>());
        CloudSyncStore.markLocalChanged(context);
    }

    List<ObservedOffer> getRecent() {
        return readOffers(KEY_OFFERS);
    }

    List<ObservedOffer> getArchived() {
        return readOffers(KEY_ARCHIVED_OFFERS);
    }

    List<ObservedOffer> getTrashed() {
        return readOffers(KEY_TRASHED_OFFERS);
    }

    private boolean moveOffer(String id, String fromKey, String toKey) {
        List<ObservedOffer> from = new ArrayList<>(readOffers(fromKey));
        ObservedOffer target = null;
        for (ObservedOffer offer : from) {
            if (offer.getId().equals(id)) {
                target = offer;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        from.removeIf(offer -> offer.getId().equals(id));
        List<ObservedOffer> to = new ArrayList<>(readOffers(toKey));
        to.removeIf(offer -> offer.getId().equals(id));
        to.add(0, target);
        saveOffers(fromKey, from);
        saveOffers(toKey, trimOffers(to));
        return true;
    }

    private boolean removeOffer(String id, String key) {
        List<ObservedOffer> offers = new ArrayList<>(readOffers(key));
        boolean removed = offers.removeIf(offer -> offer.getId().equals(id));
        if (!removed) {
            return false;
        }
        saveOffers(key, offers);
        return true;
    }

    private List<ObservedOffer> trimOffers(List<ObservedOffer> offers) {
        if (offers.size() > MAX_OFFERS) {
            return new ArrayList<>(offers.subList(0, MAX_OFFERS));
        }
        return sortByObservedAt(offers);
    }

    private List<ObservedOffer> sortByObservedAt(List<ObservedOffer> offers) {
        offers.sort(Comparator.comparingLong(ObservedOffer::getObservedAt).reversed());
        return offers;
    }

    private void saveOffers(String key, List<ObservedOffer> offers) {
        JSONArray array = new JSONArray();
        try {
            for (ObservedOffer item : offers) {
                array.put(new JSONObject()
                        .put("id", item.getId())
                        .put("interest_id", item.getInterestId())
                        .put("interest", item.getInterest())
                        .put("source", item.getSource())
                        .put("price", item.getPrice())
                        .put("maximum_price", item.getMaximumPrice())
                        .put("observed_at", item.getObservedAt())
                        .put("link", item.getLink()));
            }
            preferences.edit().putString(key, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<ObservedOffer> readOffers(String key) {
        List<ObservedOffer> offers = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(key, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                offers.add(new ObservedOffer(
                        item.optString("id", ""),
                        item.optLong("interest_id", 0L),
                        item.getString("interest"),
                        item.getString("source"),
                        item.getDouble("price"),
                        item.getDouble("maximum_price"),
                        item.getLong("observed_at"),
                        item.optString("link")
                ));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return offers;
    }

    private Interest findMatchingInterest(ObservedOffer offer, List<Interest> interests) {
        for (Interest interest : interests) {
            if (offer.getInterestId() != 0L && offer.getInterestId() == interest.getId()) {
                return interest;
            }
        }
        String normalizedOfferTerm = OfferTextParser.normalize(offer.getInterest());
        for (Interest interest : interests) {
            if (normalizedOfferTerm.equals(OfferTextParser.normalize(interest.getTerm()))) {
                return interest;
            }
        }
        return null;
    }

    private boolean isSameObservedOffer(ObservedOffer first, ObservedOffer second) {
        return first.getInterestId() == second.getInterestId()
                && normalize(first.getInterest()).equals(normalize(second.getInterest()))
                && normalize(first.getSource()).equals(normalize(second.getSource()))
                && Double.compare(first.getPrice(), second.getPrice()) == 0
                && first.getObservedAt() == second.getObservedAt()
                && first.getLink().equals(second.getLink());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> readProcessedMessages() {
        List<String> processed = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_PROCESSED_MESSAGES, "[]"));
            for (int index = 0; index < array.length(); index++) {
                processed.add(array.getString(index));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return processed;
    }
}
