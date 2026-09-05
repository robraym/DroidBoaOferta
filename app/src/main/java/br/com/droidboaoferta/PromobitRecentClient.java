package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PromobitRecentClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private PromobitRecentClient() {
    }

    static List<ExternalProductDeal> fetchRecent(String sourceUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html");
        connection.setRequestProperty("User-Agent", "Alertou/1.0 Android");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return parseRecentDeals(readResponse(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static List<ExternalProductDeal> parseRecentDeals(String html) throws Exception {
        Matcher matcher = NEXT_DATA.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Promobit data not found");
        }
        JSONObject root = new JSONObject(matcher.group(1));
        JSONObject props = root.optJSONObject("props");
        JSONObject pageProps = props == null ? null : props.optJSONObject("pageProps");
        JSONObject serverOffers = pageProps == null ? null : pageProps.optJSONObject("serverOffers");
        JSONArray offers = serverOffers == null ? null : serverOffers.optJSONArray("offers");
        if (offers == null) {
            throw new IllegalStateException("Promobit offers not found");
        }
        List<ExternalProductDeal> deals = new ArrayList<>();
        for (int index = 0; index < offers.length(); index++) {
            JSONObject offer = offers.optJSONObject(index);
            if (offer == null) {
                continue;
            }
            long id = offer.optLong("offerId", 0L);
            String title = offer.optString("offerTitle", "").trim();
            String slug = offer.optString("offerSlug", "").trim();
            double price = offer.optDouble("offerPrice", Double.NaN);
            if (id <= 0L || title.isEmpty() || slug.isEmpty() || Double.isNaN(price) || price <= 0d) {
                continue;
            }
            deals.add(new ExternalProductDeal(
                    String.valueOf(id),
                    title,
                    "https://www.promobit.com.br/oferta/" + slug + "/",
                    Math.round(price * 100d) / 100d
            ));
        }
        return deals;
    }

    private static String readResponse(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Promobit response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
