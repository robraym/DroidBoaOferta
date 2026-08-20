package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

/** Lightweight per-group cursor used only to recover messages received while offline. */
final class MonitorCheckpointStore {
    private static final String PREFS = "monitor_message_checkpoints";

    private MonitorCheckpointStore() {
    }

    static long getLastMessageId(Context context, long chatId) {
        return prefs(context).getLong("chat_" + chatId, 0L);
    }

    static void markProcessed(Context context, long chatId, long messageId) {
        if (chatId == 0L || messageId <= getLastMessageId(context, chatId)) return;
        prefs(context).edit().putLong("chat_" + chatId, messageId).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
