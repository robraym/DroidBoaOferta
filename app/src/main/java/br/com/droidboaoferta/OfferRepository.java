package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class OfferRepository {
    private static final String PREFS = "offer_preferences";
    private static final String KEY_OFFERS = "recent_offers";
    private static final String KEY_PROCESSED_MESSAGES = "processed_messages";
    private static final int MAX_OFFERS = 30;
    private static final int MAX_PROCESSED_MESSAGES = 500;

    private final SharedPreferences preferences;

    OfferRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        offers.add(0, offer);
        if (offers.size() > MAX_OFFERS) {
            offers = new ArrayList<>(offers.subList(0, MAX_OFFERS));
        }

        JSONArray array = new JSONArray();
        try {
            for (ObservedOffer item : offers) {
                array.put(new JSONObject()
                        .put("interest", item.getInterest())
                        .put("source", item.getSource())
                        .put("price", item.getPrice())
                        .put("maximum_price", item.getMaximumPrice())
                        .put("observed_at", item.getObservedAt())
                        .put("link", item.getLink()));
            }
            preferences.edit().putString(KEY_OFFERS, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    List<ObservedOffer> getRecent() {
        List<ObservedOffer> offers = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_OFFERS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                offers.add(new ObservedOffer(
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
