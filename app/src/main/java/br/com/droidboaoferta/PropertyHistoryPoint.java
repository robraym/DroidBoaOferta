package br.com.droidboaoferta;

final class PropertyHistoryPoint {
    private final long observedAt;
    private final double price;
    private final double area;

    PropertyHistoryPoint(long observedAt, double price, double area) {
        this.observedAt = observedAt;
        this.price = price;
        this.area = area;
    }

    long getObservedAt() {
        return observedAt;
    }

    double getPrice() {
        return price;
    }

    double getArea() {
        return area;
    }
}
