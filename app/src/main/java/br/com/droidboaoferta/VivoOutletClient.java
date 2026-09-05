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

final class VivoOutletClient {
    private static final String API_BASE = "https://api.store.vivo.com.br/occ/v2/vivo/products/search";
    private static final String STORE_BASE = "https://store.vivo.com.br";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 3;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final double PIX_DISCOUNT = 0.95d;

    private VivoOutletClient() {
    }

    static List<VivoOutletProduct> fetchProducts(String sourceUrl) throws Exception {
        String categoryCode = VivoOutletSource.getCategoryCode(sourceUrl);
        if (categoryCode == null) {
            throw new IllegalArgumentException("Unsupported Vivo outlet URL");
        }
        List<VivoOutletProduct> products = new ArrayList<>();
        int totalPages = 1;
        for (int page = 0; page < totalPages && page < MAX_PAGES; page++) {
            JSONObject response = new JSONObject(fetchPage(categoryCode, page));
            totalPages = Math.max(1, response.optJSONObject("pagination")
                    .optInt("totalPages", 1));
            JSONArray entries = response.optJSONArray("products");
            if (entries == null) {
                continue;
            }
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String code = entry.optString("code", "").trim();
                String name = entry.optString("name", "").trim();
                String path = entry.optString("url", "").trim();
                double listedPrice = entry.optJSONObject("price").optDouble("value", Double.NaN);
                if (code.isEmpty() || name.isEmpty() || path.isEmpty()
                        || Double.isNaN(listedPrice) || listedPrice <= 0d) {
                    continue;
                }
                products.add(new VivoOutletProduct(
                        code,
                        name,
                        roundCurrency(listedPrice * PIX_DISCOUNT),
                        path.startsWith("http") ? path : STORE_BASE + path
                ));
            }
        }
        return products;
    }

    private static String fetchPage(String categoryCode, int page) throws Exception {
        String query = ":relevance:allCategories:" + categoryCode;
        String url = API_BASE
                + "?query=" + URLEncoder.encode(query, "UTF-8")
                + "&fields=FULL&pageSize=" + PAGE_SIZE + "&currentPage=" + page;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Alertou/1.0 Android");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return readResponse(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Vivo response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static double roundCurrency(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
