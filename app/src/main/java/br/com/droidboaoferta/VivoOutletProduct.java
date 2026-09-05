package br.com.droidboaoferta;

final class VivoOutletProduct {
    private final String code;
    private final String name;
    private final double pixPrice;
    private final String link;

    VivoOutletProduct(String code, String name, double pixPrice, String link) {
        this.code = code;
        this.name = name;
        this.pixPrice = pixPrice;
        this.link = link;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    double getPixPrice() {
        return pixPrice;
    }

    String getLink() {
        return link;
    }
}
