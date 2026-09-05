package br.com.droidboaoferta;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class VivoOutletMonitor {
    static final String ACTION_STATUS_CHANGED =
            "br.com.droidboaoferta.VIVO_OUTLET_STATUS_CHANGED";
    private static final String PREFS = "vivo_outlet_monitor";
    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final long CHECK_INTERVAL_MINUTES = 15L;
    private static final VivoOutletMonitor INSTANCE = new VivoOutletMonitor();

    private Context appContext;
    private ScheduledExecutorService executor;

    private VivoOutletMonitor() {
    }

    static VivoOutletMonitor getInstance() {
        return INSTANCE;
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkAllSafely, 0L, CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
    }

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    synchronized void checkNow(Context context) {
        boolean alreadyRunning = executor != null && !executor.isShutdown();
        start(context);
        if (alreadyRunning) {
            executor.execute(this::checkAllSafely);
        }
    }

    private void checkAllSafely() {
        Context context = appContext;
        if (context == null || !VivoOutletSource.isConfigured(context)) {
            return;
        }
        try {
            List<VivoOutletProduct> products = VivoOutletClient.fetchProducts(
                    VivoOutletSource.getUrl(context));
            if (products.isEmpty()) {
                throw new IllegalStateException("No Vivo outlet products");
            }
            VivoOutletSource.markSuccessfulCheck(context);
            List<Interest> interests = new InterestRepository(context).getAll();
            OfferRepository repository = new OfferRepository(context);
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long observedAt = System.currentTimeMillis();
            boolean found = false;
            for (Interest interest : interests) {
                if (!interest.isPrice()) {
                    continue;
                }
                for (VivoOutletProduct product : products) {
                    if (!OfferTextParser.matchesInterest(product.getName(), interest.getTerm())
                            || product.getPixPrice() > interest.getMaximumPrice()) {
                        continue;
                    }
                    String key = LAST_PRICE_PREFIX + interest.getId() + "_" + product.getCode();
                    boolean known = preferences.contains(key);
                    double lastPrice = Double.longBitsToDouble(preferences.getLong(
                            key, Double.doubleToRawLongBits(Double.NaN)));
                    if (known && Double.compare(lastPrice, product.getPixPrice()) == 0) {
                        continue;
                    }
                    preferences.edit().putLong(key, Double.doubleToRawLongBits(product.getPixPrice()))
                            .apply();
                    repository.add(new ObservedOffer(
                            "vivo|" + interest.getId() + "|" + product.getCode(),
                            interest.getId(),
                            interest.getTerm(),
                            context.getString(R.string.vivo_outlet_offer_source),
                            product.getPixPrice(),
                            interest.getMaximumPrice(),
                            observedAt,
                            product.getLink(),
                            ""
                    ));
                    found = true;
                }
            }
            if (found) {
                context.sendBroadcast(new Intent(OfferMonitor.ACTION_OFFER_FOUND)
                        .setPackage(context.getPackageName()));
            }
        } catch (Exception ignored) {
            VivoOutletSource.markFailedCheck(context);
            // Mantém a última leitura válida se a loja estiver indisponível temporariamente.
        } finally {
            context.sendBroadcast(new Intent(ACTION_STATUS_CHANGED)
                    .setPackage(context.getPackageName()));
        }
    }
}
