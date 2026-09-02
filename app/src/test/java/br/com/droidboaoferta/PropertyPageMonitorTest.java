package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PropertyPageMonitorTest {
    @Test
    public void alwaysVerifiesPreviouslyObservedListing() {
        assertTrue(PropertyPageMonitor.shouldVerifyIndividualPrice(
                true, 600000d, 400000d));
    }

    @Test
    public void verifiesNewListingNearAlertLimit() {
        assertTrue(PropertyPageMonitor.shouldVerifyIndividualPrice(
                false, 430000d, 400000d));
    }

    @Test
    public void avoidsFetchingUntrackedListingFarAboveLimit() {
        assertFalse(PropertyPageMonitor.shouldVerifyIndividualPrice(
                false, 600000d, 400000d));
    }
}
