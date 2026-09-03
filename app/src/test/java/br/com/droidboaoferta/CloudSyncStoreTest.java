package br.com.droidboaoferta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CloudSyncStoreTest {
    @Test
    public void eighteenBackupPartsTriggerOneRefreshNotEighteen() throws Exception {
        int refreshes = 0;
        for (int index = 1; index <= 18; index++) {
            JSONObject part = rawMessage(index, chunk("current", 300, index, 18, "payload"));
            if (CloudSyncStore.shouldRefreshForBackupMessage(part)) refreshes++;
        }
        assertEquals(1, refreshes);
        org.junit.Assert.assertTrue(CloudSyncStore.shouldRefreshForBackupMessage(
                rawMessage(1, chunk("single", 300, 1, 1, "payload"))));
        org.junit.Assert.assertTrue(CloudSyncStore.shouldRefreshForBackupMessage(
                message(1, backup(300, true, "[]", 0).toString())));
    }

    @Test
    public void mergesHistoryFromOlderSnapshotWithoutReplacingNewerConfiguration() throws Exception {
        JSONObject older = backup(100L, true, "[{\"id\":1}]", 1);
        older.getJSONObject("data").put("property_history", PropertyHistorySync.pack(new JSONArray().put(
                PropertyHistorySyncTest.entry(1, "123", 200, PropertyHistorySyncTest.point(100, 414000),
                        PropertyHistorySyncTest.point(200, 399000)))));
        JSONObject newer = backup(200L, true, "[{\"id\":2}]", 1);
        newer.getJSONObject("data").put("property_history", PropertyHistorySync.pack(new JSONArray().put(
                PropertyHistorySyncTest.entry(1, "123", 300, PropertyHistorySyncTest.point(300, 399000)))));
        JSONObject selected = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(message(1, older.toString())).put(message(2, newer.toString())));
        assertEquals(200, selected.getLong("updated_at"));
        assertEquals("[{\"id\":2}]", selected.getJSONObject("data").getString("interests"));
        assertEquals(3, CloudSyncStore.propertyHistoryFromBackup(selected).getJSONObject(0).getJSONArray("points").length());
        org.junit.Assert.assertTrue(selected.getBoolean("_property_history_combined"));
        // After the union is published, seeing the older backups cannot request another union.
        selected.remove("_property_history_combined");
        selected.put("updated_at", 400);
        JSONObject secondPull = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(message(1, older.toString())).put(message(2, newer.toString()))
                .put(message(3, selected.toString())));
        org.junit.Assert.assertFalse(secondPull.optBoolean("_property_history_combined"));
    }

    @Test
    public void chunkedPropertyHistoryRoundTripPreservesOriginalReadings() throws Exception {
        JSONObject source = backup(300, true, "[{\"id\":1}]", 1);
        source.getJSONObject("data").put("property_history", PropertyHistorySync.pack(new JSONArray().put(
                PropertyHistorySyncTest.entry(1, "123", 200, PropertyHistorySyncTest.point(100, 414000),
                        PropertyHistorySyncTest.point(200, 399000)))));
        String payload = source.toString();
        int middle = payload.length() / 2;
        JSONObject restored = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(rawMessage(2, chunk("history", 300, 2, 2, payload.substring(middle))))
                .put(rawMessage(1, chunk("history", 300, 1, 2, payload.substring(0, middle)))));
        assertEquals(PropertyHistorySync.contentKey(CloudSyncStore.propertyHistoryFromBackup(source)),
                PropertyHistorySync.contentKey(CloudSyncStore.propertyHistoryFromBackup(restored)));
        org.junit.Assert.assertFalse(restored.optBoolean("_property_history_combined"));
    }

    @Test
    public void newestCompleteBackupWinsEvenWhenItIsIntentionallyEmpty() throws Exception {
        JSONObject olderRich = backup(100L, false, "[{\"id\":1}]", 1);
        JSONObject newerEmpty = backup(200L, true, "[]", 0);

        JSONObject selected = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(message(1L, olderRich.toString()))
                .put(message(2L, newerEmpty.toString())));

        assertNotNull(selected);
        assertEquals(200L, selected.getLong("updated_at"));
    }

    @Test
    public void manualRestorePrefersCompleteBackupThatHasAlerts() throws Exception {
        JSONObject olderRich = backup(100L, true, "[{\"id\":1}]", 1);
        JSONObject newerEmpty = backup(200L, true, "[]", 0);

        JSONObject selected = CloudSyncStore.findNewestRestorableBackup(new JSONArray()
                .put(message(1L, olderRich.toString()))
                .put(message(2L, newerEmpty.toString())));

        assertNotNull(selected);
        assertEquals(100L, selected.getLong("updated_at"));
    }

    @Test
    public void richerLegacyBackupStillProtectsAgainstLegacyEmptyOverwrite() throws Exception {
        JSONObject olderRich = backup(100L, false, "[{\"id\":1}]", 1);
        JSONObject newerEmpty = backup(200L, false, "[]", 0);

        JSONObject selected = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(message(1L, olderRich.toString()))
                .put(message(2L, newerEmpty.toString())));

        assertNotNull(selected);
        assertEquals(100L, selected.getLong("updated_at"));
    }

    @Test
    public void incompleteNewerChunkSetDoesNotReplaceCompleteBackup() throws Exception {
        JSONObject complete = backup(100L, true, "[]", 0);
        String partialPayload = backup(200L, true, "[{\"id\":2}]", 1).toString();
        String partialChunk = CloudSyncStore.MARKER + "\n"
                + new JSONObject()
                .put("version", 2)
                .put("backup_id", "200")
                .put("updated_at", 200L)
                .put("chunk", 1)
                .put("total", 2)
                + "\n" + partialPayload.substring(0, partialPayload.length() / 2);

        JSONObject selected = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(message(1L, complete.toString()))
                .put(rawMessage(2L, partialChunk)));

        assertNotNull(selected);
        assertEquals(100L, selected.getLong("updated_at"));
    }

    @Test
    public void completeChunkSetIsReassembledOutOfOrder() throws Exception {
        String payload = backup(300L, true, "[{\"id\":3}]", 1).toString();
        int middle = payload.length() / 2;
        String first = chunk("300", 300L, 1, 2, payload.substring(0, middle));
        String second = chunk("300", 300L, 2, 2, payload.substring(middle));

        JSONObject selected = CloudSyncStore.findNewestBackup(new JSONArray()
                .put(rawMessage(2L, second))
                .put(rawMessage(1L, first)));

        assertNotNull(selected);
        assertEquals(300L, selected.getLong("updated_at"));
        assertEquals(1, selected.getJSONObject("data").getJSONArray("selected_groups").length());
    }

    @Test
    public void retryBackoffIsBounded() {
        assertEquals(5_000L, CloudSyncRetryPolicy.delayForAttempt(1));
        assertEquals(10_000L, CloudSyncRetryPolicy.delayForAttempt(2));
        assertEquals(20_000L, CloudSyncRetryPolicy.delayForAttempt(3));
        assertEquals(40_000L, CloudSyncRetryPolicy.delayForAttempt(4));
        assertEquals(60_000L, CloudSyncRetryPolicy.delayForAttempt(5));
        assertEquals(60_000L, CloudSyncRetryPolicy.delayForAttempt(20));
    }

    @Test
    public void weeklyMergePreservesEveryEarnedStar() throws Exception {
        String local = new JSONArray().put(week(100L,
                standing(10L, 1, 10), standing(20L, 2, 8))).toString();
        String remote = new JSONArray().put(week(100L,
                standing(20L, 1, 10), standing(10L, 2, 8))).toString();

        JSONArray merged = new JSONArray(CloudSyncStore.mergeWeeks(local, remote));
        JSONArray standings = merged.getJSONObject(0).getJSONArray("standings");

        assertEquals(2, standings.length());
        assertEquals(1, findStanding(standings, 10L).getInt("position"));
        assertEquals(1, findStanding(standings, 20L).getInt("position"));
    }

    @Test
    public void weeklyMergeKeepsBestPositionAndHighestPoints() throws Exception {
        String local = new JSONArray().put(week(100L, standing(10L, 3, 6))).toString();
        String remote = new JSONArray().put(week(100L, standing(10L, 2, 8))).toString();

        JSONArray merged = new JSONArray(CloudSyncStore.mergeWeeks(local, remote));
        JSONObject standing = merged.getJSONObject(0).getJSONArray("standings").getJSONObject(0);

        assertEquals(2, standing.getInt("position"));
        assertEquals(8, standing.getInt("points"));
    }

    @Test
    public void removedRankingEventCannotReturnFromOlderSnapshot() throws Exception {
        JSONArray event = new JSONArray().put(10L).put(1_000L).put("echo spot");
        String oldSnapshot = new JSONArray().put(event).toString();
        String removals = new JSONArray().put(event).toString();

        JSONArray result = new JSONArray(CloudSyncStore.removeSpeedEvents(
                oldSnapshot, removals));

        assertEquals(0, result.length());
    }

    @Test
    public void quickInterestDeltaUpdatesPriceWithoutChangingPropertyFilters() throws Exception {
        JSONObject oldProperty = propertyInterest(200L, 506000d, 29d, 31d);
        JSONObject payload = new JSONObject()
                .put("type", "interest")
                .put("interest_id", 200L)
                .put("deleted", false)
                .put("fields", new JSONObject().put("maximum_price", 480000d));

        JSONArray result = CloudSyncStore.applyInterestConfigurationDelta(
                new JSONArray().put(oldProperty).toString(), payload);

        JSONObject restored = result.getJSONObject(0);
        assertEquals(480000d, restored.getDouble("maximum_price"), 0.001d);
        assertEquals(29d, restored.getDouble("minimum_area"), 0.001d);
        assertEquals(31d, restored.getDouble("maximum_area"), 0.001d);
    }

    @Test
    public void partialInterestDeltaCannotCreateAnIncompleteAlert() throws Exception {
        JSONObject payload = new JSONObject()
                .put("type", "interest")
                .put("interest_id", 200L)
                .put("deleted", false)
                .put("fields", new JSONObject().put("maximum_price", 480000d));

        JSONArray result = CloudSyncStore.applyInterestConfigurationDelta("[]", payload);

        assertEquals(0, result.length());
    }

    @Test
    public void quickInterestDeltaContainsOnlyTheChangedPropertyValue() throws Exception {
        Interest previous = new Interest(
                200L, "https://www.quintoandar.com.br/condominio/predio",
                506000d, Interest.TYPE_PROPERTY, 29d, 31d);
        Interest updated = new Interest(
                200L, "https://www.quintoandar.com.br/condominio/predio",
                480000d, Interest.TYPE_PROPERTY, 29d, 31d);

        JSONObject fields = CloudSyncStore.changedInterestFields(previous, updated);

        assertEquals(1, fields.length());
        assertEquals(480000d, fields.getDouble("maximum_price"), 0.001d);
    }

    @Test
    public void quickInterestDeltaSynchronizesOnlyTheCondominiumName() throws Exception {
        Interest previous = new Interest(
                200L, "https://www.quintoandar.com.br/condominio/predio",
                506000d, Interest.TYPE_PROPERTY, 29d, 31d, "");
        Interest updated = new Interest(
                200L, "https://www.quintoandar.com.br/condominio/predio",
                506000d, Interest.TYPE_PROPERTY, 29d, 31d, "VN Frei Caneca");

        JSONObject fields = CloudSyncStore.changedInterestFields(previous, updated);

        assertEquals(1, fields.length());
        assertEquals("VN Frei Caneca", fields.getString("property_name"));
    }

    @Test
    public void quickInterestDeltaUsesOneTelegramOrderPerAlert() throws Exception {
        JSONObject update = new JSONObject()
                .put("type", "interest")
                .put("interest_id", 200L)
                .put("deleted", false);
        JSONObject deletion = new JSONObject(update.toString()).put("deleted", true);
        JSONObject anotherAlert = new JSONObject(update.toString()).put("interest_id", 300L);

        assertEquals("interest:200", CloudSyncStore.configurationDeltaSelectionKey(update));
        assertEquals("interest:200", CloudSyncStore.configurationDeltaSelectionKey(deletion));
        assertEquals("interest:300", CloudSyncStore.configurationDeltaSelectionKey(anotherAlert));
    }

    @Test
    public void quickInterestDeltaDeletesOnlyTheSelectedAlert() throws Exception {
        JSONArray local = new JSONArray()
                .put(propertyInterest(200L, 506000d, 29d, 31d))
                .put(new JSONObject().put("id", 300L).put("type", Interest.TYPE_PRICE));
        JSONObject payload = new JSONObject()
                .put("type", "interest")
                .put("interest_id", 200L)
                .put("deleted", true);

        JSONArray result = CloudSyncStore.applyInterestConfigurationDelta(
                local.toString(), payload);

        assertEquals(1, result.length());
        assertEquals(300L, result.getJSONObject(0).getLong("id"));
    }

    @Test
    public void quickSortDeltaAcceptsOnlyExistingSortOptions() {
        assertEquals(0, CloudSyncStore.normalizeAlertsSortOrder(0));
        assertEquals(1, CloudSyncStore.normalizeAlertsSortOrder(1));
        assertEquals(2, CloudSyncStore.normalizeAlertsSortOrder(2));
        assertEquals(3, CloudSyncStore.normalizeAlertsSortOrder(3));
        assertEquals(0, CloudSyncStore.normalizeAlertsSortOrder(-1));
        assertEquals(0, CloudSyncStore.normalizeAlertsSortOrder(4));
    }

    private JSONObject backup(long updatedAt, boolean complete, String interests,
                              int selectedGroups) throws Exception {
        JSONArray groups = new JSONArray();
        for (int index = 0; index < selectedGroups; index++) {
            groups.put(Long.toString(index + 1L));
        }
        return new JSONObject()
                .put("version", complete ? 2 : 1)
                .put("complete", complete)
                .put("updated_at", updatedAt)
                .put("data", new JSONObject()
                        .put("selected_groups", groups)
                        .put("interests", interests)
                        .put("recent_offers", "[]")
                        .put("archived_offers", "[]")
                        .put("trashed_offers", "[]"));
    }

    private JSONObject week(long startedAt, JSONObject... standings) throws Exception {
        JSONArray values = new JSONArray();
        for (JSONObject standing : standings) values.put(standing);
        return new JSONObject().put("started_at", startedAt).put("standings", values);
    }

    private JSONObject propertyInterest(long id, double price, double minimumArea,
                                        double maximumArea) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("term", "https://www.quintoandar.com.br/condominio/predio")
                .put("maximum_price", price)
                .put("type", Interest.TYPE_PROPERTY)
                .put("minimum_area", minimumArea)
                .put("maximum_area", maximumArea);
    }

    private JSONObject standing(long chatId, int position, int points) throws Exception {
        return new JSONObject().put("chat_id", chatId)
                .put("position", position).put("points", points);
    }

    private JSONObject findStanding(JSONArray standings, long chatId) throws Exception {
        for (int index = 0; index < standings.length(); index++) {
            JSONObject item = standings.getJSONObject(index);
            if (item.getLong("chat_id") == chatId) return item;
        }
        throw new AssertionError("Standing not found for " + chatId);
    }

    private JSONObject message(long id, String backup) throws Exception {
        return rawMessage(id, CloudSyncStore.MARKER + "\n" + backup);
    }

    private JSONObject rawMessage(long id, String text) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("content", new JSONObject()
                        .put("@type", "messageText")
                        .put("text", new JSONObject().put("text", text)));
    }

    private String chunk(String backupId, long updatedAt, int index, int total,
                         String payload) throws Exception {
        return CloudSyncStore.MARKER + "\n"
                + new JSONObject()
                .put("version", 2)
                .put("backup_id", backupId)
                .put("updated_at", updatedAt)
                .put("chunk", index)
                .put("total", total)
                + "\n" + payload;
    }
}
