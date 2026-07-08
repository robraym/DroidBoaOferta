package br.com.droidboaoferta;

final class CloudSyncRetryPolicy {
    private CloudSyncRetryPolicy() {
    }

    static long delayForAttempt(int attempt) {
        int exponent = Math.max(0, Math.min(4, attempt - 1));
        return Math.min(60_000L, 5_000L * (1L << exponent));
    }
}
