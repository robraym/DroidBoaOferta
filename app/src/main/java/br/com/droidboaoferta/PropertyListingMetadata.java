package br.com.droidboaoferta;

final class PropertyListingMetadata {
    private final long firstPublicationAt;
    private final long lastPublicationAt;

    PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt) {
        this.firstPublicationAt = firstPublicationAt;
        this.lastPublicationAt = lastPublicationAt;
    }

    static PropertyListingMetadata empty() {
        return new PropertyListingMetadata(0L, 0L);
    }

    long getFirstPublicationAt() {
        return firstPublicationAt;
    }

    long getLastPublicationAt() {
        return lastPublicationAt;
    }
}
