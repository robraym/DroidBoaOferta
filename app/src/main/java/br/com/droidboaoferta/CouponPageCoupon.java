package br.com.droidboaoferta;

final class CouponPageCoupon {
    private final String code;
    private final double value;

    CouponPageCoupon(String code, double value) {
        this.code = code == null ? "" : code.trim();
        this.value = value;
    }

    String getCode() {
        return code;
    }

    double getValue() {
        return value;
    }
}
