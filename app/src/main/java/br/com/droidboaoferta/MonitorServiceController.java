package br.com.droidboaoferta;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

import java.util.Collections;

final class MonitorServiceController {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";

    private MonitorServiceController() {
    }

    static void update(Context context) {
        Context appContext = context.getApplicationContext();
        Intent serviceIntent = new Intent(appContext, OfferMonitorService.class);
        if (!shouldRun(appContext)) {
            appContext.stopService(serviceIntent);
            return;
        }
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent);
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            AppErrorStore.recordSerious(
                    appContext,
                    "Monitor de ofertas",
                    message == null ? "Não foi possível iniciar o monitor em segundo plano." : message
            );
        }
    }

    static boolean shouldRun(Context context) {
        if (!isEnabled(context)) {
            return false;
        }
        boolean hasPriceAlert = false;
        boolean hasCouponAlert = false;
        for (Interest interest : new InterestRepository(context).getAll()) {
            if (interest.isCoupon()) {
                hasCouponAlert = true;
            } else {
                hasPriceAlert = true;
            }
        }
        return hasCouponAlert || (hasPriceAlert && selectedGroupCount(context) > 0);
    }

    static boolean isEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE)
                .getBoolean(MONITOR_ENABLED, true);
    }

    static int selectedGroupCount(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE);
        return preferences.getStringSet(SELECTED_GROUPS, Collections.emptySet()).size();
    }
}
