package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class InterestRepository {
    private static final String PREFS = "offer_preferences";
    private static final String KEY_INTERESTS = "interests";

    private final Context context;
    private final SharedPreferences preferences;

    InterestRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Interest> getAll() {
        String stored = preferences.getString(KEY_INTERESTS, "[]");
        List<Interest> interests = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                interests.add(new Interest(
                        item.getLong("id"),
                        item.getString("term"),
                        item.getDouble("maximum_price"),
                        item.optString("type", Interest.TYPE_PRICE),
                        item.optDouble("minimum_area", 0d),
                        item.optDouble("maximum_area", 0d),
                        item.optString("property_name", "")
                ));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        interests.sort((first, second) -> Long.compare(second.getId(), first.getId()));
        return interests;
    }

    long add(String term, double maximumPrice) {
        return add(term, maximumPrice, Interest.TYPE_PRICE);
    }

    long addCoupon(String pageUrl, double minimumCouponValue) {
        return add(pageUrl, minimumCouponValue, Interest.TYPE_COUPON);
    }

    long addProperty(String pageUrl, double minimumArea, double maximumArea,
                     double maximumPrice, String propertyName) {
        return add(pageUrl, maximumPrice, Interest.TYPE_PROPERTY, minimumArea, maximumArea,
                propertyName);
    }

    private long add(String term, double maximumPrice, String type) {
        return add(term, maximumPrice, type, 0d, 0d, "");
    }

    private long add(String term, double maximumPrice, String type,
                     double minimumArea, double maximumArea, String propertyName) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        long id = now;
        Interest added = new Interest(
                id, term.trim(), maximumPrice, type, minimumArea, maximumArea, propertyName);
        interests.add(0, added);
        CloudSyncStore.rememberInterestChanged(context, id, now);
        save(interests);
        CloudSyncStore.syncInterestChanged(context, null, added, now);
        return id;
    }

    void update(long id, String term, double maximumPrice) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        Interest previous = null;
        Interest updated = null;
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            if (interest.getId() == id) {
                previous = interest;
                updated = new Interest(
                        id, term.trim(), maximumPrice, interest.getType(),
                        interest.getMinimumArea(), interest.getMaximumArea(),
                        interest.getPropertyName());
                interests.set(index, updated);
                CloudSyncStore.rememberInterestChanged(context, id, now);
                break;
            }
        }
        save(interests);
        if (updated != null) {
            CloudSyncStore.syncInterestChanged(context, previous, updated, now);
        }
    }

    void updateProperty(long id, String pageUrl, double minimumArea,
                        double maximumArea, double maximumPrice, String propertyName) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        Interest previous = null;
        Interest updated = null;
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            if (interest.getId() == id) {
                previous = interest;
                updated = new Interest(
                        id,
                        pageUrl.trim(),
                        maximumPrice,
                        Interest.TYPE_PROPERTY,
                        minimumArea,
                        maximumArea,
                        propertyName
                );
                interests.set(index, updated);
                CloudSyncStore.rememberInterestChanged(context, id, now);
                break;
            }
        }
        save(interests);
        if (updated != null) {
            CloudSyncStore.syncInterestChanged(context, previous, updated, now);
        }
    }

    void updatePropertyName(long id, String propertyName) {
        if (propertyName == null || propertyName.trim().isEmpty()) {
            return;
        }
        List<Interest> interests = new ArrayList<>(getAll());
        Interest previous = null;
        Interest updated = null;
        long now = System.currentTimeMillis();
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            if (interest.getId() == id && interest.isProperty()
                    && !propertyName.trim().equals(interest.getPropertyName())) {
                previous = interest;
                updated = new Interest(
                        interest.getId(), interest.getTerm(), interest.getMaximumPrice(),
                        interest.getType(), interest.getMinimumArea(), interest.getMaximumArea(),
                        propertyName);
                interests.set(index, updated);
                CloudSyncStore.rememberInterestChanged(context, id, now);
                break;
            }
        }
        if (updated != null) {
            save(interests);
            CloudSyncStore.syncInterestChanged(context, previous, updated, now);
        }
    }

    void remove(long id) {
        List<Interest> interests = new ArrayList<>(getAll());
        boolean removed = interests.removeIf(interest -> interest.getId() == id);
        long deletedAt = System.currentTimeMillis();
        CloudSyncStore.rememberInterestDeleted(context, id, deletedAt);
        save(interests);
        if (removed) {
            CloudSyncStore.syncInterestDeleted(context, id, deletedAt);
        }
    }

    private void save(List<Interest> interests) {
        JSONArray array = new JSONArray();
        try {
            for (Interest interest : interests) {
                array.put(new JSONObject()
                        .put("id", interest.getId())
                        .put("term", interest.getTerm())
                        .put("maximum_price", interest.getMaximumPrice())
                        .put("type", interest.getType())
                        .put("minimum_area", interest.getMinimumArea())
                        .put("maximum_area", interest.getMaximumArea())
                        .put("property_name", interest.getPropertyName()));
            }
            preferences.edit().putString(KEY_INTERESTS, array.toString()).apply();
        } catch (Exception ignored) {
            // Os valores são primitivos e não devem falhar ao serem serializados.
        }
    }
}
