package br.com.droidboaoferta;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final int REQUEST_NOTIFICATIONS = 1201;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSettings();
        }
    };

    private TextView statusTitle;
    private TextView statusSummary;
    private ImageButton monitorToggle;
    private TextView groupsSummary;
    private TextView alertsSummary;
    private InterestRepository interestRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        interestRepository = new InterestRepository(this);
        statusTitle = findViewById(R.id.text_monitor_status_title);
        statusSummary = findViewById(R.id.text_monitor_status_summary);
        monitorToggle = findViewById(R.id.button_monitor_toggle);
        groupsSummary = findViewById(R.id.text_groups_summary);
        alertsSummary = findViewById(R.id.text_alerts_summary);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        findViewById(R.id.row_groups).setOnClickListener(view -> startActivity(
                new Intent(this, TelegramSetupActivity.class)
        ));
        findViewById(R.id.row_alerts).setOnClickListener(view -> startActivity(
                new Intent(this, AlertsActivity.class)
        ));
        monitorToggle.setOnClickListener(view -> toggleMonitor());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(MonitorStatusStore.ACTION_STATUS_CHANGED);
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSettings();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    private void refreshSettings() {
        int groupCount = getSelectedGroupCount();
        List<Interest> interests = interestRepository.getAll();
        boolean monitorEnabled = isMonitorEnabled();

        if (groupCount == 0) {
            groupsSummary.setText(R.string.dashboard_no_groups);
        } else {
            groupsSummary.setText(getResources().getQuantityString(
                    R.plurals.dashboard_groups_selected,
                    groupCount,
                    groupCount
            ));
        }
        renderInterests(interests);

        if (groupCount == 0) {
            statusTitle.setText(R.string.dashboard_status_choose_groups);
            statusSummary.setText(R.string.dashboard_status_choose_groups_summary);
            monitorToggle.setVisibility(View.GONE);
            stopService(new Intent(this, OfferMonitorService.class));
        } else if (interests.isEmpty()) {
            statusTitle.setText(R.string.dashboard_status_add_interest);
            statusSummary.setText(R.string.dashboard_status_add_interest_summary);
            monitorToggle.setVisibility(View.GONE);
            stopService(new Intent(this, OfferMonitorService.class));
        } else if (monitorEnabled) {
            requestNotificationPermissionIfNeeded();
            statusTitle.setText(R.string.dashboard_status_active);
            String groupCountText = getResources().getQuantityString(
                    R.plurals.dashboard_groups_count,
                    groupCount,
                    groupCount
            );
            String interestCountText = getResources().getQuantityString(
                    R.plurals.dashboard_interests_count,
                    interests.size(),
                    interests.size()
            );
            ContextCompat.startForegroundService(this, new Intent(this, OfferMonitorService.class));
            statusSummary.setText(buildActiveMonitorSummary(groupCountText, interestCountText));
            monitorToggle.setImageResource(R.drawable.ic_pause);
            monitorToggle.setContentDescription(getString(R.string.action_pause));
            monitorToggle.setVisibility(View.VISIBLE);
        } else {
            statusTitle.setText(R.string.dashboard_status_paused);
            statusSummary.setText(R.string.dashboard_status_paused_summary);
            monitorToggle.setImageResource(R.drawable.ic_play);
            monitorToggle.setContentDescription(getString(R.string.action_activate));
            monitorToggle.setVisibility(View.VISIBLE);
            stopService(new Intent(this, OfferMonitorService.class));
        }
    }

    private void renderInterests(List<Interest> interests) {
        if (interests.isEmpty()) {
            alertsSummary.setText(R.string.dashboard_no_interests);
            return;
        }
        alertsSummary.setText(getResources().getQuantityString(
                R.plurals.dashboard_alerts_configured,
                interests.size(),
                interests.size()
        ));
    }

    private String buildActiveMonitorSummary(String groupCountText, String interestCountText) {
        MonitorStatusStore.Snapshot snapshot = MonitorStatusStore.read(this);
        String configuration = getString(
                R.string.dashboard_status_active_summary,
                groupCountText,
                interestCountText
        );
        return configuration + "\n" + getTelegramConnectionText(snapshot);
    }

    private String getTelegramConnectionText(MonitorStatusStore.Snapshot snapshot) {
        TelegramClientManager.State state;
        try {
            state = TelegramClientManager.State.valueOf(snapshot.telegramState);
        } catch (IllegalArgumentException exception) {
            state = TelegramClientManager.State.STARTING;
        }
        if (state == TelegramClientManager.State.READY) {
            long connectedAt = snapshot.telegramConnectedAt == 0L
                    ? System.currentTimeMillis()
                    : snapshot.telegramConnectedAt;
            return getString(R.string.dashboard_telegram_connected_for, formatRelativeTime(connectedAt));
        }
        return getTelegramStateText(snapshot.telegramState);
    }

    private String getTelegramStateText(String stateName) {
        TelegramClientManager.State state;
        try {
            state = TelegramClientManager.State.valueOf(stateName);
        } catch (IllegalArgumentException exception) {
            state = TelegramClientManager.State.STARTING;
        }
        switch (state) {
            case READY:
                return getString(R.string.dashboard_telegram_ready);
            case MISSING_CREDENTIALS:
                return getString(R.string.dashboard_telegram_missing_credentials);
            case WAITING_PHONE:
            case WAITING_EMAIL:
            case WAITING_EMAIL_CODE:
            case WAITING_CODE:
            case WAITING_PASSWORD:
                return getString(R.string.dashboard_telegram_login_pending);
            case CLOSED:
                return getString(R.string.dashboard_telegram_closed);
            case UNSUPPORTED_AUTHORIZATION:
                return getString(R.string.dashboard_telegram_attention);
            case STARTING:
            default:
                return getString(R.string.dashboard_telegram_starting);
        }
    }

    private String formatRelativeTime(long timestamp) {
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - timestamp);
        long minutes = elapsedMillis / 60000L;
        if (minutes < 1) {
            return getString(R.string.time_now);
        }
        if (minutes < 60) {
            return getResources().getQuantityString(R.plurals.time_minutes_ago, (int) minutes, (int) minutes);
        }
        long hours = minutes / 60L;
        if (hours < 24) {
            return getResources().getQuantityString(R.plurals.time_hours_ago, (int) hours, (int) hours);
        }
        long days = hours / 24L;
        int safeDays = (int) Math.min(days, Integer.MAX_VALUE);
        return getResources().getQuantityString(R.plurals.time_days_ago, safeDays, safeDays);
    }

    private void toggleMonitor() {
        boolean enabled = !isMonitorEnabled();
        getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MONITOR_ENABLED, enabled)
                .apply();
        refreshSettings();
    }

    private boolean isMonitorEnabled() {
        return getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .getBoolean(MONITOR_ENABLED, true);
    }

    private int getSelectedGroupCount() {
        return getSharedPreferences(TELEGRAM_PREFS, MODE_PRIVATE)
                .getStringSet(SELECTED_GROUPS, Collections.emptySet())
                .size();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }
}
