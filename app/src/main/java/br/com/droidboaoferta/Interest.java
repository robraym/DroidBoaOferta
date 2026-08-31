package br.com.droidboaoferta;

final class Interest {
    static final String TYPE_PRICE = "price";
    static final String TYPE_COUPON = "coupon";
    static final String TYPE_PROPERTY = "property";

    private final long id;
    private final String term;
    private final double maximumPrice;
    private final String type;
    private final double minimumArea;
    private final double maximumArea;
    private final String propertyName;

    Interest(long id, String term, double maximumPrice) {
        this(id, term, maximumPrice, TYPE_PRICE);
    }

    Interest(long id, String term, double maximumPrice, String type) {
        this(id, term, maximumPrice, type, 0d, 0d);
    }

    Interest(long id, String term, double maximumPrice, String type,
             double minimumArea, double maximumArea) {
        this(id, term, maximumPrice, type, minimumArea, maximumArea, "");
    }

    Interest(long id, String term, double maximumPrice, String type,
             double minimumArea, double maximumArea, String propertyName) {
        this.id = id;
        this.term = term;
        this.maximumPrice = maximumPrice;
        this.type = TYPE_COUPON.equals(type)
                ? TYPE_COUPON
                : (TYPE_PROPERTY.equals(type) ? TYPE_PROPERTY : TYPE_PRICE);
        this.minimumArea = minimumArea;
        this.maximumArea = maximumArea;
        this.propertyName = propertyName == null ? "" : propertyName.trim();
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

    boolean isProperty() {
        return TYPE_PROPERTY.equals(type);
    }

    boolean isPrice() {
        return TYPE_PRICE.equals(type);
    }

    double getMinimumArea() {
        return minimumArea;
    }

    double getMaximumArea() {
        return maximumArea;
    }

    String getPropertyName() {
        return propertyName;
    }
}
