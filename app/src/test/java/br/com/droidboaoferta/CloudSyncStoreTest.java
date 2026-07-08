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
