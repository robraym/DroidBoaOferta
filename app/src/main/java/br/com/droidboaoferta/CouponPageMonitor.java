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

final class CouponPageMonitor {
    private static final String PREFS = "coupon_page_monitor";
    private static final String LAST_AMOUNT_PREFIX = "last_amount_";
    private static final String LAST_CODE_PREFIX = "last_code_";
    private static final long CHECK_INTERVAL_MINUTES = 5L;
    private static final CouponPageMonitor INSTANCE = new CouponPageMonitor();

    private Context appContext;
    private ScheduledExecutorService executor;

    private CouponPageMonitor() {
    }

    static CouponPageMonitor getInstance() {
        return INSTANCE;
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
                this::checkAllSafely,
                0L,
                CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
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
            executor.execute(this::checkAllSafely);
        }
    }

    void clearState(Context context, long interestId) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(LAST_AMOUNT_PREFIX + interestId)
                .remove(LAST_CODE_PREFIX + interestId)
                .apply();
    }

    private void checkAllSafely() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        List<Interest> interests = new InterestRepository(context).getAll();
        for (Interest interest : interests) {
            if (!interest.isCoupon()) {
                continue;
            }
            try {
                checkInterest(context, interest);
            } catch (Exception ignored) {
                // Uma falha temporária da página não altera o último cupom conhecido.
            }
        }
    }

    private void checkInterest(Context context, Interest interest) throws Exception {
        CouponPageCoupon highest = CouponPageClient.fetchHighest(interest.getTerm());
        if (highest == null) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String amountKey = LAST_AMOUNT_PREFIX + interest.getId();
        String codeKey = LAST_CODE_PREFIX + interest.getId();
        boolean hadPrevious = preferences.contains(amountKey);
        double previousAmount = Double.longBitsToDouble(
                preferences.getLong(amountKey, Double.doubleToRawLongBits(Double.NaN)));
        String previousCode = preferences.getString(codeKey, "");

        preferences.edit()
                .putLong(amountKey, Double.doubleToRawLongBits(highest.getValue()))
                .putString(codeKey, highest.getCode())
                .apply();

        boolean reachedMinimum = highest.getValue() >= interest.getMaximumPrice();
        boolean becameBetter = !hadPrevious
                || highest.getValue() > previousAmount
                || (Double.compare(highest.getValue(), previousAmount) == 0
                && !highest.getCode().equals(previousCode));
        if (!reachedMinimum || !becameBetter) {
            return;
        }

        long observedAt = System.currentTimeMillis();
        String canonicalUrl = CouponPageClient.normalizeSupportedUrl(interest.getTerm());
        String offerId = "coupon|" + interest.getId() + "|" + highest.getCode()
                + "|" + Double.doubleToLongBits(highest.getValue());
        ObservedOffer offer = new ObservedOffer(
                offerId,
                interest.getId(),
                context.getString(R.string.motorola_coupon_offer_title),
                context.getString(R.string.motorola_coupon_source, highest.getCode()),
                highest.getValue(),
                interest.getMaximumPrice(),
                observedAt,
                canonicalUrl == null ? interest.getTerm() : canonicalUrl,
                ""
        );
        new OfferRepository(context).add(offer);
        showNotification(context, interest, highest, offer.getLink());
        context.sendBroadcast(new Intent(OfferMonitor.ACTION_OFFER_FOUND)
                .setPackage(context.getPackageName()));
    }

    private void showNotification(Context context, Interest interest, CouponPageCoupon coupon,
                                  String pageUrl) {
        Intent openPage = new Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                Long.hashCode(interest.getId()),
                openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        String title = context.getString(
                R.string.coupon_notification_title,
                currency.format(coupon.getValue())
        );
        String explanation = context.getString(
                R.string.coupon_notification_explanation,
                coupon.getCode()
        );
        AlertSoundController.configureNotificationChannel(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                AlertSoundController.getChannelId(context)
        )
                .setSmallIcon(R.drawable.ic_notification_offer)
                .setContentTitle(title)
                .setContentText(explanation)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(explanation))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(AlertSoundController.getSoundUri(context))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        manager.notify(Long.hashCode(interest.getId()), builder.build());
        AlertSoundController.playSelectedSound(context);
    }
}
