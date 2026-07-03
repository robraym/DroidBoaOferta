package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

final class MonitorStatusStore {
    static final String ACTION_STATUS_CHANGED = "br.com.droidboaoferta.MONITOR_STATUS_CHANGED";

    private static final String PREFS = "monitor_status";
    private static final String SERVICE_RUNNING = "service_running";
    private static final String TELEGRAM_STATE = "telegram_state";
    private static final String TELEGRAM_CONNECTED_AT = "telegram_connected_at";
    private static final String LAST_SELECTED_MESSAGE_AT = "last_selected_message_at";
    private static final String LAST_ANALYZED_MESSAGE_AT = "last_analyzed_message_at";
    private static final String LAST_APPROVED_OFFER_AT = "last_approved_offer_at";
    private static final String LAST_ERROR = "last_error";

    private MonitorStatusStore() {
    }

    static void setServiceRunning(Context context, boolean running) {
        prefs(context).edit()
                .putBoolean(SERVICE_RUNNING, running)
                .apply();
        notifyChanged(context);
    }

    static void setTelegramState(Context context, TelegramClientManager.State state) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String previousState = prefs.getString(TELEGRAM_STATE, TelegramClientManager.State.STARTING.name());
        SharedPreferences.Editor editor = prefs.edit()
                .putString(TELEGRAM_STATE, state.name());
        if (state == TelegramClientManager.State.READY) {
            if (!TelegramClientManager.State.READY.name().equals(previousState)
                    || prefs.getLong(TELEGRAM_CONNECTED_AT, 0L) == 0L) {
                editor.putLong(TELEGRAM_CONNECTED_AT, System.currentTimeMillis());
            }
        } else {
            editor.remove(TELEGRAM_CONNECTED_AT);
        }
        editor.apply();
        notifyChanged(context);
    }

    static void markSelectedMessage(Context context) {
        prefs(context).edit()
                .putLong(LAST_SELECTED_MESSAGE_AT, System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    static void markAnalyzedMessage(Context context) {
        prefs(context).edit()
                .putLong(LAST_ANALYZED_MESSAGE_AT, System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    static void markApprovedOffer(Context context) {
        prefs(context).edit()
                .putLong(LAST_APPROVED_OFFER_AT, System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    static void setLastError(Context context, String message) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return;
        }
        prefs(context).edit()
                .putString(LAST_ERROR, message)
                .apply();
        notifyChanged(context);
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = prefs(context);
        return new Snapshot(
                prefs.getBoolean(SERVICE_RUNNING, false),
                prefs.getString(TELEGRAM_STATE, TelegramClientManager.State.STARTING.name()),
                prefs.getLong(TELEGRAM_CONNECTED_AT, 0L),
                prefs.getLong(LAST_SELECTED_MESSAGE_AT, 0L),
                prefs.getLong(LAST_ANALYZED_MESSAGE_AT, 0L),
                prefs.getLong(LAST_APPROVED_OFFER_AT, 0L),
                prefs.getString(LAST_ERROR, "")
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyChanged(Context context) {
        context.getApplicationContext().sendBroadcast(
                new android.content.Intent(ACTION_STATUS_CHANGED)
                        .setPackage(context.getPackageName())
        );
    }

    static final class Snapshot {
        final boolean serviceRunning;
        final String telegramState;
        final long telegramConnectedAt;
        final long lastSelectedMessageAt;
        final long lastAnalyzedMessageAt;
        final long lastApprovedOfferAt;
        final String lastError;

        Snapshot(boolean serviceRunning, String telegramState, long telegramConnectedAt, long lastSelectedMessageAt,
                 long lastAnalyzedMessageAt, long lastApprovedOfferAt, String lastError) {
            this.serviceRunning = serviceRunning;
            this.telegramState = telegramState;
            this.telegramConnectedAt = telegramConnectedAt;
            this.lastSelectedMessageAt = lastSelectedMessageAt;
            this.lastAnalyzedMessageAt = lastAnalyzedMessageAt;
            this.lastApprovedOfferAt = lastApprovedOfferAt;
            this.lastError = lastError;
        }
    }
}
