package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupPromotionExpiryRepositoryTest {
    @Test
    public void closingPromotionRemovesSelectedPublicationAndLaterOnes() {
        assertFalse(GroupPromotionExpiryRepository.excludesFromRanking(1_000L, 0L, 999L));
        assertTrue(GroupPromotionExpiryRepository.excludesFromRanking(1_000L, 0L, 1_000L));
        assertTrue(GroupPromotionExpiryRepository.excludesFromRanking(1_000L, 0L, 1_001L));
    }

    @Test
    public void resumingPromotionAllowsNewPublications() {
        assertTrue(GroupPromotionExpiryRepository.excludesFromRanking(1_000L, 2_000L, 1_500L));
        assertFalse(GroupPromotionExpiryRepository.excludesFromRanking(1_000L, 2_000L, 2_000L));
    }
}
