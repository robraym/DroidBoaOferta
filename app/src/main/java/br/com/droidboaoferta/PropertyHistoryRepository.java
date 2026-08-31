package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PropertyHistoryRepository {
    static final int UNCHANGED = 0;
    static final int CREATED = 1;
    static final int CHANGED = 2;
    private static final String PREFS = "property_history";
    private static final String KEY_ENTRIES = "entries";
    private static final long METADATA_RETRY_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_POINTS = 60;

    private final SharedPreferences preferences;

    PropertyHistoryRepository(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean shouldFetchMetadata(long interestId, PropertyPageListing listing, long now) {
        JSONObject item = find(readEntries(), interestId, listing.getId());
        if (item == null) {
            return true;
        }
        if (item.optLong("first_publication_at", 0L) > 0L) {
            return false;
        }
        String normalizedUrl = PropertyPageClient.normalizeListingUrl(listing.getUrl());
        String previousUrl = PropertyPageClient.normalizeListingUrl(item.optString("url", ""));
        boolean urlChanged = normalizedUrl != null && !normalizedUrl.equals(previousUrl);
        return urlChanged || now - item.optLong("metadata_attempted_at", 0L) >= METADATA_RETRY_MS;
    }

    synchronized void clearMetadataAttemptsForInterest(long interestId) {
        JSONArray entries = readEntries();
        boolean changed = false;
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item != null && item.optLong("interest_id", 0L) == interestId) {
                item.remove("metadata_attempted_at");
                changed = true;
            }
        }
        if (changed) {
            preferences.edit().putString(KEY_ENTRIES, entries.toString()).apply();
        }
    }

    synchronized int recordObservation(long interestId, PropertyPageListing listing,
                                       long observedAt,
                                       PropertyListingMetadata metadata) {
        JSONArray entries = readEntries();
        JSONObject item = find(entries, interestId, listing.getId());
        boolean created = item == null;
        if (created) {
            item = new JSONObject();
            entries.put(item);
        }
        JSONArray points = item.optJSONArray("points");
        if (points == null) {
            points = new JSONArray();
        }
        JSONObject previous = points.optJSONObject(points.length() - 1);
        boolean changed = previous == null
                || Double.compare(previous.optDouble("price", 0d), listing.getSalePrice()) != 0
                || Double.compare(previous.optDouble("area", 0d), listing.getArea()) != 0;
        try {
            item.put("interest_id", interestId)
                    .put("listing_id", listing.getId())
                    .put("title", listing.getDescription())
                    .put("url", normalizeListingUrl(listing.getUrl()))
                    .put("first_seen_at", item.optLong("first_seen_at", observedAt))
                    .put("last_seen_at", observedAt)
                    .put("new_ad", listing.isNewAd());
            if (metadata != null) {
                item.put("metadata_attempted_at", observedAt);
                if (metadata.getFirstPublicationAt() > 0L) {
                    item.put("first_publication_at", metadata.getFirstPublicationAt());
                }
                if (metadata.getLastPublicationAt() > 0L) {
                    item.put("last_publication_at", metadata.getLastPublicationAt());
                }
            }
            if (changed) {
                points.put(new JSONObject()
                        .put("observed_at", observedAt)
                        .put("price", listing.getSalePrice())
                        .put("area", listing.getArea()));
                while (points.length() > MAX_POINTS) {
                    points.remove(0);
                }
            }
            item.put("points", points);
            preferences.edit().putString(KEY_ENTRIES, entries.toString()).apply();
        } catch (Exception ignored) {
            return UNCHANGED;
        }
        return created ? CREATED : (changed ? CHANGED : UNCHANGED);
    }

    synchronized PropertyHistoryEntry getForOffer(ObservedOffer offer) {
        if (offer == null || !offer.getId().startsWith("property|")) {
            return null;
        }
        String[] parts = offer.getId().split("\\|", 4);
        if (parts.length < 3) {
            return null;
        }
        try {
            return toEntry(find(readEntries(), Long.parseLong(parts[1]), parts[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    synchronized boolean isRecentOffer(ObservedOffer offer) {
        PropertyHistoryEntry entry = getForOffer(offer);
        return entry != null && entry.isRecent(System.currentTimeMillis());
    }

    private JSONArray readEntries() {
        try {
            return new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private JSONObject find(JSONArray entries, long interestId, String listingId) {
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item != null && item.optLong("interest_id", 0L) == interestId
                    && listingId.equals(item.optString("listing_id", ""))) {
                return item;
            }
        }
        return null;
    }

    private PropertyHistoryEntry toEntry(JSONObject item) {
        if (item == null) {
            return null;
        }
        List<PropertyHistoryPoint> points = new ArrayList<>();
        JSONArray values = item.optJSONArray("points");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value != null) {
                    points.add(new PropertyHistoryPoint(
                            value.optLong("observed_at", 0L),
                            value.optDouble("price", 0d),
                            value.optDouble("area", 0d)));
                }
            }
        }
        return new PropertyHistoryEntry(
                item.optLong("interest_id", 0L), item.optString("listing_id", ""),
                item.optString("title", ""), normalizeListingUrl(item.optString("url", "")),
                item.optLong("first_seen_at", 0L), item.optLong("last_seen_at", 0L),
                item.optLong("first_publication_at", 0L), item.optBoolean("new_ad", false),
                points);
    }

    private String normalizeListingUrl(String url) {
        String normalizedUrl = PropertyPageClient.normalizeListingUrl(url);
        return normalizedUrl == null ? (url == null ? "" : url) : normalizedUrl;
    }
}
