package br.com.droidboaoferta;

import org.json.JSONArray;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupSpeedRepositoryTest {
    @Test
    public void removedPublicationIsNotRecordedAgain() {
        String removals = new JSONArray()
                .put(new JSONArray().put(10L).put(1_000L).put("echo spot"))
                .toString();

        assertTrue(GroupSpeedRepository.containsRemoval(
                removals, 10L, "echo spot", 1_000L));
        assertFalse(GroupSpeedRepository.containsRemoval(
                removals, 20L, "echo spot", 1_000L));
    }
}
