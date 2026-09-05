package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class KabumOfferClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String API_URL =
            "https://servicespub.prod.api.aws.grupokabum.com.br/catalog/v2/brandshowcase"
                    + "?query=oferta-relampago-lista"
                    + "&is_prime=false"
                    + "&payload_data=products_category_filters";

    private KabumOfferClient() {
    }

    static List<ExternalProductDeal> fetchOffers(String sourceUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Origin", "https://www.kabum.com.br");
        connection.setRequestProperty("Referer", sourceUrl);
        connection.setRequestProperty("User-Agent", "Alertou/1.0 Android");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return parseOffers(readResponse(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static List<ExternalProductDeal> parseOffers(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray products = root.optJSONArray("data");
        if (products == null) {
            throw new IllegalStateException("KaBuM offers not found");
        }
        List<ExternalProductDeal> deals = new ArrayList<>();
        for (int index = 0; index < products.length(); index++) {
            JSONObject product = products.optJSONObject(index);
            if (product == null || !"product".equals(product.optString("type"))) {
                continue;
            }
            long id = product.optLong("id", 0L);
            JSONObject attributes = product.optJSONObject("attributes");
            if (id <= 0L || attributes == null || !attributes.optBoolean("available", true)) {
                continue;
            }
            String title = attributes.optString("title", "").trim();
            String friendly = attributes.optString("product_link", "").trim();
            double price = resolvePrice(attributes);
            if (title.isEmpty() || Double.isNaN(price) || price <= 0d) {
                continue;
            }
            deals.add(new ExternalProductDeal(
                    String.valueOf(id),
                    title,
                    createProductUrl(id, friendly),
                    Math.round(price * 100d) / 100d
            ));
        }
        return deals;
    }

    private static double resolvePrice(JSONObject attributes) {
        JSONObject offer = attributes.optJSONObject("offer");
        if (offer != null) {
            double offerPrice = offer.optDouble("price_with_discount", Double.NaN);
            if (!Double.isNaN(offerPrice) && offerPrice > 0d) {
                return offerPrice;
            }
            offerPrice = offer.optDouble("price", Double.NaN);
            if (!Double.isNaN(offerPrice) && offerPrice > 0d) {
                return offerPrice;
            }
        }
        double discounted = attributes.optDouble("price_with_discount", Double.NaN);
        if (!Double.isNaN(discounted) && discounted > 0d) {
            return discounted;
        }
        return attributes.optDouble("price", Double.NaN);
    }

    private static String createProductUrl(long id, String friendly) throws Exception {
        String slug = friendly == null || friendly.trim().isEmpty()
                ? "produto"
                : friendly.toLowerCase(Locale.ROOT);
        return "https://www.kabum.com.br/produto/"
                + id
                + "/"
                + URLEncoder.encode(slug, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String readResponse(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("KaBuM response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
