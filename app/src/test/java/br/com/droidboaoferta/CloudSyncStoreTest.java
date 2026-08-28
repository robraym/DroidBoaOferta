package br.com.droidboaoferta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CloudSyncStoreTest {
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
