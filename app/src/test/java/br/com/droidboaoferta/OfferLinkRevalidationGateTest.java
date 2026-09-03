package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfferLinkRevalidationGateTest {
    @Test
    public void repeatedRefreshesDoNotRepeatPendingOrSuccessfulChecks() {
        OfferLinkRevalidationGate gate = new OfferLinkRevalidationGate();
        assertTrue(gate.begin("offer", 100L));
        for (int index = 0; index < 1000; index++) assertFalse(gate.begin("offer", 200L));
        gate.finish("offer", true, 300L);
        for (int index = 0; index < 1000; index++) assertFalse(gate.begin("offer", 1_000_000L));
    }

    @Test
    public void failureCannotCreateImmediateRetryLoop() {
        OfferLinkRevalidationGate gate = new OfferLinkRevalidationGate();
        assertTrue(gate.begin("offer", 100L));
        gate.finish("offer", false, 200L);
        for (int index = 0; index < 1000; index++) assertFalse(gate.begin("offer", 201L + index));
        assertFalse(gate.begin("offer", 60_199L));
        assertTrue(gate.begin("offer", 60_200L));
    }

    @Test
    public void otherOffersAndNewConnectionCanStillBeValidated() {
        OfferLinkRevalidationGate gate = new OfferLinkRevalidationGate();
        assertTrue(gate.begin("first", 100L));
        assertTrue(gate.begin("second", 100L));
        gate.finish("first", true, 200L);
        gate.clear();
        assertTrue(gate.begin("first", 300L));
        assertTrue(gate.begin("second", 300L));
    }
}
