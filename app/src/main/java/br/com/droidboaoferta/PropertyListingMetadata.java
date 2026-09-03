package br.com.droidboaoferta;

final class PropertyListingMetadata {
    private final long firstPublicationAt;
    private final long lastPublicationAt;
    private final double salePrice;
    private final String listingId;
    private final double area;
    private final boolean unavailable;

    PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt) {
        this(firstPublicationAt, lastPublicationAt, Double.NaN);
    }

    PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt,
                            double salePrice) {
        this(firstPublicationAt, lastPublicationAt, salePrice, "", Double.NaN, false);
    }

    private PropertyListingMetadata(long firstPublicationAt, long lastPublicationAt,
                                    double salePrice, String listingId, double area,
                                    boolean unavailable) {
        this.firstPublicationAt = firstPublicationAt;
        this.lastPublicationAt = lastPublicationAt;
        this.salePrice = salePrice;
        this.listingId = listingId;
        this.area = area;
        this.unavailable = unavailable;
    }

    static PropertyListingMetadata verified(String id, double price, double area,
                                             long firstPublicationAt, long lastPublicationAt) {
        return new PropertyListingMetadata(firstPublicationAt, lastPublicationAt,
                price, id, area, false);
    }

    static PropertyListingMetadata unavailable(String id) {
        return new PropertyListingMetadata(0L, 0L, Double.NaN, id, Double.NaN, true);
    }

    boolean isVerifiedFor(String id) {
        return !unavailable && !listingId.isEmpty() && listingId.equals(id)
                && Double.isFinite(salePrice) && salePrice > 0d
                && Double.isFinite(area) && area > 0d;
    }

    boolean isUnavailableFor(String id) {
        return unavailable && !listingId.isEmpty() && listingId.equals(id);
    }

    double getArea() { return area; }

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
