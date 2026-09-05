package br.com.droidboaoferta;

final class PelandoDeal {
    private final String id;
    private final String title;
    private final String link;
    private final double price;

    PelandoDeal(String id, String title, String link, double price) {
        this.id = id;
        this.title = title;
        this.link = link;
        this.price = price;
    }

    String getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    String getLink() {
        return link;
    }

    double getPrice() {
        return price;
    }
}
