package br.com.droidboaoferta;

import org.json.JSONObject;
import java.util.Locale;

/** A send-time gate: an old timer or a manual action cannot shorten Telegram's pause. */
final class CloudBackupRetryGate {
    private long notBeforeElapsed;

    void defer(long nowElapsed, long delayMs) {
        notBeforeElapsed = Math.max(notBeforeElapsed, deadline(nowElapsed, delayMs));
    }

    void restore(long deadlineWall, long nowWall, long nowElapsed) {
        defer(nowElapsed, Math.max(0L, deadlineWall - nowWall));
    }

    long remaining(long nowElapsed) {
        return Math.max(0L, notBeforeElapsed - nowElapsed);
    }

    static long deadline(long now, long delay) {
        return now + Math.min(Math.max(0L, delay), Long.MAX_VALUE - now);
    }

    static long telegramDelay(JSONObject error) {
        if (error == null || error.optInt("code") != 429) return 0L;
        String message = error.optString("message", "").toLowerCase(Locale.ROOT);
        java.util.regex.Matcher seconds = java.util.regex.Pattern
                .compile("(?:retry after\\s+|flood_wait_)([0-9]+)").matcher(message);
        if (!seconds.find()) return 60_000L;
        try {
            long value = Long.parseLong(seconds.group(1));
            if (value > Long.MAX_VALUE / 1000L - 1L) return 60_000L;
            return (value + 1L) * 1000L; // Small margin at the server's boundary.
        } catch (NumberFormatException ignored) {
            return 60_000L;
        }
    }

    static String requestTag(long generation, long token) {
        return "cloud_sync_send:" + generation + ":" + token;
    }

    static boolean isCurrentRequest(String tag, long generation, long token) {
        return requestTag(generation, token).equals(tag);
    }

    static int resumeIndex(int nextIndex, int confirmedParts) {
        return Math.max(confirmedParts, nextIndex - 1);
    }
}
