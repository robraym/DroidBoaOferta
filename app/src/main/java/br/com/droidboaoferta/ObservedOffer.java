package br.com.droidboaoferta;

final class ObservedOffer {
    private final String id;
    private final long interestId;
    private final String interest;
    private final String source;
    private final double price;
    private final double maximumPrice;
    private final long observedAt;
    private final String link;

    ObservedOffer(String interest, String source, double price, double maximumPrice, long observedAt,
                  String link) {
        this(0L, interest, source, price, maximumPrice, observedAt, link);
    }

    ObservedOffer(long interestId, String interest, String source, double price, double maximumPrice,
                  long observedAt, String link) {
        this(createId(interestId, interest, source, price, maximumPrice, observedAt, link),
                interestId, interest, source, price, maximumPrice, observedAt, link);
    }

    ObservedOffer(String id, String interest, String source, double price, double maximumPrice, long observedAt,
                  String link) {
        this(id, 0L, interest, source, price, maximumPrice, observedAt, link);
    }

    ObservedOffer(String id, long interestId, String interest, String source, double price, double maximumPrice,
                  long observedAt, String link) {
        this.interestId = interestId;
        this.interest = interest;
        this.source = source;
        this.price = price;
        this.maximumPrice = maximumPrice;
        this.observedAt = observedAt;
        this.link = link;
        this.id = id == null || id.trim().isEmpty()
                ? createId(interestId, interest, source, price, maximumPrice, observedAt, link)
                : id;
    }

    String getId() {
        return id;
    }

    long getInterestId() {
        return interestId;
    }

    String getInterest() {
        return interest;
    }

    String getSource() {
        return source;
    }

    double getPrice() {
        return price;
    }

    double getMaximumPrice() {
        return maximumPrice;
    }

    long getObservedAt() {
        return observedAt;
    }

    String getLink() {
        return link;
    }

    private static String createId(long interestId, String interest, String source, double price,
                                   double maximumPrice, long observedAt, String link) {
        return interestId + "|" + interest + "|" + source + "|" + price + "|" + maximumPrice + "|"
                + observedAt + "|" + link;
    }
}
