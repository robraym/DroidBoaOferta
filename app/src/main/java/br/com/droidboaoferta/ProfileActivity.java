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
import android.text.method.ScrollingMovementMethod;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ProfileActivity extends AppCompatActivity implements TelegramClientManager.Listener {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final int REQUEST_NOTIFICATIONS = 1201;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSettingsControls();
            refreshErrorSummary();
        }
    };

    private TelegramClientManager clientManager;
    private TextView avatarText;
    private TextView profileName;
    private TextView profileSummary;
    private TextView profileStatus;
    private TextView appVersion;
    private TextView syncSummary;
    private TextView monitorStatusTitle;
    private TextView monitorStatusSummary;
    private TextView themeSummary;
    private TextView errorSummary;
    private ImageButton monitorToggle;
    private InterestRepository interestRepository;
    private LinearLayout accountCard;
    private LinearLayout syncRow;
    private LinearLayout logoutRow;
    private View accountChevron;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        clientManager = TelegramClientManager.getInstance();
        interestRepository = new InterestRepository(this);
        avatarText = findViewById(R.id.text_profile_avatar);
        profileName = findViewById(R.id.text_profile_name);
        profileSummary = findViewById(R.id.text_profile_summary);
        profileStatus = findViewById(R.id.text_profile_status);
        appVersion = findViewById(R.id.text_app_version);
        syncSummary = findViewById(R.id.text_sync_summary);
        monitorStatusTitle = findViewById(R.id.text_monitor_status_title);
        monitorStatusSummary = findViewById(R.id.text_monitor_status_summary);
        themeSummary = findViewById(R.id.text_theme_summary);
        errorSummary = findViewById(R.id.text_error_summary);
        monitorToggle = findViewById(R.id.button_monitor_toggle);
        accountCard = findViewById(R.id.card_telegram_account);
        syncRow = findViewById(R.id.row_sync);
        logoutRow = findViewById(R.id.row_logout);
        accountChevron = findViewById(R.id.image_account_chevron);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        accountCard.setOnClickListener(view -> {
            if (clientManager.getState() != TelegramClientManager.State.READY) {
                startActivity(new Intent(this, TelegramSetupActivity.class));
            }
        });
        findViewById(R.id.row_theme).setOnClickListener(view -> showThemeDialog());
        monitorToggle.setOnClickListener(view -> toggleMonitor());
        findViewById(R.id.row_terms).setOnClickListener(view -> showTermsDialog());
        findViewById(R.id.row_errors).setOnClickListener(view -> showErrorHistoryDialog());
        logoutRow.setOnClickListener(view -> showLogoutConfirmation());
        appVersion.setText(getString(R.string.profile_app_version, BuildConfig.VERSION_NAME));
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientManager.setListener(this);
        clientManager.start(this);
        IntentFilter statusFilter = new IntentFilter(MonitorStatusStore.ACTION_STATUS_CHANGED);
        statusFilter.addAction(AppErrorStore.ACTION_ERRORS_CHANGED);
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                statusFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        refreshProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfile();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusReceiver);
        clientManager.clearListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TelegramClientManager.State state) {
        runOnUiThread(this::refreshProfile);
    }

    @Override
    public void onGroupsLoaded(List<TelegramGroup> groups) {
    }

    @Override
    public void onAccountChanged() {
        runOnUiThread(this::refreshProfile);
    }

    @Override
    public void onCloudSyncStatus(int messageResource) {
        runOnUiThread(() -> {
            refreshSyncSummary();
        });
    }

    private void refreshProfile() {
        TelegramClientManager.State state = clientManager.getState();
        String accountName = clientManager.getAccountName();
        String accountPhone = clientManager.getAccountPhone();
        boolean connected = state == TelegramClientManager.State.READY;

        if (connected) {
            String displayName = TextUtils.isEmpty(accountName)
                    ? getString(R.string.profile_telegram_connected)
                    : accountName;
            profileName.setText(displayName);
            profileSummary.setText(TextUtils.isEmpty(accountPhone)
                    ? getString(R.string.profile_telegram_account_summary)
                    : getString(R.string.profile_telegram_phone, accountPhone));
            profileStatus.setText(getString(R.string.profile_telegram_status_connected));
            avatarText.setText(getAvatarLetter(displayName));
        } else {
            profileName.setText(R.string.profile_telegram_disconnected);
            profileSummary.setText(R.string.profile_telegram_disconnected_summary);
            profileStatus.setText(getTelegramStateText(state));
            avatarText.setText(R.string.icon_telegram_letter);
        }
        accountCard.setClickable(!connected);
        accountCard.setFocusable(!connected);
        accountChevron.setVisibility(connected ? View.GONE : View.VISIBLE);
        syncRow.setVisibility(connected ? View.VISIBLE : View.GONE);
        logoutRow.setVisibility(connected ? View.VISIBLE : View.GONE);
        refreshSyncSummary();
        refreshErrorSummary();
        refreshSettingsControls();
    }

    private void refreshSettingsControls() {
        int groupCount = getSelectedGroupCount();
        List<Interest> interests = interestRepository.getAll();
        boolean monitorEnabled = isMonitorEnabled();
        themeSummary.setText(ThemeController.getSummaryResource(ThemeController.getSavedMode(this)));

        if (groupCount == 0) {
            monitorStatusTitle.setText(R.string.dashboard_status_choose_groups);
            monitorStatusSummary.setText(R.string.dashboard_status_choose_groups_summary);
            monitorToggle.setVisibility(View.GONE);
            stopService(new Intent(this, OfferMonitorService.class));
        } else if (interests.isEmpty()) {
            monitorStatusTitle.setText(R.string.dashboard_status_add_interest);
            monitorStatusSummary.setText(R.string.dashboard_status_add_interest_summary);
            monitorToggle.setVisibility(View.GONE);
            stopService(new Intent(this, OfferMonitorService.class));
        } else if (monitorEnabled) {
            requestNotificationPermissionIfNeeded();
            monitorStatusTitle.setText(R.string.dashboard_status_active);
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
            monitorStatusSummary.setText(buildActiveMonitorSummary(groupCountText, interestCountText));
            monitorToggle.setImageResource(R.drawable.ic_pause);
            monitorToggle.setContentDescription(getString(R.string.action_pause));
            monitorToggle.setVisibility(View.VISIBLE);
        } else {
            monitorStatusTitle.setText(R.string.dashboard_status_paused);
            monitorStatusSummary.setText(R.string.dashboard_status_paused_summary);
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
        return getTelegramStateText(state);
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
        long changedAt = System.currentTimeMillis();
        getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MONITOR_ENABLED, enabled)
                .apply();
        CloudSyncStore.rememberMonitorChanged(this, changedAt);
        CloudSyncStore.markLocalChanged(this);
        refreshSettingsControls();
    }

    private void showThemeDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.settings_theme_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(10), 0, dp(10));
        String savedMode = ThemeController.getSavedMode(this);
        String[] modes = new String[]{
                ThemeController.MODE_LIGHT,
                ThemeController.MODE_DARK
        };
        int[] labels = new int[]{
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
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
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

    private String getAvatarLetter(String text) {
        if (TextUtils.isEmpty(text)) {
            return getString(R.string.icon_telegram_letter);
        }
        return text.substring(0, 1).toUpperCase();
    }

    private String getTelegramStateText(TelegramClientManager.State state) {
        switch (state) {
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
            case READY:
                return getString(R.string.dashboard_telegram_ready);
            case STARTING:
            default:
                return getString(R.string.dashboard_telegram_starting);
        }
    }

    private void showLogoutConfirmation() {
        showConfirmationDialog(
                R.string.profile_logout_dialog_title,
                R.string.profile_logout_dialog_message,
                R.string.profile_action_disconnect,
                true,
                this::disconnectTelegram
        );
    }

    private void refreshSyncSummary() {
        if (syncSummary == null) {
            return;
        }
        if (CloudSyncStore.hasPendingPush(this)) {
            syncSummary.setText(R.string.profile_sync_pending);
            return;
        }
        long lastSyncAt = Math.max(
                CloudSyncStore.getLastBackupAt(this),
                CloudSyncStore.getLastRemoteBackupAt(this)
        );
        syncSummary.setText(lastSyncAt > 0L
                ? getString(R.string.profile_sync_last_format, formatSyncTime(lastSyncAt))
                : getString(R.string.profile_sync_waiting));
    }

    private String formatSyncTime(long timestamp) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("pt", "BR"))
                .format(new Date(timestamp));
    }

    private void disconnectTelegram() {
        stopService(new Intent(this, OfferMonitorService.class));
        getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MONITOR_ENABLED, false)
                .apply();
        getSharedPreferences(TELEGRAM_PREFS, MODE_PRIVATE)
                .edit()
                .remove(SELECTED_GROUPS)
                .apply();
        MonitorStatusStore.setServiceRunning(this, false);
        MonitorStatusStore.setTelegramState(this, TelegramClientManager.State.CLOSED);
        clientManager.logOut();
        refreshProfile();
    }

    private void showTermsDialog() {
        showInformationDialog(R.string.profile_terms_title, R.string.profile_terms_message);
    }

    private void refreshErrorSummary() {
        if (errorSummary == null) {
            return;
        }
        int count = AppErrorStore.getAll(this).size();
        errorSummary.setText(count == 0
                ? getString(R.string.profile_errors_none)
                : getString(R.string.profile_errors_count, count));
    }

    private void showErrorHistoryDialog() {
        List<AppErrorStore.Entry> errors = AppErrorStore.getAll(this);
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.profile_errors_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(errors.isEmpty()
                ? getString(R.string.profile_errors_dialog_empty)
                : formatErrorHistory(errors));
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(14);
        message.setPadding(0, dp(10), 0, dp(12));
        message.setMaxLines(12);
        message.setVerticalScrollBarEnabled(true);
        message.setMovementMethod(new ScrollingMovementMethod());
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        if (!errors.isEmpty()) {
            TextView clear = createDialogAction(R.string.profile_errors_clear);
            clear.setTextColor(getColor(R.color.danger));
            clear.setOnClickListener(view -> {
                AppErrorStore.clear(this);
                dialog.dismiss();
                refreshErrorSummary();
            });
            actions.addView(clear);
        }
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
        content.addView(actions);

        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = getResources().getDisplayMetrics().widthPixels - dp(44);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.65f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private String formatErrorHistory(List<AppErrorStore.Entry> errors) {
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("pt", "BR"));
        StringBuilder history = new StringBuilder();
        for (int index = 0; index < errors.size(); index++) {
            AppErrorStore.Entry error = errors.get(index);
            if (index > 0) {
                history.append("\n\n");
            }
            history.append(formatter.format(new Date(error.timestamp)))
                    .append(" · ")
                    .append(error.source)
                    .append("\n")
                    .append(error.message);
        }
        return history.toString();
    }

    private void showInformationDialog(int titleResource, int messageResource) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(messageResource);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(8), 0, dp(16));
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
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

    private void showConfirmationDialog(int titleResource, int messageResource, int confirmResource,
                                        boolean dangerous, Runnable onConfirm) {
        showConfirmationDialog(titleResource, getString(messageResource), confirmResource, dangerous, onConfirm);
    }

    private void showConfirmationDialog(int titleResource, String messageText, int confirmResource,
                                        boolean dangerous, Runnable onConfirm) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(8), 0, dp(16));
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView confirm = createDialogAction(confirmResource);
        confirm.setTextColor(getColor(dangerous ? R.color.danger : R.color.action));
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
        actions.addView(confirm);
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

    private TextView createDialogAction(int textResource) {
        TextView action = new TextView(this);
        action.setText(textResource);
        action.setTextColor(getColor(R.color.action));
        action.setTextSize(15);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(14), dp(9), dp(14), dp(9));
        return action;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
