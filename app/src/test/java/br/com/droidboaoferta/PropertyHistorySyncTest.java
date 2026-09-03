package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PropertyHistorySyncTest {
    @Test public void sendsExistingHistoryOnceAndCoalescesPendingChanges() throws Exception {
        Map<String, String> storage = new HashMap<>();
        storage.put("entries", new JSONArray().put(entry(1, "123", 100, point(100, 399000))).toString());
        android.content.SharedPreferences prefs = PropertyHistoryRepositoryTest.memoryPreferences(storage);
        assertTrue(PropertyHistoryRepository.consumePendingSync(prefs));
        for (int i = 0; i < 100; i++) assertFalse(PropertyHistoryRepository.consumePendingSync(prefs));
        storage.put("sync_dirty", "true");
        storage.put("sync_dirty", "true");
        assertTrue(PropertyHistoryRepository.consumePendingSync(prefs));
        assertFalse(PropertyHistoryRepository.consumePendingSync(prefs));
    }

    @Test public void supportsBothLoftUrlFormatsWithoutMixingItsPricesWithQuintoAndar() {
        assertEquals(PropertyHistorySync.identity("https://loft.com.br/imovel/abc", "abc"),
                PropertyHistorySync.identity("https://loft.com.br/imovel/apartamento-centro/abc", "abc"));
        assertFalse(PropertyHistorySync.identity("https://loft.com.br/imovel/abc", "abc").equals(
                PropertyHistorySync.identity("https://www.quintoandar.com.br/imovel/abc/comprar", "abc")));
        assertEquals("", PropertyHistorySync.identity("https://loft.com.br/imovel/other", "abc"));
    }

    @Test public void preservesWorkingOlderHistoryEvenIfOtherDeviceFirstSawANewerPrice() throws Exception {
        JSONObject old = entry(1, "123", 200, point(100, 414000), point(200, 399000));
        old.remove("identity_validation_version");
        JSONArray merged = PropertyHistorySync.merge(new JSONArray().put(old),
                new JSONArray().put(entry(1, "123", 300, point(300, 390000))));
        assertEquals(3, merged.getJSONObject(0).getJSONArray("points").length());
        assertFalse(merged.getJSONObject(0).has("sync_quarantined_points"));
    }

    @Test public void compactQuarantineRoundTripCannotReactivateBadPoints() throws Exception {
        JSONObject old = entry(1, "123", 200, point(100, 520000), point(200, 320000));
        old.remove("identity_validation_version");
        JSONObject blocked = new JSONObject(old.toString());
        PropertyHistoryRepository.quarantineLegacyHistory(blocked);
        blocked.put("last_seen_at", 300).put("validation_status", "unavailable");
        JSONArray merged = PropertyHistorySync.merge(new JSONArray().put(old), new JSONArray().put(blocked));
        JSONArray restored = PropertyHistorySync.unpack(PropertyHistorySync.pack(merged));
        assertEquals(PropertyHistorySync.contentKey(merged), PropertyHistorySync.contentKey(restored));
        assertEquals(PropertyHistorySync.contentKey(merged), PropertyHistorySync.contentKey(
                PropertyHistorySync.merge(restored, new JSONArray().put(old))));
    }

    @Test public void limitsActivePointsToSixtyWithoutUploadingDiscardedOlderPointsAgain() throws Exception {
        JSONArray old = new JSONArray().put(entry(1, "123", 1, point(1, 414000)));
        JSONArray recentPoints = new JSONArray();
        for (int i = 2; i <= 80; i++) recentPoints.put(point(i, 400000 - i));
        JSONArray recent = new JSONArray().put(entry(1, "123", 80).put("points", recentPoints));
        JSONArray merged = PropertyHistorySync.merge(old, recent);
        assertEquals(60, merged.getJSONObject(0).getJSONArray("points").length());
        assertEquals(PropertyHistorySync.contentKey(merged), PropertyHistorySync.contentKey(PropertyHistorySync.merge(merged, old)));
    }

    static JSONObject point(long at, double price) throws Exception {
        return new JSONObject().put("observed_at", at).put("price", price).put("area", 27);
    }

    static JSONObject entry(long interest, String id, long seen, JSONObject... points) throws Exception {
        return new JSONObject().put("interest_id", interest).put("listing_id", id)
                .put("url", PropertyPageClient.buildListingUrl(id))
                .put("title", "Go Portugal").put("first_seen_at", 100)
                .put("last_seen_at", seen).put("first_publication_at", 50)
                .put("identity_validation_version", 1).put("validation_status", "available")
                .put("points", new JSONArray(points));
    }

    @Test public void mergesSamsungAndMotorolaBothWaysWithoutLosingOriginalDates() throws Exception {
        JSONArray samsung = new JSONArray().put(entry(1, "123", 200, point(100, 414000), point(200, 399000)));
        JSONArray motorola = new JSONArray().put(entry(1, "123", 300, point(300, 399000)));
        JSONArray combined = PropertyHistorySync.merge(samsung, motorola);
        assertEquals(PropertyHistorySync.canonical(combined),
                PropertyHistorySync.canonical(PropertyHistorySync.merge(motorola, samsung)));
        JSONArray points = combined.getJSONObject(0).getJSONArray("points");
        assertEquals(3, points.length());
        assertEquals(100, points.getJSONObject(0).getLong("observed_at"));
        assertEquals(414000, points.getJSONObject(0).getDouble("price"), 0);
        assertEquals(50, combined.getJSONObject(0).getLong("first_publication_at"));
        String expected = PropertyHistorySync.canonical(combined);
        for (int i = 0; i < 20; i++) combined = PropertyHistorySync.merge(combined, samsung);
        assertEquals(expected, PropertyHistorySync.canonical(combined));
    }

    @Test public void keepsDifferentProvidersAndCodesSeparateButMergesDifferentAlertIds() throws Exception {
        JSONObject loft = entry(1, "123", 300, point(300, 300000))
                .put("url", "https://loft.com.br/imovel/123");
        JSONArray merged = PropertyHistorySync.merge(new JSONArray().put(entry(1, "123", 100, point(100, 414000))),
                new JSONArray().put(entry(2, "123", 200, point(200, 399000)))
                        .put(entry(1, "456", 200, point(200, 200000))).put(loft));
        assertEquals(4, merged.length());
        int shared = 0;
        for (int i = 0; i < merged.length(); i++) {
            JSONObject item = merged.getJSONObject(i);
            if (item.getJSONArray("points").length() == 2) shared++;
        }
        assertEquals(2, shared);
    }

    @Test public void unavailableQuarantineCannotBeUndoneByOldDeviceOrRepeatedSync() throws Exception {
        JSONObject old = entry(1, "123", 200, point(100, 520000), point(200, 320000));
        old.remove("identity_validation_version");
        JSONObject blocked = new JSONObject(old.toString());
        PropertyHistoryRepository.quarantineLegacyHistory(blocked);
        blocked.put("validation_status", "unavailable").put("last_seen_at", 300);
        // Even if an old device polls later, its unverified data cannot restore availability.
        old.put("last_seen_at", 400);
        JSONArray merged = PropertyHistorySync.merge(new JSONArray().put(old), new JSONArray().put(blocked));
        JSONObject item = merged.getJSONObject(0);
        assertEquals("unavailable", item.getString("validation_status"));
        assertEquals(0, item.getJSONArray("points").length());
        assertEquals(2, item.getJSONArray("sync_quarantined_points").length());
        assertEquals(PropertyHistorySync.canonical(merged), PropertyHistorySync.canonical(
                PropertyHistorySync.merge(merged, new JSONArray().put(old))));
        assertEquals(PropertyHistorySync.canonical(merged), PropertyHistorySync.canonical(
                PropertyHistorySync.merge(new JSONArray().put(blocked), new JSONArray().put(old))));
    }

    @Test public void compatibleLegacyGoPortugalRemainsVisible() throws Exception {
        JSONObject old = entry(1, "123", 200, point(100, 414000), point(200, 399000));
        old.remove("identity_validation_version");
        JSONArray merged = PropertyHistorySync.merge(new JSONArray().put(old),
                new JSONArray().put(entry(1, "123", 300, point(300, 399000))));
        assertEquals(3, merged.getJSONObject(0).getJSONArray("points").length());
        assertEquals(PropertyHistorySync.canonical(merged), PropertyHistorySync.canonical(
                PropertyHistorySync.merge(merged, new JSONArray().put(old))));
    }

    @Test public void conflictingSameInstantIsQuarantinedAndNotReintroduced() throws Exception {
        JSONArray first = new JSONArray().put(entry(1, "123", 100, point(100, 520000)));
        JSONArray second = new JSONArray().put(entry(1, "123", 100, point(100, 320000)));
        JSONArray merged = PropertyHistorySync.merge(first, second);
        assertEquals(0, merged.getJSONObject(0).getJSONArray("points").length());
        assertEquals(2, merged.getJSONObject(0).getJSONArray("sync_quarantined_points").length());
        assertEquals(PropertyHistorySync.canonical(merged), PropertyHistorySync.canonical(
                PropertyHistorySync.merge(merged, first)));
    }

    @Test public void ignoresMalformedAndWrongIdentityRemoteRecords() throws Exception {
        JSONArray valid = new JSONArray().put(entry(1, "123", 100, point(100, 399000)));
        JSONArray remote = new JSONArray().put(entry(1, "123", 200, point(200, 100000))
                .put("url", PropertyPageClient.buildListingUrl("456"))).put("bad").put(JSONObject.NULL);
        assertEquals(PropertyHistorySync.canonical(PropertyHistorySync.merge(valid, new JSONArray())),
                PropertyHistorySync.canonical(PropertyHistorySync.merge(valid, remote)));
    }

    @Test public void restoredRepositoryShowsDropAndImportDoesNotTriggerUploadLoop() throws Exception {
        Map<String, String> samsung = new HashMap<>();
        Map<String, String> motorola = new HashMap<>();
        samsung.put("entries", new JSONArray().put(entry(1, "123", 200,
                point(100, 414000), point(200, 399000))).toString());
        PropertyHistoryRepository source = new PropertyHistoryRepository(PropertyHistoryRepositoryTest.memoryPreferences(samsung));
        PropertyHistoryRepository target = new PropertyHistoryRepository(PropertyHistoryRepositoryTest.memoryPreferences(motorola));
        assertTrue(target.mergeFromSync(source.exportForSync()));
        assertFalse(target.mergeFromSync(source.exportForSync()));
        ObservedOffer offer = new ObservedOffer("property|99|123", 99, "Renamed", "", 399000, 500000,
                300, PropertyPageClient.buildListingUrl("123"), "");
        PropertyHistoryEntry history = target.getForOffer(offer);
        assertNotNull(history);
        assertEquals(15000, history.getPriceDropAmount(), 0);
        assertEquals(3.623, history.getPriceDropPercentage(), .001);
        assertFalse(Boolean.parseBoolean(motorola.get("sync_dirty")));
        assertFalse(target.mergeFromSync(new JSONArray()));
        assertEquals(2, target.getForOffer(offer).getPoints().size());
    }

    @Test public void repeatedReadsDoNotRequestNewBackupButPriceChangeDoes() throws Exception {
        Map<String, String> stored = new HashMap<>();
        PropertyHistoryRepository repository = new PropertyHistoryRepository(PropertyHistoryRepositoryTest.memoryPreferences(stored));
        PropertyPageListing listing = new PropertyPageListing("123", 27, 399000, "Go",
                PropertyPageClient.buildListingUrl("123"));
        PropertyListingMetadata metadata = PropertyListingMetadata.verified("123", 399000, 27, 50, 50);
        repository.recordObservation(1, listing, 100, metadata);
        assertEquals("true", stored.get("sync_dirty"));
        stored.put("sync_dirty", "false");
        String before = PropertyHistorySync.contentKey(repository.exportForSync());
        repository.recordObservation(1, listing, 200, metadata);
        assertEquals("false", stored.get("sync_dirty"));
        assertEquals(before, PropertyHistorySync.contentKey(repository.exportForSync()));
        PropertyPageListing cheaper = new PropertyPageListing("123", 27, 390000, "Go", listing.getUrl());
        repository.recordObservation(1, cheaper, 300, PropertyListingMetadata.verified("123", 390000, 27, 50, 50));
        assertEquals("true", stored.get("sync_dirty"));
    }

    @Test public void compactBackupStoresHistoryOnceAndRoundTripsWithoutResyncLoop() throws Exception {
        JSONArray source = new JSONArray();
        for (int i = 1; i <= 3; i++) source.put(entry(i, "123", 200, point(100, 414000), point(200, 399000)));
        JSONArray normalized = PropertyHistorySync.merge(source, new JSONArray());
        JSONObject packed = PropertyHistorySync.pack(normalized);
        assertEquals(1, packed.getJSONArray("rows").length());
        assertEquals(3, packed.getJSONArray("rows").getJSONObject(0).getJSONArray("alerts").length());
        assertTrue(packed.toString().length() < normalized.toString().length() / 2);
        assertEquals(PropertyHistorySync.contentKey(normalized),
                PropertyHistorySync.contentKey(PropertyHistorySync.unpack(packed)));
        Map<String, String> storage = new HashMap<>();
        PropertyHistoryRepository repository = new PropertyHistoryRepository(PropertyHistoryRepositoryTest.memoryPreferences(storage));
        assertTrue(repository.mergeFromSync(PropertyHistorySync.unpack(packed)));
        for (int i = 0; i < 100; i++) {
            assertFalse(repository.mergeFromSync(PropertyHistorySync.unpack(packed)));
            assertEquals(PropertyHistorySync.contentKey(normalized),
                    PropertyHistorySync.contentKey(repository.exportForSync()));
        }
        assertFalse(Boolean.parseBoolean(storage.get("sync_dirty")));
    }
}
