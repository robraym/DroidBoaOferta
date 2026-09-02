package br.com.droidboaoferta;

final class PropertyListingMetadata {
    private final long firstPublicationAt;
    private final long lastPublicationAt;
    private final double salePrice;

    PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt) {
        this(firstPublicationAt, lastPublicationAt, Double.NaN);
    }

    PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt,
                            double salePrice) {
        this.firstPublicationAt = firstPublicationAt;
        this.lastPublicationAt = lastPublicationAt;
        this.salePrice = salePrice;
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

    double getSalePrice() {
        return salePrice;
    }
}
