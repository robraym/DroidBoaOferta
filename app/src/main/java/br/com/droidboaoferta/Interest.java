package br.com.droidboaoferta;

final class Interest {
    static final String TYPE_PRICE = "price";
    static final String TYPE_COUPON = "coupon";

    private final long id;
    private final String term;
    private final double maximumPrice;
    private final String type;

    Interest(long id, String term, double maximumPrice) {
        this(id, term, maximumPrice, TYPE_PRICE);
    }

    Interest(long id, String term, double maximumPrice, String type) {
        this.id = id;
        this.term = term;
        this.maximumPrice = maximumPrice;
        this.type = TYPE_COUPON.equals(type) ? TYPE_COUPON : TYPE_PRICE;
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

    String getType() {
        return type;
    }

    boolean isCoupon() {
        return TYPE_COUPON.equals(type);
    }
}
