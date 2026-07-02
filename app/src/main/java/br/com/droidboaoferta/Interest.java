package br.com.droidboaoferta;

final class Interest {
    private final long id;
    private final String term;
    private final double maximumPrice;

    Interest(long id, String term, double maximumPrice) {
        this.id = id;
        this.term = term;
        this.maximumPrice = maximumPrice;
    }

    long getId() {
        return id;
    }

    String getTerm() {
        return term;
    }

    double getMaximumPrice() {
        return maximumPrice;
    }
}
