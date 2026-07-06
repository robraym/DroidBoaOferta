package br.com.droidboaoferta;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OfferMonitor implements TelegramClientManager.MessageListener {
    static final String ACTION_OFFER_FOUND = "br.com.droidboaoferta.OFFER_FOUND";
    static final String CHANNEL_OFFERS = "good_offers";

    private static final OfferMonitor INSTANCE = new OfferMonitor();

    static OfferMonitor getInstance() {
        return INSTANCE;
    }

    private Context appContext;
    private InterestRepository interestRepository;
    private OfferRepository offerRepository;

    private OfferMonitor() {
    }

    synchronized void start(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
            interestRepository = new InterestRepository(appContext);
            offerRepository = new OfferRepository(appContext);
            createNotificationChannel();
        }
        TelegramClientManager clientManager = TelegramClientManager.getInstance();
        clientManager.setMessageListener(this);
        clientManager.start(appContext);
    }

    synchronized void refreshInterestHistory(Context context, long interestId, String term,
                                             double maximumPrice) {
        start(context);
        TelegramClientManager clientManager = TelegramClientManager.getInstance();
        boolean usedValidatedSearch = clientManager.publishCachedLowestPriceMatches(
                interestId,
                term,
                maximumPrice
        );
        if (!usedValidatedSearch) {
            clientManager.refreshInterestHistory(interestId, term);
        }
    }

    @Override
    public void onNewMessage(long chatId, long messageId, long messageDate, String sourceTitle,
                             TelegramMessagePayload payload) {
        String text = payload.getText();
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", java.util.Collections.emptySet());
        if (!selectedGroups.contains(Long.toString(chatId)) || text.trim().isEmpty()) {
            return;
        }
        MonitorStatusStore.markAnalyzedMessage(appContext);

        List<Interest> interests = interestRepository.getAll();
        for (Interest interest : interests) {
            double price = OfferTextParser.extractPriceForInterest(text, interest.getTerm());
            if (Double.isNaN(price)) {
                continue;
            }
            processMessageForInterest(
                    interest,
                    chatId,
                    messageId,
                    messageDate,
                    sourceTitle,
                    text,
                    price,
                    true,
                    payload.findBestLink(interest.getTerm())
            );
        }
    }

    @Override
    public void onHistoricalMessage(long interestId, long chatId, long messageId,
                                    long messageDate, String sourceTitle,
                                    TelegramMessagePayload payload) {
        String text = payload.getText();
        if (text.trim().isEmpty()) {
            return;
        }
        Interest target = null;
        for (Interest interest : interestRepository.getAll()) {
            if (interest.getId() == interestId) {
                target = interest;
                break;
            }
        }
        if (target == null) {
            return;
        }
        double price = OfferTextParser.extractPriceForInterest(text, target.getTerm());
        if (Double.isNaN(price)) {
            return;
        }
        processMessageForInterest(
                target,
                chatId,
                messageId,
                messageDate,
                sourceTitle,
                text,
                price,
                false,
                payload.findBestLink(target.getTerm())
        );
    }

    private void processMessageForInterest(Interest interest, long chatId, long messageId,
                                           long messageDate, String sourceTitle, String text,
                                           double price, boolean notifyUser, String offerLink) {
        if (!OfferTextParser.matchesInterest(text, interest.getTerm())
                || price > interest.getMaximumPrice()
                || !OfferEligibility.isRecent(messageDate, System.currentTimeMillis())
                || !OfferEligibility.hasUsableLink(offerLink)
                || !offerRepository.markOfferProcessed(chatId, messageId, interest.getId())) {
            return;
        }
        ObservedOffer offer = new ObservedOffer(
                interest.getId(),
                interest.getTerm(),
                sourceTitle,
                price,
                interest.getMaximumPrice(),
                messageDate > 0L ? messageDate : System.currentTimeMillis(),
                offerLink
        );
        offerRepository.add(offer);
        MonitorStatusStore.markApprovedOffer(appContext);
        if (notifyUser) {
            showOfferNotification(offer, chatId, messageId);
        }
        appContext.sendBroadcast(new Intent(ACTION_OFFER_FOUND).setPackage(appContext.getPackageName()));
    }

    private void showOfferNotification(ObservedOffer offer, long chatId, long messageId) {
        Intent openApp = offer.getLink().isEmpty()
                ? new Intent(appContext, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink()));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                (int) (messageId ^ chatId),
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        String explanation = appContext.getString(
                R.string.offer_notification_explanation,
                currency.format(offer.getPrice()),
                currency.format(offer.getMaximumPrice()),
                offer.getSource()
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_OFFERS)
                .setSmallIcon(R.drawable.ic_notification_offer)
                .setContentTitle(offer.getInterest())
                .setContentText(explanation)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(explanation))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager manager = (NotificationManager) appContext.getSystemService(
                Context.NOTIFICATION_SERVICE
        );
        manager.notify((int) (messageId ^ chatId), builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) appContext.getSystemService(
                Context.NOTIFICATION_SERVICE
        );
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_OFFERS,
                appContext.getString(R.string.offer_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(appContext.getString(R.string.offer_channel_description));
        manager.createNotificationChannel(channel);
    }
}
