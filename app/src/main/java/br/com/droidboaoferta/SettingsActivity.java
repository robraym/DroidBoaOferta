package br.com.droidboaoferta;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
    private TextView themeSummary;
    private ImageButton monitorToggle;
    private InterestRepository interestRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        interestRepository = new InterestRepository(this);
        statusTitle = findViewById(R.id.text_monitor_status_title);
        statusSummary = findViewById(R.id.text_monitor_status_summary);
        themeSummary = findViewById(R.id.text_theme_summary);
        monitorToggle = findViewById(R.id.button_monitor_toggle);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        findViewById(R.id.row_theme).setOnClickListener(view -> showThemeDialog());
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
        themeSummary.setText(ThemeController.getSummaryResource(ThemeController.getSavedMode(this)));

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

    private void showThemeDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.settings_theme_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(10), 0, dp(10));
        String savedMode = ThemeController.getSavedMode(this);
        String[] modes = new String[]{
                ThemeController.MODE_SYSTEM,
                ThemeController.MODE_LIGHT,
                ThemeController.MODE_DARK
        };
        int[] labels = new int[]{
                R.string.theme_system,
                R.string.theme_light,
                R.string.theme_dark
        };
        int selectedIndex = ThemeController.getDialogIndex(savedMode);
        for (int index = 0; index < modes.length; index++) {
            TextView option = createThemeOption(labels[index], index == selectedIndex);
            String mode = modes[index];
            option.setOnClickListener(view -> {
                ThemeController.saveMode(this, mode);
                themeSummary.setText(ThemeController.getSummaryResource(mode));
                dialog.dismiss();
                recreate();
            });
            options.addView(option);
        }
        content.addView(options);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        content.addView(actions);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(shownWindow.getAttributes());
            params.width = getResources().getDisplayMetrics().widthPixels - dp(44);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.65f;
            shownWindow.setAttributes(params);
            shownWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private TextView createThemeOption(int textResource, boolean selected) {
        TextView option = new TextView(this);
        option.setText(selected
                ? getString(R.string.theme_selected_format, getString(textResource))
                : getString(textResource));
        option.setTextColor(getColor(selected ? R.color.action : R.color.text_primary));
        option.setTextSize(16);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(0, dp(12), 0, dp(12));
        return option;
    }

    private TextView createDialogAction(int textResource) {
        TextView action = new TextView(this);
        action.setText(textResource);
        action.setTextColor(getColor(R.color.action));
        action.setTextSize(15);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(14), dp(9), dp(14), dp(9));
        return action;
    }

    private boolean isMonitorEnabled() {
        return getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .getBoolean(MONITOR_ENABLED, true);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
