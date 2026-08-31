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

    PropertyHistoryEntry(long interestId, String listingId, String title, String url,
                         long firstSeenAt, long lastSeenAt, long firstPublicationAt,
                         boolean newAd, List<PropertyHistoryPoint> points) {
        this.interestId = interestId;
        this.listingId = listingId;
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.firstPublicationAt = firstPublicationAt;
        this.newAd = newAd;
        this.points = points == null ? Collections.emptyList() : Collections.unmodifiableList(points);
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

    boolean isRecent(long now) {
        long reference = firstPublicationAt > 0L ? firstPublicationAt : firstSeenAt;
        return newAd && reference > 0L && now - reference <= 7L * 24L * 60L * 60L * 1000L;
    }
}
