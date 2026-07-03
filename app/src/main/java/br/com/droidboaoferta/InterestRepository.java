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
                        item.getDouble("maximum_price")
                ));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return interests;
    }

    void add(String term, double maximumPrice) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        long id = now;
        interests.add(new Interest(id, term.trim(), maximumPrice));
        CloudSyncStore.rememberInterestChanged(context, id, now);
        save(interests);
    }

    void update(long id, String term, double maximumPrice) {
        List<Interest> interests = new ArrayList<>(getAll());
        long now = System.currentTimeMillis();
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            if (interest.getId() == id) {
                interests.set(index, new Interest(id, term.trim(), maximumPrice));
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
                        .put("maximum_price", interest.getMaximumPrice()));
            }
            preferences.edit().putString(KEY_INTERESTS, array.toString()).apply();
            CloudSyncStore.markLocalChanged(context);
        } catch (Exception ignored) {
            // Os valores são primitivos e não devem falhar ao serem serializados.
        }
    }
}
