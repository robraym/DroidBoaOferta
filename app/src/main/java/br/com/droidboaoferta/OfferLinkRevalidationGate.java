package br.com.droidboaoferta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Limits stored-card retries without weakening validation of new offers. */
final class OfferLinkRevalidationGate {
    private static final long RETRY_DELAY_MS = 60_000L;
    private final Set<String> pending = new HashSet<>();
    private final Set<String> checked = new HashSet<>();
    private final Map<String, Long> retryAfter = new HashMap<>();

    synchronized boolean begin(String id, long now) {
        if (checked.contains(id) || pending.contains(id)
                || now < retryAfter.getOrDefault(id, 0L)) return false;
        pending.add(id);
        return true;
    }

    synchronized void finish(String id, boolean readable, long now) {
        pending.remove(id);
        if (readable) {
            checked.add(id);
            retryAfter.remove(id);
        } else {
            retryAfter.put(id, now + RETRY_DELAY_MS);
        }
    }

    synchronized void clear() {
        pending.clear();
        checked.clear();
        retryAfter.clear();
    }
}
