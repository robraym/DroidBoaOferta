package br.com.droidboaoferta;

import java.util.Collections;
import java.util.List;

final class PropertyHistoryEntry {
    private final long interestId;
    private final String listingId;
    private final String title;
    private final String url;
    private final long firstSeenAt;
    private final long lastSeenAt;
    private final long firstPublicationAt;
    private final boolean newAd;
    private final List<PropertyHistoryPoint> points;
    private final String validationStatus;
    private final boolean hasUnverifiedHistory;

    PropertyHistoryEntry(long interestId, String listingId, String title, String url,
                         long firstSeenAt, long lastSeenAt, long firstPublicationAt,
                         boolean newAd, List<PropertyHistoryPoint> points) {
        this(interestId, listingId, title, url, firstSeenAt, lastSeenAt, firstPublicationAt,
                newAd, points, "available", false);
    }

    PropertyHistoryEntry(long interestId, String listingId, String title, String url,
                         long firstSeenAt, long lastSeenAt, long firstPublicationAt,
                         boolean newAd, List<PropertyHistoryPoint> points,
                         String validationStatus, boolean hasUnverifiedHistory) {
        this.interestId = interestId;
        this.listingId = listingId;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.firstPublicationAt = firstPublicationAt;
        this.newAd = newAd;
        this.points = points == null ? Collections.emptyList() : Collections.unmodifiableList(points);
        this.validationStatus = validationStatus;
        this.hasUnverifiedHistory = hasUnverifiedHistory;
    }

    long getInterestId() { return interestId; }
    String getListingId() { return listingId; }
    String getTitle() { return title; }
    String getUrl() { return url; }
    long getFirstSeenAt() { return firstSeenAt; }
    long getLastSeenAt() { return lastSeenAt; }
    long getFirstPublicationAt() { return firstPublicationAt; }
    boolean isNewAd() { return newAd; }
    List<PropertyHistoryPoint> getPoints() { return points; }
    boolean isUnavailable() { return "unavailable".equals(validationStatus); }
    boolean isPendingValidation() { return "pending".equals(validationStatus); }
    boolean hasUnverifiedHistory() { return hasUnverifiedHistory; }
    double getLatestPrice(double fallback) {
        return points.isEmpty() ? fallback : points.get(points.size() - 1).getPrice();
    }

    boolean hasPriceDrop() {
        return getPriceDropAmount() > 0d;
    }

    double getPriceDropAmount() {
        if (isUnavailable() || isPendingValidation()) return 0d;
        for (int index = points.size() - 1; index > 0; index--) {
            double previousPrice = points.get(index - 1).getPrice();
            double currentPrice = points.get(index).getPrice();
            if (Double.compare(previousPrice, currentPrice) != 0) {
                return currentPrice < previousPrice ? previousPrice - currentPrice : 0d;
            }
        }
        return 0d;
    }

    double getPriceDropPercentage() {
        if (isUnavailable() || isPendingValidation()) return 0d;
        for (int index = points.size() - 1; index > 0; index--) {
            double previousPrice = points.get(index - 1).getPrice();
            double currentPrice = points.get(index).getPrice();
            if (Double.compare(previousPrice, currentPrice) != 0) {
                return currentPrice < previousPrice && previousPrice > 0d
                        ? (previousPrice - currentPrice) * 100d / previousPrice
                        : 0d;
            }
        }
        return 0d;
    }

    boolean isRecent(long now) {
        if (isUnavailable() || isPendingValidation()) return false;
        long reference = firstPublicationAt > 0L ? firstPublicationAt : firstSeenAt;
        return newAd && reference > 0L && now - reference <= 7L * 24L * 60L * 60L * 1000L;
    }
}
