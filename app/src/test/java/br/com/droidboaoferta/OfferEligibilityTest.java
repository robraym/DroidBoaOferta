package br.com.droidboaoferta;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OfferEligibilityTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void acceptsRecentOfferWithWebLink() {
        assertTrue(OfferEligibility.isRecent(
                NOW - 30L * 24L * 60L * 60L * 1000L,
                NOW
        ));
        assertTrue(OfferEligibility.hasUsableLink("https://loja.example/s24-ultra"));
    }

    @Test
    public void rejectsOfferOlderThanNinetyDays() {
        assertFalse(OfferEligibility.isRecent(
                NOW - 91L * 24L * 60L * 60L * 1000L,
                NOW
        ));
    }

    @Test
    public void rejectsOfferWithoutWebLink() {
        assertFalse(OfferEligibility.hasUsableLink(""));
        assertFalse(OfferEligibility.hasUsableLink(null));
    }
}
