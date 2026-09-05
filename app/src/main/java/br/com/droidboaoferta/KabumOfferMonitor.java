package br.com.droidboaoferta;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class KabumOfferMonitor {
    static final String ACTION_STATUS_CHANGED =
            "br.com.droidboaoferta.KABUM_OFFER_STATUS_CHANGED";
    private static final String PREFS = "kabum_offer_monitor";
    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final String KEY_LAST_FEED_SIGNATURE = "last_feed_signature";
    private static final KabumOfferMonitor INSTANCE = new KabumOfferMonitor();

    private Context appContext;
    private ScheduledExecutorService executor;

    private KabumOfferMonitor() {
    }

    static KabumOfferMonitor getInstance() {
        return INSTANCE;
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
                () -> checkAllSafely(false),
                0L,
                KabumOfferSource.getCheckIntervalSeconds(appContext),
                TimeUnit.SECONDS
        );
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
            executor.execute(() -> checkAllSafely(true));
        }
    }

    void clearState(Context context, long interestId) {
        String prefix = LAST_PRICE_PREFIX + interestId + "_";
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    synchronized void rescheduleIfRunning(Context context) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        stop();
        start(context);
    }

    private void checkAllSafely(boolean force) {
        Context context = appContext;
        if (context == null || !KabumOfferSource.isConfigured(context)) {
            return;
        }
        try {
            List<ExternalProductDeal> deals = KabumOfferClient.fetchOffers(
                    KabumOfferSource.getUrl(context)
            );
            if (deals.isEmpty()) {
                throw new IllegalStateException("No KaBuM offers");
            }
            KabumOfferSource.markSuccessfulCheck(context);
            String feedSignature = createFeedSignature(deals);
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!force && feedSignature.equals(preferences.getString(KEY_LAST_FEED_SIGNATURE, ""))) {
                return;
            }
            preferences.edit().putString(KEY_LAST_FEED_SIGNATURE, feedSignature).apply();
            List<Interest> interests = new InterestRepository(context).getAll();
            OfferRepository repository = new OfferRepository(context);
            long observedAt = System.currentTimeMillis();
            boolean found = false;
            for (ExternalProductDeal deal : deals) {
                for (Interest interest : interests) {
                    if (!interest.isPrice()
                            || !OfferTextParser.matchesInterest(deal.getTitle(), interest.getTerm())
                            || deal.getPrice() > interest.getMaximumPrice()) {
                        continue;
                    }
                    String key = LAST_PRICE_PREFIX + interest.getId() + "_" + deal.getId();
                    boolean known = preferences.contains(key);
                    double lastPrice = Double.longBitsToDouble(preferences.getLong(
                            key,
                            Double.doubleToRawLongBits(Double.NaN)
                    ));
                    preferences.edit().putLong(key, Double.doubleToRawLongBits(deal.getPrice()))
                            .apply();
                    if (known && Double.compare(lastPrice, deal.getPrice()) == 0) {
                        continue;
                    }
                    ObservedOffer offer = new ObservedOffer(
                            "kabum|" + interest.getId() + "|" + deal.getId(),
                            interest.getId(),
                            interest.getTerm(),
                            context.getString(R.string.kabum_offer_source),
                            deal.getPrice(),
                            interest.getMaximumPrice(),
                            observedAt,
                            deal.getLink(),
                            ""
                    );
                    repository.add(offer);
                    showNotification(context, offer);
                    found = true;
                }
            }
            if (found) {
                context.sendBroadcast(new Intent(OfferMonitor.ACTION_OFFER_FOUND)
                        .setPackage(context.getPackageName()));
            }
        } catch (Exception ignored) {
            KabumOfferSource.markFailedCheck(context);
        } finally {
            context.sendBroadcast(new Intent(ACTION_STATUS_CHANGED)
                    .setPackage(context.getPackageName()));
        }
    }

    private String createFeedSignature(List<ExternalProductDeal> deals) {
        StringBuilder signature = new StringBuilder();
        int limit = Math.min(40, deals.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                signature.append('|');
            }
            signature.append(deals.get(index).getId());
        }
        return signature.toString();
    }

    private void showNotification(Context context, ObservedOffer offer) {
        Intent openPage = new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink()));
        int notificationId = offer.getId().hashCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        String explanation = context.getString(
                R.string.offer_notification_explanation,
                currency.format(offer.getPrice()),
                currency.format(offer.getMaximumPrice()),
                offer.getSource()
        );
        AlertSoundController.configureNotificationChannel(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                AlertSoundController.getChannelId(context)
        )
                .setSmallIcon(R.drawable.ic_notification_offer)
                .setContentTitle(offer.getInterest())
                .setContentText(explanation)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(explanation))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(AlertSoundController.getSoundUri(context))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE
        );
        if (manager != null) {
            manager.notify(notificationId, builder.build());
        }
        AlertSoundController.playSelectedSound(context);
    }
}
