package br.com.droidboaoferta;

final class PropertyPageListing {
    private final String id;
    private final double area;
    private final double salePrice;
    private final String description;
    private final String url;
    private final boolean newAd;

    PropertyPageListing(String id, double area, double salePrice,
                        String description, String url) {
        this(id, area, salePrice, description, url, false);
    }

    PropertyPageListing(String id, double area, double salePrice,
                        String description, String url, boolean newAd) {
        this.id = id == null ? "" : id.trim();
        this.area = area;
        this.salePrice = salePrice;
        this.description = description == null ? "" : description.trim();
        this.url = url == null ? "" : url.trim();
        this.newAd = newAd;
    }

    String getId() {
        return id;
    }

    double getArea() {
        return area;
    }

    double getSalePrice() {
        return salePrice;
    }

    String getDescription() {
        return description;
    }

    String getUrl() {
        return url;
    }

    boolean isNewAd() {
        return newAd;
    }

    boolean matches(double minimumArea, double maximumArea, double maximumPrice) {
        return area >= minimumArea
                && area <= maximumArea
                && salePrice <= maximumPrice;
    }
}
