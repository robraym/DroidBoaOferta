package br.com.droidboaoferta;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PropertyHistoryRepositoryTest {
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
    }
}
