package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

public class PropertyPageMonitorTest {
    @Test
    public void neverFallsBackToSummaryAfterFailedOrUnavailableIndividualPage() {
        PropertyPageListing listing = new PropertyPageListing("123", 40, 520000, "",
                PropertyPageClient.buildListingUrl("123", true));
        assertNull(PropertyPageMonitor.resolveCurrentListing(listing, null));
        assertNull(PropertyPageMonitor.resolveCurrentListing(listing, PropertyListingMetadata.empty()));
        assertNull(PropertyPageMonitor.resolveCurrentListing(listing, PropertyListingMetadata.unavailable("123")));
        assertNull(PropertyPageMonitor.resolveCurrentListing(listing,
                PropertyListingMetadata.verified("other", 320000, 53, 0, 0)));
        PropertyPageListing valid = PropertyPageMonitor.resolveCurrentListing(listing,
                PropertyListingMetadata.verified("123", 399000, 42, 0, 0));
        assertEquals(399000, valid.getSalePrice(), 0.001);
        assertEquals(42, valid.getArea(), 0.001);
    }

    @Test
    public void loftStillUsesItsAuthoritativeApiListing() {
        PropertyPageListing listing = new PropertyPageListing("loft-id", 40, 520000, "",
                "https://loft.com.br/imovel/loft-id");
        assertSame(listing, PropertyPageMonitor.resolveCurrentListing(listing, null));
    }

    @Test
    public void invalidListingUrlCannotBypassIdentityValidation() {
        PropertyPageListing listing = new PropertyPageListing("123", 40, 520000, "", "");
        assertNull(PropertyPageMonitor.resolveCurrentListing(listing, null));
    }
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
