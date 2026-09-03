package br.com.droidboaoferta;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PropertyHistoryRepositoryTest {
    @Test
    public void persistsMigrationAndRebuildsOnlyFromVerifiedObservations() throws Exception {
        java.util.Map<String, String> stored = new java.util.HashMap<>();
        String url = PropertyPageClient.buildListingUrl("118413857", true);
        JSONObject old = new JSONObject().put("interest_id", 1).put("listing_id", "118413857")
                .put("url", url).put("points", new org.json.JSONArray()
                        .put(new JSONObject().put("price", 520000).put("area", 40).put("observed_at", 1))
                        .put(new JSONObject().put("price", 320000).put("area", 40).put("observed_at", 2)));
        stored.put("entries", new org.json.JSONArray().put(old).toString());
        android.content.SharedPreferences prefs = memoryPreferences(stored);
        PropertyHistoryRepository repository = new PropertyHistoryRepository(prefs);
        ObservedOffer offer = new ObservedOffer("property|1|118413857", 1, "Princes", "QuintoAndar",
                320000, 600000, 2, url, "");
        PropertyHistoryEntry pending = repository.getForOffer(offer);
        assertTrue(pending.isPendingValidation());
        assertTrue(pending.hasUnverifiedHistory());
        assertTrue(pending.getPoints().isEmpty());
        assertFalse(pending.hasPriceDrop());
        assertEquals(1, repository.getTrackedListings(1).size());
        PropertyPageListing listing = repository.getTrackedListings(1).get(0);
        assertEquals("118413857", listing.getId());
        assertEquals(PropertyHistoryRepository.UNCHANGED,
                repository.recordObservation(1, listing, 3, PropertyListingMetadata.empty()));
        repository.markUnavailable(1, listing, 4);
        assertTrue(new PropertyHistoryRepository(prefs).getForOffer(offer).isUnavailable());

        PropertyListingMetadata verified = PropertyListingMetadata.verified("118413857", 520000, 40, 0, 0);
        PropertyPageListing available = PropertyPageMonitor.resolveCurrentListing(listing, verified);
        repository.recordObservation(1, available, 5, verified);
        PropertyHistoryEntry rebuilt = new PropertyHistoryRepository(prefs).getForOffer(offer);
        assertFalse(rebuilt.isUnavailable());
        assertFalse(rebuilt.isPendingValidation());
        assertFalse(rebuilt.hasPriceDrop());
        assertEquals(1, rebuilt.getPoints().size());
        assertEquals(520000d, rebuilt.getLatestPrice(0d), 0d);
        assertEquals(PropertyHistoryRepository.UNCHANGED,
                repository.recordObservation(1, available, 6, verified));
        PropertyListingMetadata reduction = PropertyListingMetadata.verified("118413857", 500000, 40, 0, 0);
        repository.recordObservation(1, PropertyPageMonitor.resolveCurrentListing(listing, reduction), 7, reduction);
        assertEquals(20000d, repository.getForOffer(offer).getPriceDropAmount(), 0d);
        assertEquals(2, new org.json.JSONArray(stored.get("entries")).getJSONObject(0)
                .getJSONObject("legacy_unverified").getJSONArray("points").length());
    }

    @Test
    public void newVerifiedHistoryIsNotQuarantinedOnNextRead() {
        PropertyHistoryRepository repository = new PropertyHistoryRepository(memoryPreferences(new java.util.HashMap<>()));
        PropertyPageListing listing = new PropertyPageListing("123", 27, 399000, "",
                PropertyPageClient.buildListingUrl("123"));
        PropertyListingMetadata metadata = PropertyListingMetadata.verified("123", 399000, 27, 0, 0);
        assertEquals(PropertyHistoryRepository.CREATED, repository.recordObservation(1, listing, 1, metadata));
        ObservedOffer offer = new ObservedOffer("property|1|123", 1, "", "", 399000, 400000, 1, listing.getUrl(), "");
        assertEquals(1, repository.getForOffer(offer).getPoints().size());
        assertFalse(repository.getForOffer(offer).hasUnverifiedHistory());
    }

    private static android.content.SharedPreferences memoryPreferences(java.util.Map<String, String> values) {
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                PropertyHistoryRepositoryTest.class.getClassLoader(),
                new Class<?>[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return values.getOrDefault((String) args[0], (String) args[1]);
                    if ("edit".equals(method.getName())) {
                        java.util.Map<String, String> pending = new java.util.HashMap<>();
                        return java.lang.reflect.Proxy.newProxyInstance(PropertyHistoryRepositoryTest.class.getClassLoader(),
                                new Class<?>[]{android.content.SharedPreferences.Editor.class}, (editor, action, params) -> {
                                    if ("putString".equals(action.getName())) {
                                        pending.put((String) params[0], (String) params[1]);
                                        return editor;
                                    }
                                    if ("apply".equals(action.getName())) { values.putAll(pending); return null; }
                                    throw new UnsupportedOperationException(action.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void quarantinesLegacyOscillationWithoutDeletingOriginalData() throws Exception {
        JSONObject item = new JSONObject().put("url", PropertyPageClient.buildListingUrl("118413857", true))
                .put("listing_id", "118413857").put("first_publication_at", 123L)
                .put("points", new org.json.JSONArray("[{price:520000},{price:320000},{price:520000},{price:320000}]"));
        String original = item.toString();
        assertTrue(PropertyHistoryRepository.quarantineLegacyHistory(item));
        assertEquals(original, item.getJSONObject("legacy_unverified").toString());
        assertEquals(0, item.getJSONArray("points").length());
        assertEquals("pending", item.getString("validation_status"));
        assertEquals(0L, item.getLong("first_publication_at"));
        assertFalse(PropertyHistoryRepository.quarantineLegacyHistory(item));
        assertEquals(original, item.getJSONObject("legacy_unverified").toString());
    }

    @Test
    public void migrationDoesNotAlterLoftOrAlreadyValidatedHistory() throws Exception {
        JSONObject loft = new JSONObject().put("url", "https://loft.com.br/imovel/id")
                .put("points", new org.json.JSONArray("[{price:520000}]"));
        String original = loft.toString();
        assertFalse(PropertyHistoryRepository.quarantineLegacyHistory(loft));
        assertEquals(original, loft.toString());
        JSONObject valid = new JSONObject().put("url", PropertyPageClient.buildListingUrl("123"))
                .put("identity_validation_version", 1);
        assertFalse(PropertyHistoryRepository.quarantineLegacyHistory(valid));
    }

    @Test
    public void unavailableOrPendingHistoryNeverProducesDropBadge() {
        for (String status : Arrays.asList("unavailable", "pending")) {
            PropertyHistoryEntry entry = new PropertyHistoryEntry(1, "123", "", "", 1, 2, 0, true,
                    Arrays.asList(new PropertyHistoryPoint(1, 520000, 40),
                            new PropertyHistoryPoint(2, 320000, 40)), status, true);
            assertFalse(entry.hasPriceDrop());
            assertEquals(0d, entry.getPriceDropPercentage(), 0d);
            assertFalse(entry.isRecent(3));
        }
    }
    @Test
    public void recordsPriceReductionAsNewHistoryPoint() throws Exception {
        JSONObject previous = new JSONObject()
                .put("price", 414000d)
                .put("area", 27d);
        PropertyPageListing updated = new PropertyPageListing(
                "895590942", 27d, 399000d, "Studio", ""
        );

        assertTrue(PropertyHistoryRepository.hasObservationChanged(previous, updated));
    }

    @Test
    public void doesNotDuplicateUnchangedHistoryPoint() throws Exception {
        JSONObject previous = new JSONObject()
                .put("price", 399000d)
                .put("area", 27d);
        PropertyPageListing unchanged = new PropertyPageListing(
                "895590942", 27d, 399000d, "Studio", ""
        );

        assertFalse(PropertyHistoryRepository.hasObservationChanged(previous, unchanged));
    }

    @Test
    public void identifiesPriceDropForDashboardBadge() {
        PropertyHistoryEntry entry = new PropertyHistoryEntry(
                1L, "895590942", "Studio", "", 1L, 2L, 0L, false,
                Arrays.asList(
                        new PropertyHistoryPoint(1L, 414000d, 27d),
                        new PropertyHistoryPoint(2L, 399000d, 27d)
                )
        );

        assertTrue(entry.hasPriceDrop());
        assertEquals(15000d, entry.getPriceDropAmount(), 0.001d);
        assertEquals(3.623d, entry.getPriceDropPercentage(), 0.001d);
    }
}
