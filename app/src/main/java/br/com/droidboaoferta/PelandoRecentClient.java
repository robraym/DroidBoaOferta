package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PelandoRecentClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Pattern FEED_SCHEMA = Pattern.compile(
            "<script[^>]*id=[\"']feed-schema[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PRICE_IN_TITLE = Pattern.compile(
            "Por\\s+R\\$\\s*([0-9.]+,[0-9]{2})",
            Pattern.CASE_INSENSITIVE
    );

    private PelandoRecentClient() {
    }

    static RecentResult fetchRecent(String sourceUrl, String lastModified, boolean force)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html");
        connection.setRequestProperty("User-Agent", "Alertou/1.0 Android");
        if (!force && lastModified != null && !lastModified.trim().isEmpty()) {
            connection.setRequestProperty("If-Modified-Since", lastModified.trim());
        }
        try {
            int status = connection.getResponseCode();
            String responseLastModified = connection.getHeaderField("Last-Modified");
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return RecentResult.notModified(responseLastModified);
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return RecentResult.changed(parseRecentDeals(readResponse(connection.getInputStream())),
                    responseLastModified);
        } finally {
            connection.disconnect();
        }
    }

    static PelandoDeal fetchDealDetails(String title, String link) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
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
            String html = readResponse(connection.getInputStream());
            double price = parsePrice(html);
            String id = extractDealId(link);
            if (id == null || Double.isNaN(price) || price <= 0d) {
                throw new IllegalStateException("Invalid Pelando deal");
            }
            return new PelandoDeal(id, title, link, price);
        } finally {
            connection.disconnect();
        }
    }

    private static List<PelandoDeal> parseRecentDeals(String html) throws Exception {
        Matcher matcher = FEED_SCHEMA.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Pelando feed schema not found");
        }
        JSONObject schema = new JSONObject(matcher.group(1));
        JSONObject mainEntity = schema.optJSONObject("mainEntity");
        JSONArray entries = mainEntity == null ? null : mainEntity.optJSONArray("hasPart");
        List<PelandoDeal> deals = new ArrayList<>();
        if (entries == null) {
            return deals;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String link = entry.optString("url", "").trim();
            String title = entry.optString("name", "").trim();
            String id = extractDealId(link);
            if (id == null || title.isEmpty()) {
                continue;
            }
            deals.add(new PelandoDeal(id, title, link, Double.NaN));
        }
        return deals;
    }

    private static double parsePrice(String html) {
        Matcher matcher = PRICE_IN_TITLE.matcher(html);
        if (!matcher.find()) {
            return Double.NaN;
        }
        String normalized = matcher.group(1).replace(".", "").replace(",", ".");
        try {
            return Math.round(Double.parseDouble(normalized) * 100d) / 100d;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static String extractDealId(String link) {
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(link.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (!"www.pelando.com.br".equals(host) && !"pelando.com.br".equals(host)) {
                return null;
            }
            String[] parts = path.split("/");
            if (parts.length < 3 || !"d".equals(parts[1])) {
                return null;
            }
            return parts[2].toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
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
                    throw new IllegalStateException("Pelando response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static final class RecentResult {
        private final boolean changed;
        private final List<PelandoDeal> deals;
        private final String lastModified;

        private RecentResult(boolean changed, List<PelandoDeal> deals, String lastModified) {
            this.changed = changed;
            this.deals = deals;
            this.lastModified = lastModified;
        }

        static RecentResult notModified(String lastModified) {
            return new RecentResult(false, new ArrayList<>(), lastModified);
        }

        static RecentResult changed(List<PelandoDeal> deals, String lastModified) {
            return new RecentResult(true, deals, lastModified);
        }

        boolean isChanged() {
            return changed;
        }

        List<PelandoDeal> getDeals() {
            return deals;
        }

        String getLastModified() {
            return lastModified;
        }
    }
}
