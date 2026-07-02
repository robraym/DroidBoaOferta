package br.com.droidboaoferta;

final class ObservedOffer {
    private final String interest;
    private final String source;
    private final double price;
    private final double maximumPrice;
    private final long observedAt;
    private final String link;

    ObservedOffer(String interest, String source, double price, double maximumPrice, long observedAt,
                  String link) {
        this.interest = interest;
        this.source = source;
        this.price = price;
        this.maximumPrice = maximumPrice;
        this.observedAt = observedAt;
        this.link = link;
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
}
