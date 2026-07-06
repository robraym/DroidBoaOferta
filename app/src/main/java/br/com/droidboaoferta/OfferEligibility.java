package br.com.droidboaoferta;

final class OfferEligibility {
    static final long MAX_HISTORY_AGE_MS = 90L * 24L * 60L * 60L * 1000L;

    private OfferEligibility() {
    }

    static boolean isRecent(long observedAt, long now) {
        return observedAt > 0L
                && observedAt <= now
                && now - observedAt <= MAX_HISTORY_AGE_MS;
    }

    static boolean hasUsableLink(String link) {
        if (link == null) {
            return false;
        }
        String cleanLink = link.trim().toLowerCase(java.util.Locale.ROOT);
        return cleanLink.startsWith("https://") || cleanLink.startsWith("http://");
    }

    static boolean canDisplay(ObservedOffer offer, long now) {
        return offer != null
                && isRecent(offer.getObservedAt(), now)
                && hasUsableLink(offer.getLink());
    }
}
