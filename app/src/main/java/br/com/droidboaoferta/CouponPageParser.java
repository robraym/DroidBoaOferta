package br.com.droidboaoferta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CouponPageParser {
    private static final Pattern PUBLIC_COUPON = Pattern.compile(
            "(?i)cupom\\s+de\\s+r\\$\\s*([0-9][0-9.]*?(?:,[0-9]{1,2})?)\\s+de\\s+desconto"
                    + ".{0,300}?use\\s+o\\s+cupom\\s+([a-z0-9_-]{3,40})"
    );

    private CouponPageParser() {
    }

    static List<CouponPageCoupon> parse(String html) {
        if (html == null || html.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        text = Normalizer.normalize(text, Normalizer.Form.NFC);

        List<CouponPageCoupon> coupons = new ArrayList<>();
        Matcher matcher = PUBLIC_COUPON.matcher(text);
        while (matcher.find()) {
            double value = parseBrazilianNumber(matcher.group(1));
            String code = matcher.group(2).toUpperCase(Locale.ROOT);
            if (value > 0d && coupons.stream().noneMatch(item -> item.getCode().equals(code))) {
                coupons.add(new CouponPageCoupon(code, value));
            }
        }
        coupons.sort(Comparator.comparingDouble(CouponPageCoupon::getValue).reversed());
        return coupons;
    }

    static CouponPageCoupon findHighest(String html) {
        List<CouponPageCoupon> coupons = parse(html);
        return coupons.isEmpty() ? null : coupons.get(0);
    }

    private static double parseBrazilianNumber(String value) {
        try {
            return Double.parseDouble(value.replace(".", "").replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }
}
