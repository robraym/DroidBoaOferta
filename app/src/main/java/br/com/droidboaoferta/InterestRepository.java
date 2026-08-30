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
                        item.optString("type", Interest.TYPE_PRICE)
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

    private long add(String term, double maximumPrice, String type) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        long id = now;
        interests.add(0, new Interest(id, term.trim(), maximumPrice, type));
        CloudSyncStore.rememberInterestChanged(context, id, now);
        save(interests);
        return id;
    }

    void update(long id, String term, double maximumPrice) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            if (interest.getId() == id) {
                interests.set(index, new Interest(
                        id, term.trim(), maximumPrice, interest.getType()));
                CloudSyncStore.rememberInterestChanged(context, id, now);
                break;
            }
        }
        save(interests);
    }

    void remove(long id) {
        List<Interest> interests = new ArrayList<>(getAll());
        interests.removeIf(interest -> interest.getId() == id);
        CloudSyncStore.rememberInterestDeleted(context, id, System.currentTimeMillis());
        save(interests);
    }

    private void save(List<Interest> interests) {
        JSONArray array = new JSONArray();
        try {
            for (Interest interest : interests) {
                array.put(new JSONObject()
                        .put("id", interest.getId())
                        .put("term", interest.getTerm())
                        .put("maximum_price", interest.getMaximumPrice())
                        .put("type", interest.getType()));
            }
            preferences.edit().putString(KEY_INTERESTS, array.toString()).apply();
            CloudSyncStore.markLocalChanged(context);
            CloudSyncStore.syncInterestsChanged(context);
        } catch (Exception ignored) {
            // Os valores são primitivos e não devem falhar ao serem serializados.
        }
    }
}
