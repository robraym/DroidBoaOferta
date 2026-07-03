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

    @Override
    public void onNewMessage(long chatId, long messageId, String sourceTitle, String text) {
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", java.util.Collections.emptySet());
        if (!selectedGroups.contains(Long.toString(chatId)) || text.trim().isEmpty()) {
            return;
        }
        if (!offerRepository.markMessageProcessed(chatId, messageId)) {
            return;
        }
        MonitorStatusStore.markAnalyzedMessage(appContext);

        double price = OfferTextParser.extractPrice(text);
        if (Double.isNaN(price)) {
            return;
        }

        String normalizedMessage = OfferTextParser.normalize(text);
        List<Interest> interests = interestRepository.getAll();
        for (Interest interest : interests) {
            if (!normalizedMessage.contains(OfferTextParser.normalize(interest.getTerm()))) {
                continue;
            }
            if (price > interest.getMaximumPrice()) {
                continue;
            }

            ObservedOffer offer = new ObservedOffer(
                    interest.getTerm(),
                    sourceTitle,
                    price,
                    interest.getMaximumPrice(),
                    System.currentTimeMillis(),
                    OfferTextParser.extractLink(text)
            );
            offerRepository.add(offer);
            MonitorStatusStore.markApprovedOffer(appContext);
            showOfferNotification(offer, chatId, messageId);
            appContext.sendBroadcast(new Intent(ACTION_OFFER_FOUND).setPackage(appContext.getPackageName()));
        }
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
