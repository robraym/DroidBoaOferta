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
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    PropertyHistoryRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized boolean shouldFetchMetadata(long interestId, PropertyPageListing listing, long now) {
        JSONObject item = find(readEntries(), interestId, listing.getId(), listing.getUrl());
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
        synchronized (preferences) {
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
    }

    synchronized boolean contains(long interestId, PropertyPageListing listing) {
        return find(readEntries(), interestId, listing.getId(), listing.getUrl()) != null;
    }

    synchronized int recordObservation(long interestId, PropertyPageListing listing,
                                       long observedAt,
                                       PropertyListingMetadata metadata) {
        synchronized (preferences) {
            if (PropertyPageClient.normalizeListingUrl(listing.getUrl()) == null) return UNCHANGED;
            boolean requiresIdentity = PropertyPageClient.isQuintoAndarListingUrl(listing.getUrl());
            if (requiresIdentity && (metadata == null || !metadata.isVerifiedFor(listing.getId())
                    || Double.compare(metadata.getSalePrice(), listing.getSalePrice()) != 0
                    || Double.compare(metadata.getArea(), listing.getArea()) != 0)) {
                return UNCHANGED;
            }
            JSONArray entries = readEntries();
            JSONObject item = find(entries, interestId, listing.getId(), listing.getUrl());
            boolean created = item == null;
            if (created) {
                item = new JSONObject();
                entries.put(item);
            }
            String before;
            try {
                before = PropertyHistorySync.changeKey(item);
                restoreCompatibleLegacyHistory(item, metadata);
            } catch (org.json.JSONException ignored) {
                return UNCHANGED;
            }
            JSONArray points = item.optJSONArray("points");
            if (points == null) {
                points = new JSONArray();
            }
            JSONObject previous = points.optJSONObject(points.length() - 1);
            boolean changed = hasObservationChanged(previous, listing);
            try {
                item.put("interest_id", interestId)
                        .put("listing_id", listing.getId())
                        .put("title", listing.getDescription())
                        .put("url", normalizeListingUrl(listing.getUrl()))
                        .put("first_seen_at", item.optLong("first_seen_at", observedAt))
                        .put("last_seen_at", observedAt)
                        .put("new_ad", listing.isNewAd());
                if (requiresIdentity) {
                    item.put("identity_validation_version", 1)
                            .put("validation_status", "available");
                }
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
                            .put("area", listing.getArea())
                            .put("identity_verified", requiresIdentity));
                    while (points.length() > MAX_POINTS) {
                        points.remove(0);
                    }
                }
                item.put("points", points);
                SharedPreferences.Editor editor = preferences.edit().putString(KEY_ENTRIES, entries.toString());
                if (!before.equals(PropertyHistorySync.changeKey(item))) editor.putBoolean("sync_dirty", true);
                editor.apply();
            } catch (Exception ignored) {
                return UNCHANGED;
            }
            return created ? CREATED : (changed ? CHANGED : UNCHANGED);
        }
    }

    static boolean hasObservationChanged(JSONObject previous, PropertyPageListing listing) {
        return previous == null
                || Double.compare(previous.optDouble("price", 0d), listing.getSalePrice()) != 0
                || Double.compare(previous.optDouble("area", 0d), listing.getArea()) != 0;
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
            JSONArray entries = readEntries();
            String identity = PropertyHistorySync.identity(offer.getLink(), parts[2]);
            JSONObject exact = find(entries, Long.parseLong(parts[1]), parts[2]);
            if (exact != null && (identity.isEmpty() || identity.equals(PropertyHistorySync.identity(
                    exact.optString("url"), exact.optString("listing_id"))))) return toEntry(exact);
            for (int index = 0; !identity.isEmpty() && index < entries.length(); index++) {
                JSONObject item = entries.optJSONObject(index);
                if (item != null && identity.equals(PropertyHistorySync.identity(
                        item.optString("url"), item.optString("listing_id")))) return toEntry(item);
            }
            return null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    synchronized boolean isRecentOffer(ObservedOffer offer) {
        PropertyHistoryEntry entry = getForOffer(offer);
        return entry != null && entry.isRecent(System.currentTimeMillis());
    }

    private JSONArray readEntries() {
        synchronized (preferences) {
            try {
                JSONArray entries = new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
                boolean changed = false;
                for (int index = 0; index < entries.length(); index++) {
                    changed |= restoreCompatibleLegacyHistory(entries.optJSONObject(index), null);
                }
                if (changed) {
                    preferences.edit().putString(KEY_ENTRIES, entries.toString()).apply();
                }
                return entries;
            } catch (Exception ignored) {
                return new JSONArray();
            }
        }
    }

    // Only an unavailable listing that has never been validated triggers this isolation.
    static boolean quarantineLegacyHistory(JSONObject item) throws org.json.JSONException {
        if (item == null || !PropertyPageClient.isQuintoAndarListingUrl(item.optString("url"))
                || item.optInt("identity_validation_version", 0) >= 1) {
            return false;
        }
        JSONObject legacy = new JSONObject(item.toString());
        item.put("legacy_unverified", legacy)
                .put("points", new JSONArray())
                .put("first_publication_at", 0L)
                .put("last_publication_at", 0L)
                .put("validation_status", "pending")
                .put("legacy_isolation_reason", "unavailable")
                .put("identity_validation_version", 1);
        return true;
    }

    // Repair the previous overly broad migration without restoring known-bad histories.
    static boolean restoreCompatibleLegacyHistory(JSONObject item, PropertyListingMetadata metadata)
            throws org.json.JSONException {
        if (item == null || !PropertyPageClient.isQuintoAndarListingUrl(item.optString("url"))
                || item.optBoolean("legacy_history_restored", false)) return false;
        JSONObject legacy = item.optJSONObject("legacy_unverified");
        if (legacy == null || item.has("legacy_isolation_reason")) return false;
        if ("unavailable".equals(item.optString("validation_status"))) {
            item.put("legacy_isolation_reason", "unavailable");
            return true;
        }
        JSONArray oldPoints = legacy.optJSONArray("points");
        JSONObject lastOld = oldPoints == null ? null : oldPoints.optJSONObject(oldPoints.length() - 1);
        if (lastOld == null) return false;
        JSONArray currentPoints = item.optJSONArray("points");
        boolean compatible = metadata != null && metadata.isVerifiedFor(item.optString("listing_id"))
                && samePriceAndArea(lastOld, metadata.getSalePrice(), metadata.getArea());
        if (!compatible && "available".equals(item.optString("validation_status"))) {
            for (int index = 0; currentPoints != null && index < currentPoints.length(); index++) {
                JSONObject point = currentPoints.optJSONObject(index);
                if (point != null && samePriceAndArea(lastOld,
                        point.optDouble("price"), point.optDouble("area"))) {
                    compatible = true;
                    break;
                }
            }
        }
        if (!compatible) return false;
        List<JSONObject> combined = new ArrayList<>();
        for (JSONArray array : new JSONArray[]{oldPoints, currentPoints}) {
            for (int index = 0; array != null && index < array.length(); index++) {
                JSONObject point = array.optJSONObject(index);
                if (point != null) combined.add(point);
            }
        }
        combined.sort(java.util.Comparator.comparingLong(point -> point.optLong("observed_at")));
        JSONArray restored = new JSONArray();
        JSONObject previous = null;
        for (JSONObject point : combined) {
            if (previous == null || !samePriceAndArea(previous,
                    point.optDouble("price"), point.optDouble("area"))) {
                restored.put(point);
                previous = point;
            }
        }
        item.put("points", restored).put("legacy_history_restored", true);
        for (String key : new String[]{"first_publication_at", "last_publication_at"}) {
            if (item.optLong(key, 0L) <= 0L) item.put(key, legacy.optLong(key, 0L));
        }
        return true;
    }

    private static boolean samePriceAndArea(JSONObject point, double price, double area) {
        return price > 0d && area > 0d
                && Double.compare(point.optDouble("price", Double.NaN), price) == 0
                && Double.compare(point.optDouble("area", Double.NaN), area) == 0;
    }

    synchronized void markUnavailable(long interestId, PropertyPageListing listing, long checkedAt) {
        synchronized (preferences) {
            JSONArray entries = readEntries();
            JSONObject item = find(entries, interestId, listing.getId(), listing.getUrl());
            if (item == null) {
                item = new JSONObject();
                entries.put(item);
            }
            try {
                String before = PropertyHistorySync.changeKey(item);
                quarantineLegacyHistory(item);
                if (item.optJSONObject("legacy_unverified") != null
                        && !item.optBoolean("legacy_history_restored", false)) {
                    item.put("legacy_isolation_reason", "unavailable");
                }
                item.put("interest_id", interestId).put("listing_id", listing.getId())
                        .put("url", listing.getUrl()).put("title", listing.getDescription())
                        .put("first_seen_at", item.optLong("first_seen_at", checkedAt))
                        .put("last_seen_at", checkedAt).put("validation_status", "unavailable")
                        .put("identity_validation_version", 1)
                        .put("last_summary_area", listing.getArea())
                        .put("last_summary_price", listing.getSalePrice());
                SharedPreferences.Editor editor = preferences.edit().putString(KEY_ENTRIES, entries.toString());
                if (!before.equals(PropertyHistorySync.changeKey(item))) editor.putBoolean("sync_dirty", true);
                editor.apply();
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    synchronized List<PropertyPageListing> getTrackedListings(long interestId) {
        List<PropertyPageListing> listings = new ArrayList<>();
        JSONArray entries = readEntries();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item == null || item.optLong("interest_id") != interestId
                    || !PropertyPageClient.isQuintoAndarListingUrl(item.optString("url"))) continue;
            JSONArray points = item.optJSONArray("points");
            if ((points == null || points.length() == 0) && item.optJSONObject("legacy_unverified") != null) {
                points = item.optJSONObject("legacy_unverified").optJSONArray("points");
            }
            JSONObject last = points == null ? null : points.optJSONObject(points.length() - 1);
            double area = last == null ? item.optDouble("last_summary_area", 0d) : last.optDouble("area");
            double price = last == null ? item.optDouble("last_summary_price", 0d) : last.optDouble("price");
            if (area > 0d && price > 0d) {
                listings.add(new PropertyPageListing(item.optString("listing_id"), area, price,
                        item.optString("title"), item.optString("url")));
            }
        }
        return listings;
    }

    synchronized JSONArray exportForSync() {
        try {
            return PropertyHistorySync.merge(readEntries(), new JSONArray());
        } catch (org.json.JSONException ignored) {
            return new JSONArray();
        }
    }

    synchronized boolean mergeFromSync(JSONArray remote) {
        if (remote == null || remote.length() == 0) return false;
        synchronized (preferences) {
            try {
                JSONArray local = readEntries();
                JSONArray merged = PropertyHistorySync.merge(local, remote);
                // Malformed local legacy records are preserved but never exported or merged by identity.
                for (int i = 0; i < local.length(); i++) {
                    JSONObject item = local.optJSONObject(i);
                    if (item != null && PropertyHistorySync.identity(item.optString("url"),
                            item.optString("listing_id")).isEmpty()) merged.put(item);
                }
                if (PropertyHistorySync.contentKey(local).equals(PropertyHistorySync.contentKey(merged))) return false;
                preferences.edit().putString(KEY_ENTRIES, merged.toString()).apply();
                return true;
            } catch (org.json.JSONException ignored) {
                return false;
            }
        }
    }

    static void publishPendingChanges(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (consumePendingSync(prefs)) CloudSyncStore.markLocalChanged(context);
    }

    static boolean consumePendingSync(SharedPreferences prefs) {
        synchronized (prefs) {
            boolean migrated = prefs.getBoolean("sync_migrated_v1", false);
            boolean dirty = prefs.getBoolean("sync_dirty", false)
                    || (!migrated && !"[]".equals(prefs.getString(KEY_ENTRIES, "[]")));
            if (migrated && !dirty) return false;
            prefs.edit().putBoolean("sync_migrated_v1", true).putBoolean("sync_dirty", false).apply();
            return dirty;
        }
    }

    private JSONObject find(JSONArray entries, long interestId, String listingId) {
        return find(entries, interestId, listingId, null);
    }

    private JSONObject find(JSONArray entries, long interestId, String listingId, String url) {
        String identity = PropertyHistorySync.identity(url, listingId);
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item != null && item.optLong("interest_id", 0L) == interestId
                    && listingId.equals(item.optString("listing_id", ""))
                    && (url == null || (!identity.isEmpty() && identity.equals(PropertyHistorySync.identity(
                    item.optString("url"), item.optString("listing_id")))))) {
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
                points, item.optString("validation_status", "available"),
                (item.optJSONObject("legacy_unverified") != null
                        && !item.optBoolean("legacy_history_restored", false))
                        || (item.optJSONArray("sync_quarantined_points") != null
                        && item.optJSONArray("sync_quarantined_points").length() > 0));
    }

    private String normalizeListingUrl(String url) {
        String normalizedUrl = PropertyPageClient.normalizeListingUrl(url);
        return normalizedUrl == null ? (url == null ? "" : url) : normalizedUrl;
    }
}
