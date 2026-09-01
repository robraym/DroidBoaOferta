package br.com.droidboaoferta;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PropertyPageClient {
    private static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;

    private PropertyPageClient() {
    }

    static boolean isSupported(String rawUrl) {
        return normalizeSupportedUrl(rawUrl) != null;
    }

    static String normalizeSupportedUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if (!"https".equals(scheme)
                    || !path.startsWith("/condominio/")
                    || path.length() <= "/condominio/".length()) {
                return null;
            }
            if ("quintoandar.com.br".equals(host) || "www.quintoandar.com.br".equals(host)) {
                return "https://www.quintoandar.com.br" + path;
            }
            if ("loft.com.br".equals(host) || "www.loft.com.br".equals(host)) {
                return "https://loft.com.br" + path;
            }
            return null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static PropertyPageResult fetch(String rawUrl) throws Exception {
        String normalizedUrl = normalizeSupportedUrl(rawUrl);
        if (normalizedUrl == null) {
            throw new IllegalArgumentException("Unsupported property page");
        }
        String loftDataUrl = buildLoftDataUrl(normalizedUrl);
        if (loftDataUrl != null) {
            return PropertyPageParser.parseLoftApiResponse(fetchHtml(loftDataUrl));
        }
        return PropertyPageParser.parse(fetchHtml(normalizedUrl));
    }

    static String buildLoftDataUrl(String normalizedUrl) {
        if (normalizedUrl == null || !"Loft".equals(getSourceName(normalizedUrl))) {
            return null;
        }
        try {
            String path = URI.create(normalizedUrl).getPath();
            int separator = path == null ? -1 : path.lastIndexOf('/');
            String shortId = separator >= 0 ? path.substring(separator + 1).trim() : "";
            if (shortId.isEmpty()) {
                return null;
            }
            return "https://informational-pages-api.loft.com.br/condominiums/" + shortId;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String buildLoftListingUrl(String id) {
        if (id == null || id.trim().isEmpty()) {
            return "";
        }
        return "https://loft.com.br/imovel/" + id.trim();
    }

    static PropertyListingMetadata fetchListingMetadata(String rawUrl) throws Exception {
        String normalizedUrl = normalizeListingUrl(rawUrl);
        if (normalizedUrl == null) {
            throw new IllegalArgumentException("Unsupported listing page");
        }
        return PropertyPageParser.parseListingMetadata(fetchHtml(normalizedUrl));
    }

    static String buildListingUrl(String id) {
        return buildListingUrl(id, false);
    }

    static String buildListingUrl(String id, boolean classified) {
        if (id == null || id.trim().isEmpty()) {
            return "";
        }
        return "https://www.quintoandar.com.br/"
                + (classified ? "classificado/" : "imovel/")
                + id.trim()
                + "/comprar";
    }

    static String buildListingUrlFromOfferId(String offerId) {
        if (offerId == null || !offerId.startsWith("property|")) {
            return "";
        }
        String[] parts = offerId.split("\\|", -1);
        if (parts.length < 3 || parts[2].trim().isEmpty()) {
            return "";
        }
        return buildListingUrl(parts[2]);
    }

    static String getSourceName(String rawUrl) {
        if (rawUrl == null) {
            return "QuintoAndar";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("loft.com.br".equals(host) || "www.loft.com.br".equals(host)) {
                return "Loft";
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "QuintoAndar";
    }

    static String normalizeListingUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if (!"https".equals(scheme)) {
                return null;
            }
            if ("quintoandar.com.br".equals(host) || "www.quintoandar.com.br".equals(host)) {
                if (path.startsWith("/classificado/") && path.endsWith("/comprar")) {
                    return "https://www.quintoandar.com.br" + path;
                }
                if (path.startsWith("/imovel/") && path.endsWith("/comprar")) {
                    return "https://www.quintoandar.com.br" + path;
                }
            } else if ("loft.com.br".equals(host) || "www.loft.com.br".equals(host)) {
                if (path.startsWith("/imovel/") && path.length() > "/imovel/".length()) {
                    return "https://loft.com.br" + path;
                }
            }
            return null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String fetchHtml(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
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
                    throw new IllegalStateException("Property page is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
