package br.com.droidboaoferta;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PropertyPageMonitor {
    private static final String PREFS = "property_page_monitor";
    private static final String NOTIFIED_PRICES_PREFIX = "notified_prices_";
    private static final long CHECK_INTERVAL_MINUTES = 15L;
    private static final double PRICE_VERIFICATION_MARGIN = 1.10d;
    private static final PropertyPageMonitor INSTANCE = new PropertyPageMonitor();

    private Context appContext;
    private ScheduledExecutorService executor;

    private PropertyPageMonitor() {
    }

    static PropertyPageMonitor getInstance() {
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
                .remove(NOTIFIED_PRICES_PREFIX + interestId)
                .apply();
        new PropertyHistoryRepository(context).clearMetadataAttemptsForInterest(interestId);
    }

    private void checkAllSafely() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        for (Interest interest : new InterestRepository(context).getAll()) {
            if (!interest.isProperty()) {
                continue;
            }
            try {
                checkInterest(context, interest);
            } catch (Exception ignored) {
                // Uma falha temporária não altera os imóveis já avisados.
            }
        }
        PropertyHistoryRepository.publishPendingChanges(context);
        // Also refresh status when all results are unavailable or no new offer was emitted.
        context.sendBroadcast(new Intent(OfferMonitor.ACTION_OFFER_FOUND)
                .setPackage(context.getPackageName()));
    }

    private void checkInterest(Context context, Interest interest) throws Exception {
        PropertyPageResult result = PropertyPageClient.fetch(interest.getTerm());
        String propertyName = PropertyPageResult.normalizeCondominiumName(
                interest.getPropertyName());
        if (propertyName.isEmpty() && result.hasCondominiumName()) {
            propertyName = result.getCondominiumName();
            new InterestRepository(context).updatePropertyName(
                    interest.getId(), propertyName);
        }
        if (propertyName.isEmpty()) {
            propertyName = result.getCondominiumName();
        }
        long observedAt = System.currentTimeMillis();
        PropertyHistoryRepository historyRepository = new PropertyHistoryRepository(context);
        List<PropertyPageListing> matches = new ArrayList<>();
        Map<String, Integer> historyChanges = new HashMap<>();
        Map<String, PropertyPageListing> candidates = new java.util.LinkedHashMap<>();
        for (PropertyPageListing listing : historyRepository.getTrackedListings(interest.getId())) {
            candidates.put(listing.getId(), listing);
        }
        for (PropertyPageListing listing : result.getSaleListings()) {
            candidates.put(listing.getId(), listing);
        }
        for (PropertyPageListing listing : candidates.values()) {
            boolean previouslyObserved = historyRepository.contains(
                    interest.getId(), listing);
            if (!previouslyObserved && !listing.matchesArea(
                    interest.getMinimumArea(), interest.getMaximumArea())) continue;
            PropertyListingMetadata metadata = null;
            boolean requiresIdentity = PropertyPageClient.isQuintoAndarListingUrl(listing.getUrl());
            if (requiresIdentity && shouldVerifyIndividualPrice(
                    previouslyObserved,
                    listing.getSalePrice(),
                    interest.getMaximumPrice())) {
                try {
                    metadata = PropertyPageClient.fetchListingMetadata(listing.getUrl());
                } catch (Exception ignored) {
                    metadata = PropertyListingMetadata.empty();
                }
            }
            if (requiresIdentity && metadata != null && metadata.isUnavailableFor(listing.getId())) {
                historyRepository.markUnavailable(interest.getId(), listing, observedAt);
                forgetUnavailableNotification(context, interest.getId(), listing.getId());
                continue;
            }
            PropertyPageListing currentListing = resolveCurrentListing(listing, metadata);
            if (currentListing == null) continue;
            boolean eligible = currentListing.matches(interest.getMinimumArea(),
                    interest.getMaximumArea(), interest.getMaximumPrice());
            if (previouslyObserved || eligible) {
                historyChanges.put(currentListing.getId(), historyRepository.recordObservation(
                        interest.getId(), currentListing, observedAt, metadata));
            }
            if (eligible) {
                matches.add(currentListing);
            }
        }
        if (matches.isEmpty()) {
            return;
        }
        matches.sort(Comparator.comparingDouble(PropertyPageListing::getSalePrice));

        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stateKey = NOTIFIED_PRICES_PREFIX + interest.getId();
        JSONObject notifiedPrices;
        try {
            notifiedPrices = new JSONObject(preferences.getString(stateKey, "{}"));
        } catch (Exception ignored) {
            notifiedPrices = new JSONObject();
        }

        List<PropertyPageListing> changed = new ArrayList<>();
        for (PropertyPageListing listing : matches) {
            double previousPrice = notifiedPrices.optDouble(listing.getId(), Double.NaN);
            int historyChange = historyChanges.getOrDefault(
                    listing.getId(), PropertyHistoryRepository.UNCHANGED);
            if (Double.isNaN(previousPrice)
                    || historyChange == PropertyHistoryRepository.CHANGED
                    || Double.compare(listing.getSalePrice(), previousPrice) != 0) {
                changed.add(listing);
            }
            try {
                notifiedPrices.put(listing.getId(), listing.getSalePrice());
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(stateKey, notifiedPrices.toString()).apply();
        if (changed.isEmpty()) {
            return;
        }

        OfferRepository repository = new OfferRepository(context);
        NumberFormat areaFormat = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        areaFormat.setMaximumFractionDigits(1);
        String sourceName = PropertyPageClient.getSourceName(interest.getTerm());
        for (PropertyPageListing listing : changed) {
            repository.add(new ObservedOffer(
                    "property|" + interest.getId() + "|" + listing.getId(),
                    interest.getId(),
                    propertyName,
                    context.getString(
                            R.string.property_offer_source,
                            sourceName,
                            areaFormat.format(listing.getArea())
                    ),
                    listing.getSalePrice(),
                    interest.getMaximumPrice(),
                    observedAt,
                    listing.getUrl(),
                    ""
            ));
        }
        showNotification(context, interest, result, changed);
        context.sendBroadcast(new Intent(OfferMonitor.ACTION_OFFER_FOUND)
                .setPackage(context.getPackageName()));
    }

    static boolean shouldVerifyIndividualPrice(boolean previouslyObserved,
                                               double summaryPrice,
                                               double maximumPrice) {
        return previouslyObserved
                || summaryPrice <= maximumPrice * PRICE_VERIFICATION_MARGIN;
    }

    static PropertyPageListing resolveCurrentListing(PropertyPageListing listing,
                                                       PropertyListingMetadata metadata) {
        String normalizedUrl = PropertyPageClient.normalizeListingUrl(listing.getUrl());
        if (normalizedUrl == null) return null;
        if ("Loft".equals(PropertyPageClient.getSourceName(normalizedUrl))) return listing;
        if (metadata == null || !metadata.isVerifiedFor(listing.getId())) return null;
        return new PropertyPageListing(listing.getId(), metadata.getArea(), metadata.getSalePrice(),
                listing.getDescription(), listing.getUrl(), listing.isNewAd());
    }

    private void forgetUnavailableNotification(Context context, long interestId, String listingId) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = NOTIFIED_PRICES_PREFIX + interestId;
        try {
            JSONObject prices = new JSONObject(preferences.getString(key, "{}"));
            prices.remove(listingId);
            preferences.edit().putString(key, prices.toString()).apply();
        } catch (Exception ignored) {
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(Long.hashCode(interestId) ^ 0x51A7);
    }

    private void showNotification(Context context, Interest interest, PropertyPageResult result,
                                  List<PropertyPageListing> listings) {
        PropertyPageListing first = listings.get(0);
        String targetUrl = listings.size() == 1 ? first.getUrl() : interest.getTerm();
        Intent openPage = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                Long.hashCode(interest.getId()) ^ 0x51A7,
                openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        NumberFormat areaFormat = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        areaFormat.setMaximumFractionDigits(1);
        String title = listings.size() == 1
                ? context.getString(R.string.property_notification_title)
                : context.getString(R.string.property_notification_title_many, listings.size());
        String explanation = listings.size() == 1
                ? context.getString(
                        R.string.property_notification_explanation,
                        result.getCondominiumName(),
                        areaFormat.format(first.getArea()),
                        currency.format(first.getSalePrice())
                )
                : context.getString(
                        R.string.property_notification_explanation_many,
                        result.getCondominiumName()
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
        manager.notify(Long.hashCode(interest.getId()) ^ 0x51A7, builder.build());
        AlertSoundController.playSelectedSound(context);
    }
}
