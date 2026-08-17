package br.com.droidboaoferta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MonitorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            MonitorServiceController.update(context);
        }
    }
}
