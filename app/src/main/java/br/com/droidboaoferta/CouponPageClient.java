package br.com.droidboaoferta;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CouponPageClient {
    static final String MOTOROLA_COUPONS_URL =
            "https://www.motorola.com.br/cupons-de-desconto-motorola";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService REQUEST_EXECUTOR = Executors.newCachedThreadPool();

    interface Callback {
        void onResult(CouponPageCoupon coupon, int errorMessageResource);
    }

    private CouponPageClient() {
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
                    || !("motorola.com.br".equals(host) || "www.motorola.com.br".equals(host))
                    || !"/cupons-de-desconto-motorola".equals(path)) {
                return null;
            }
            return MOTOROLA_COUPONS_URL;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static void fetchHighestAsync(String rawUrl, Callback callback) {
        REQUEST_EXECUTOR.execute(() -> {
            CouponPageCoupon coupon = null;
            int error = 0;
            try {
                coupon = fetchHighest(rawUrl);
                if (coupon == null) {
                    error = R.string.highest_coupon_not_found;
                }
            } catch (Exception ignored) {
                error = R.string.highest_coupon_load_failed;
            }
            CouponPageCoupon result = coupon;
            int resultError = error;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, resultError));
        });
    }

    static CouponPageCoupon fetchHighest(String rawUrl) throws Exception {
        String normalizedUrl = normalizeSupportedUrl(rawUrl);
        if (normalizedUrl == null) {
            throw new IllegalArgumentException("Unsupported coupon page");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(normalizedUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(12_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        connection.setRequestProperty("User-Agent", "Alertou/1.0 Android");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return CouponPageParser.findHighest(readResponse(connection.getInputStream()));
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
                    throw new IllegalStateException("Coupon page is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
