package br.com.droidboaoferta;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudBackupRetryGateTest {
    @Test public void queuedAndManualAttemptsCannotBypassObserved287SecondPause() throws Exception {
        CloudBackupRetryGate gate = new CloudBackupRetryGate();
        long failedAt = 100_000L;
        long delay = CloudBackupRetryGate.telegramDelay(new JSONObject()
                .put("code", 429).put("message", "Too Many Requests: retry after 287"));
        assertEquals(288_000L, delay);
        gate.defer(failedAt, delay);
        // Includes the real regression: a previously queued callback fires one second later.
        assertEquals(287_000L, gate.remaining(failedAt + 1000L));
        int sent = 0;
        for (long now = failedAt; now < failedAt + delay; now += 100L) {
            if (gate.remaining(now) == 0L) sent++;
        }
        assertEquals(0, sent);
        assertEquals(1L, gate.remaining(failedAt + delay - 1));
        assertEquals(0L, gate.remaining(failedAt + delay));
    }

    @Test public void laterShortRetryCannotShortenLongServerPause() {
        CloudBackupRetryGate gate = new CloudBackupRetryGate();
        gate.defer(1000, 288000);
        gate.defer(2000, 5000);
        assertEquals(287000, gate.remaining(2000));
        gate.defer(3000, 500000);
        assertEquals(500000, gate.remaining(3000));
    }

    @Test public void applicationRestartRestoresRemainingPauseAgainstNewElapsedClock() {
        long savedUntil = 1_000_000L + 288_000L;
        CloudBackupRetryGate recreated = new CloudBackupRetryGate();
        recreated.restore(savedUntil, 1_100_000L, 200L);
        assertEquals(188000, recreated.remaining(200));
        assertEquals(0, recreated.remaining(188200));
        // A repeated start call with an older/expired deadline cannot erase the restored gate.
        recreated.restore(1_000_000L, 1_100_000L, 300L);
        assertEquals(187900, recreated.remaining(300));
    }

    @Test public void expiredSavedPauseDoesNotBlockNewBackup() {
        CloudBackupRetryGate gate = new CloudBackupRetryGate();
        gate.restore(1000, 2000, 3000);
        assertEquals(0, gate.remaining(3000));
    }

    @Test public void resumesTenthOfEighteenPartsInsteadOfResendingFirstNine() {
        int nextPart = CloudBackupRetryGate.resumeIndex(10, 9);
        assertEquals(9, nextPart);
        int sends = 0;
        for (int i = nextPart; i < 18; i++) sends++;
        assertEquals(9, sends);
        assertEquals(0, CloudBackupRetryGate.resumeIndex(1, 0));
        assertEquals(17, CloudBackupRetryGate.resumeIndex(18, 17));
    }

    @Test public void staleResponsesAfterCancelOrNewPartAreIgnored() {
        String old = CloudBackupRetryGate.requestTag(10, 50);
        assertTrue(CloudBackupRetryGate.isCurrentRequest(old, 10, 50));
        assertFalse(CloudBackupRetryGate.isCurrentRequest(old, 11, 50));
        assertFalse(CloudBackupRetryGate.isCurrentRequest(old, 10, 51));
        assertFalse(CloudBackupRetryGate.isCurrentRequest("cloud_sync_send", 10, 50));
    }

    @Test public void parsesAlternateAndMissingRateLimitMessagesSafely() throws Exception {
        assertEquals(31000, CloudBackupRetryGate.telegramDelay(new JSONObject().put("code", 429).put("message", "FLOOD_WAIT_30")));
        assertEquals(60000, CloudBackupRetryGate.telegramDelay(new JSONObject().put("code", 429)));
        assertEquals(60000, CloudBackupRetryGate.telegramDelay(new JSONObject().put("code", 429).put("message", "retry after 999999999999999999999999")));
        assertEquals(0, CloudBackupRetryGate.telegramDelay(new JSONObject().put("code", 500).put("message", "retry after 287")));
        assertEquals(0, CloudBackupRetryGate.telegramDelay(null));
        assertEquals(Long.MAX_VALUE, CloudBackupRetryGate.deadline(100, Long.MAX_VALUE));
    }
}
