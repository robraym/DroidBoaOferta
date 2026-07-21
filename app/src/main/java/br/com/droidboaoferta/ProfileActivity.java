package br.com.droidboaoferta;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ProfileActivity extends AlertouActivity implements TelegramClientManager.Listener {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final int REQUEST_NOTIFICATIONS = 1201;
    private static final long ACTIVE_SYNC_REFRESH_MS = 15_000L;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSettingsControls();
            refreshErrorSummary();
            refreshSyncSummary();
        }
    };
    private final Handler syncRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable activeSyncRefresh = new Runnable() {
        @Override
        public void run() {
            if (clientManager != null) {
                clientManager.refreshCloudBackupSoon();
                syncRefreshHandler.postDelayed(this, ACTIVE_SYNC_REFRESH_MS);
            }
        }
    };

    private TelegramClientManager clientManager;
    private TextView avatarText;
    private TextView profileName;
    private TextView profileSummary;
    private TextView profileStatus;
    private TextView syncSummary;
    private TextView monitorStatusTitle;
    private TextView monitorStatusSummary;
    private TextView themeSummary;
    private TextView accentColorSummary;
    private TextView alertSoundSummary;
    private TextView navigationAnimationSummary;
    private TextView errorTitle;
    private TextView errorSummary;
    private ImageButton monitorToggle;
    private InterestRepository interestRepository;
    private LinearLayout accountCard;
    private LinearLayout syncRow;
    private View accountChevron;
    private MediaPlayer alertSoundPreview;
    private Dialog alertSoundDialog;
    private ActivityResultLauncher<Intent> alertSoundPickerLauncher;
    private float alertSoundPreviewVolume = 0.72f;
    private boolean alertSoundDialogWasOpenWhenPicking;

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
        syncSummary = findViewById(R.id.text_sync_summary);
        monitorStatusTitle = findViewById(R.id.text_monitor_status_title);
        monitorStatusSummary = findViewById(R.id.text_monitor_status_summary);
        themeSummary = findViewById(R.id.text_theme_summary);
        accentColorSummary = findViewById(R.id.text_accent_color_summary);
        alertSoundSummary = findViewById(R.id.text_alert_sound_summary);
        navigationAnimationSummary = findViewById(R.id.text_navigation_animation_summary);
        errorTitle = findViewById(R.id.text_error_title);
        errorSummary = findViewById(R.id.text_error_summary);
        monitorToggle = findViewById(R.id.button_monitor_toggle);
        accountCard = findViewById(R.id.card_telegram_account);
        syncRow = findViewById(R.id.row_sync);
        accountChevron = findViewById(R.id.image_account_chevron);
        alertSoundPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        alertSoundDialogWasOpenWhenPicking = false;
                        return;
                    }
                    Uri uri = result.getData().getData();
                    if (uri == null) {
                        alertSoundDialogWasOpenWhenPicking = false;
                        return;
                    }
                    try {
                        String selectedSound = AlertSoundController.saveCustomSound(this, uri);
                        refreshSettingsControls();
                        if (alertSoundDialogWasOpenWhenPicking) {
                            alertSoundDialogWasOpenWhenPicking = false;
                            if (alertSoundDialog != null && alertSoundDialog.isShowing()) {
                                alertSoundDialog.dismiss();
                            }
                            showAlertSoundDialog();
                        }
                        playAlertSoundPreview(selectedSound);
                    } catch (Exception exception) {
                        alertSoundDialogWasOpenWhenPicking = false;
                        AppErrorStore.recordSerious(
                                this,
                                getString(R.string.alert_sound_title),
                                getString(R.string.alert_sound_custom_error)
                        );
                        refreshErrorSummary();
                    }
                }
        );

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        accountCard.setOnClickListener(view -> {
            if (clientManager.getState() == TelegramClientManager.State.READY) {
                showTelegramAccountDialog();
            } else {
                startActivity(new Intent(this, TelegramSetupActivity.class));
            }
        });
        findViewById(R.id.row_theme).setOnClickListener(view -> showThemeDialog());
        findViewById(R.id.row_accent_color).setOnClickListener(
                view -> showAccentColorDialog()
        );
        findViewById(R.id.row_alert_sound).setOnClickListener(view -> showAlertSoundDialog());
        findViewById(R.id.row_navigation_animation).setOnClickListener(
                view -> showNavigationAnimationDialog()
        );
        monitorToggle.setOnClickListener(view -> toggleMonitor());
        findViewById(R.id.row_terms).setOnClickListener(view -> showTermsDialog());
        findViewById(R.id.row_errors).setOnClickListener(view -> showErrorHistoryDialog());
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientManager.setListener(this);
        clientManager.start(this);
        clientManager.refreshCloudBackupSoon();
        IntentFilter statusFilter = new IntentFilter(MonitorStatusStore.ACTION_STATUS_CHANGED);
        statusFilter.addAction(AppErrorStore.ACTION_ERRORS_CHANGED);
        statusFilter.addAction(TelegramClientManager.ACTION_CLOUD_SYNC_CHANGED);
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                statusFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        syncRefreshHandler.removeCallbacks(activeSyncRefresh);
        syncRefreshHandler.postDelayed(activeSyncRefresh, ACTIVE_SYNC_REFRESH_MS);
        refreshProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfile();
    }

    @Override
    protected void onStop() {
        syncRefreshHandler.removeCallbacks(activeSyncRefresh);
        unregisterReceiver(statusReceiver);
        clientManager.clearListener(this);
        releaseAlertSoundPreview();
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
        accountCard.setClickable(true);
        accountCard.setFocusable(true);
        accountChevron.setVisibility(View.VISIBLE);
        syncRow.setVisibility(connected ? View.VISIBLE : View.GONE);
        refreshSyncSummary();
        refreshErrorSummary();
        refreshSettingsControls();
    }

    private void refreshSettingsControls() {
        int groupCount = getSelectedGroupCount();
        List<Interest> interests = interestRepository.getAll();
        boolean monitorEnabled = isMonitorEnabled();
        themeSummary.setText(ThemeController.getSummaryResource(ThemeController.getSavedMode(this)));
        accentColorSummary.setText(AccentColorController.getSummaryResource(
                AccentColorController.getSavedMode(this)
        ));
        alertSoundSummary.setText(AlertSoundController.getProfileSummary(this));
        navigationAnimationSummary.setText(NavigationAnimationController.getSummaryResource(
                NavigationAnimationController.getSavedMode(this)
        ));

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

    private void showAccentColorDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(12));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.accent_color_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView previewHint = new TextView(this);
        previewHint.setText(R.string.accent_color_preview_hint);
        previewHint.setTextColor(getColor(R.color.text_secondary));
        previewHint.setTextSize(13);
        previewHint.setPadding(0, dp(7), 0, dp(7));
        content.addView(previewHint);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(16), dp(12), dp(16), dp(12));
        preview.setBackgroundResource(R.drawable.bg_input);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(112)
        ));

        TextView previewTitle = new TextView(this);
        previewTitle.setText(R.string.accent_color_preview_title);
        previewTitle.setTextColor(getColor(R.color.text_primary));
        previewTitle.setTextSize(18);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        preview.addView(previewTitle);

        View previewIndicator = new View(this);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(dp(92), dp(7));
        indicatorParams.topMargin = dp(9);
        preview.addView(previewIndicator, indicatorParams);

        TextView previewAction = new TextView(this);
        previewAction.setText(R.string.accent_color_preview_action);
        previewAction.setTextSize(13);
        previewAction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(148), dp(34));
        actionParams.topMargin = dp(9);
        preview.addView(previewAction, actionParams);

        GridLayout options = new GridLayout(this);
        options.setColumnCount(2);
        options.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        options.setUseDefaultMargins(false);
        options.setPadding(0, dp(6), 0, dp(3));
        content.addView(options, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        String[] modes = new String[]{
                AccentColorController.MODE_BLUE,
                AccentColorController.MODE_GREEN,
                AccentColorController.MODE_YELLOW,
                AccentColorController.MODE_PURPLE,
                AccentColorController.MODE_ORANGE,
                AccentColorController.MODE_PINK,
                AccentColorController.MODE_TEAL,
                AccentColorController.MODE_RED,
                AccentColorController.MODE_MATRIX,
                AccentColorController.MODE_ELECTRIC,
                AccentColorController.MODE_INDIGO,
                AccentColorController.MODE_MAGENTA
        };
        int[] labels = new int[]{
                R.string.accent_color_blue,
                R.string.accent_color_green,
                R.string.accent_color_yellow,
                R.string.accent_color_purple,
                R.string.accent_color_orange,
                R.string.accent_color_pink,
                R.string.accent_color_teal,
                R.string.accent_color_red,
                R.string.accent_color_matrix,
                R.string.accent_color_electric,
                R.string.accent_color_indigo,
                R.string.accent_color_magenta
        };
        String initialMode = AccentColorController.getSavedMode(this);
        String[] selectedMode = new String[]{initialMode};
        List<TextView> optionViews = new ArrayList<>();
        for (int index = 0; index < modes.length; index++) {
            TextView option = createAccentColorOption(
                    labels[index],
                    modes[index],
                    modes[index].equals(selectedMode[0])
            );
            final int optionIndex = index;
            option.setOnClickListener(view -> {
                selectedMode[0] = modes[optionIndex];
                AccentColorController.saveMode(this, selectedMode[0]);
                accentColorSummary.setText(
                        AccentColorController.getSummaryResource(selectedMode[0])
                );
                for (int current = 0; current < optionViews.size(); current++) {
                    updateAccentColorOption(
                            optionViews.get(current),
                            labels[current],
                            modes[current],
                            current == optionIndex
                    );
                }
                updateAccentColorPreview(
                        previewTitle,
                        previewIndicator,
                        previewAction,
                        selectedMode[0]
                );
            });
            optionViews.add(option);
            GridLayout.LayoutParams optionParams = new GridLayout.LayoutParams();
            optionParams.width = 0;
            optionParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            optionParams.columnSpec = GridLayout.spec(index % 2, 1, 1f);
            optionParams.rowSpec = GridLayout.spec(index / 2);
            optionParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            option.setLayoutParams(optionParams);
            options.addView(option);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
        content.addView(actions);

        dialog.setOnDismissListener(ignored -> {
            if (!initialMode.equals(AccentColorController.getSavedMode(this)) && !isFinishing()) {
                recreate();
            }
        });
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
            params.width = getResources().getDisplayMetrics().widthPixels - dp(24);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.65f;
            shownWindow.setAttributes(params);
            shownWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        updateAccentColorPreview(previewTitle, previewIndicator, previewAction, selectedMode[0]);
    }

    private TextView createAccentColorOption(int labelResource, String mode, boolean selected) {
        TextView option = new TextView(this);
        option.setTextSize(14);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(dp(43));
        option.setMaxLines(1);
        option.setEllipsize(TextUtils.TruncateAt.END);
        option.setCompoundDrawablePadding(dp(8));
        option.setPadding(dp(10), dp(5), dp(8), dp(5));
        updateAccentColorOption(option, labelResource, mode, selected);
        return option;
    }

    private void updateAccentColorOption(TextView option, int labelResource, String mode,
                                         boolean selected) {
        int primaryColor = AccentColorController.getPrimaryColor(this, mode);
        option.setText(selected
                ? getString(R.string.theme_selected_format, getString(labelResource))
                : getString(labelResource));
        option.setTextColor(selected ? primaryColor : getColor(R.color.text_primary));
        option.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);

        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(primaryColor);
        swatch.setSize(dp(18), dp(18));
        option.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch, null, null, null);

        GradientDrawable background = new GradientDrawable();
        background.setColor(selected
                ? AccentColorController.getSoftColor(this, mode)
                : Color.TRANSPARENT);
        background.setCornerRadius(dp(15));
        option.setBackground(background);
    }

    private void updateAccentColorPreview(TextView title, View indicator, TextView action,
                                          String mode) {
        int primaryColor = AccentColorController.getPrimaryColor(this, mode);
        int softColor = AccentColorController.getSoftColor(this, mode);
        title.setTextColor(primaryColor);
        setRoundedColorBackground(indicator, primaryColor, 8);
        action.setTextColor(primaryColor);
        setRoundedColorBackground(action, softColor, 18);
    }

    private void setRoundedColorBackground(View view, int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        view.setBackground(background);
    }

    private void showNavigationAnimationDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(12));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.navigation_animation_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView previewHint = new TextView(this);
        previewHint.setText(R.string.navigation_animation_preview_hint);
        previewHint.setTextColor(getColor(R.color.text_secondary));
        previewHint.setTextSize(13);
        previewHint.setPadding(0, dp(8), 0, dp(7));
        content.addView(previewHint);

        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundResource(R.drawable.bg_input);
        preview.setClipChildren(true);
        preview.setClickable(true);
        preview.setFocusable(true);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(136)
        ));

        LinearLayout previewPanel = new LinearLayout(this);
        previewPanel.setOrientation(LinearLayout.VERTICAL);
        previewPanel.setGravity(Gravity.CENTER_VERTICAL);
        previewPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        previewPanel.setBackgroundResource(R.drawable.bg_card_compact);
        FrameLayout.LayoutParams previewPanelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        previewPanelParams.setMargins(dp(12), dp(8), dp(12), dp(42));
        preview.addView(previewPanel, previewPanelParams);

        TextView fixedSearch = new TextView(this);
        fixedSearch.setText(R.string.search_hint);
        fixedSearch.setTextColor(getColor(R.color.text_secondary));
        fixedSearch.setTextSize(12);
        fixedSearch.setGravity(Gravity.CENTER_VERTICAL);
        fixedSearch.setPadding(dp(12), 0, dp(12), 0);
        fixedSearch.setBackgroundResource(R.drawable.bg_search_bar);
        FrameLayout.LayoutParams fixedSearchParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(27),
                Gravity.BOTTOM
        );
        fixedSearchParams.setMargins(dp(36), 0, dp(36), dp(8));
        preview.addView(fixedSearch, fixedSearchParams);

        TextView previewTitle = new TextView(this);
        previewTitle.setText(R.string.navigation_animation_preview_home);
        previewTitle.setTextColor(getColor(R.color.text_primary));
        previewTitle.setTextSize(19);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewPanel.addView(previewTitle);

        View previewLinePrimary = createAnimationPreviewLine(getColor(R.color.action), 0.74f);
        LinearLayout.LayoutParams primaryLineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
        );
        primaryLineParams.setMargins(0, dp(12), dp(42), 0);
        previewPanel.addView(previewLinePrimary, primaryLineParams);

        View previewLineSecondary = createAnimationPreviewLine(getColor(R.color.divider), 0.6f);
        LinearLayout.LayoutParams secondaryLineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(7)
        );
        secondaryLineParams.setMargins(0, dp(8), dp(76), 0);
        previewPanel.addView(previewLineSecondary, secondaryLineParams);

        GridLayout options = new GridLayout(this);
        options.setColumnCount(2);
        options.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        options.setUseDefaultMargins(false);
        options.setPadding(0, dp(5), 0, dp(3));
        content.addView(options, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        String[] modes = new String[]{
                NavigationAnimationController.MODE_FADE,
                NavigationAnimationController.MODE_SLIDE,
                NavigationAnimationController.MODE_RISE,
                NavigationAnimationController.MODE_DROP,
                NavigationAnimationController.MODE_ZOOM,
                NavigationAnimationController.MODE_ZOOM_OUT,
                NavigationAnimationController.MODE_TURN,
                NavigationAnimationController.MODE_BOUNCE,
                NavigationAnimationController.MODE_GLIDE_ZOOM,
                NavigationAnimationController.MODE_NONE
        };
        int[] labels = new int[]{
                R.string.navigation_animation_fade,
                R.string.navigation_animation_slide,
                R.string.navigation_animation_rise,
                R.string.navigation_animation_drop,
                R.string.navigation_animation_zoom,
                R.string.navigation_animation_zoom_out,
                R.string.navigation_animation_turn,
                R.string.navigation_animation_bounce,
                R.string.navigation_animation_glide_zoom,
                R.string.navigation_animation_none
        };
        List<TextView> optionViews = new ArrayList<>();
        String savedMode = NavigationAnimationController.getSavedMode(this);
        for (int index = 0; index < modes.length; index++) {
            TextView option = createNavigationAnimationOption(
                    labels[index],
                    modes[index].equals(savedMode)
            );
            final int optionIndex = index;
            option.setOnClickListener(view -> {
                String selectedMode = modes[optionIndex];
                NavigationAnimationController.saveMode(this, selectedMode);
                navigationAnimationSummary.setText(
                        NavigationAnimationController.getSummaryResource(selectedMode)
                );
                for (int current = 0; current < optionViews.size(); current++) {
                    updateNavigationAnimationOption(
                            optionViews.get(current),
                            labels[current],
                            current == optionIndex
                    );
                }
                playNavigationAnimationPreview(previewPanel, previewTitle, selectedMode);
            });
            optionViews.add(option);
            GridLayout.LayoutParams optionParams = new GridLayout.LayoutParams();
            optionParams.width = 0;
            optionParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            optionParams.columnSpec = GridLayout.spec(index % 2, 1, 1f);
            optionParams.rowSpec = GridLayout.spec(index / 2);
            optionParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            option.setLayoutParams(optionParams);
            options.addView(option);
        }

        preview.setOnClickListener(view -> playNavigationAnimationPreview(
                previewPanel,
                previewTitle,
                NavigationAnimationController.getSavedMode(this)
        ));

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
            params.width = getResources().getDisplayMetrics().widthPixels - dp(24);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.65f;
            shownWindow.setAttributes(params);
            shownWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        preview.post(() -> playNavigationAnimationPreview(
                previewPanel,
                previewTitle,
                NavigationAnimationController.getSavedMode(this)
        ));
    }

    private View createAnimationPreviewLine(int color, float alpha) {
        View line = new View(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        line.setBackground(background);
        line.setAlpha(alpha);
        return line;
    }

    private TextView createNavigationAnimationOption(int textResource, boolean selected) {
        TextView option = new TextView(this);
        option.setTextSize(14);
        option.setGravity(Gravity.CENTER);
        option.setMinHeight(dp(43));
        option.setMaxLines(2);
        option.setEllipsize(TextUtils.TruncateAt.END);
        option.setPadding(dp(8), dp(5), dp(8), dp(5));
        updateNavigationAnimationOption(option, textResource, selected);
        return option;
    }

    private void updateNavigationAnimationOption(TextView option, int textResource,
                                                 boolean selected) {
        option.setText(selected
                ? getString(R.string.theme_selected_format, getString(textResource))
                : getString(textResource));
        option.setTextColor(getColor(selected ? R.color.action : R.color.text_primary));
        option.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        option.setBackgroundResource(selected
                ? R.drawable.bg_button_secondary
                : R.drawable.bg_row_pressed);
    }

    private void playNavigationAnimationPreview(LinearLayout panel, TextView title, String mode) {
        panel.animate().cancel();
        panel.animate().setInterpolator(new android.view.animation.DecelerateInterpolator());
        panel.setAlpha(1f);
        panel.setTranslationX(0f);
        panel.setTranslationY(0f);
        panel.setScaleX(1f);
        panel.setScaleY(1f);
        panel.setRotationY(0f);

        if (NavigationAnimationController.MODE_NONE.equals(mode)) {
            toggleAnimationPreviewTitle(title);
            return;
        }
        if (NavigationAnimationController.MODE_SLIDE.equals(mode)) {
            float distance = Math.max(panel.getWidth() * 0.24f, dp(56));
            panel.animate()
                    .alpha(0.55f)
                    .translationX(-distance * 0.45f)
                    .setDuration(90L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setTranslationX(distance);
                        panel.animate()
                                .alpha(1f)
                                .translationX(0f)
                                .setDuration(210L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_ZOOM.equals(mode)) {
            panel.animate()
                    .alpha(0.45f)
                    .scaleX(1.03f)
                    .scaleY(1.03f)
                    .setDuration(85L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setScaleX(0.94f);
                        panel.setScaleY(0.94f);
                        panel.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(190L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_ZOOM_OUT.equals(mode)) {
            panel.animate()
                    .alpha(0.45f)
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(85L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setScaleX(1.09f);
                        panel.setScaleY(1.09f);
                        panel.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(200L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_TURN.equals(mode)) {
            panel.setCameraDistance(8000f * getResources().getDisplayMetrics().density);
            panel.animate()
                    .alpha(0.35f)
                    .rotationY(-14f)
                    .setDuration(100L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setRotationY(18f);
                        panel.animate()
                                .alpha(1f)
                                .rotationY(0f)
                                .setDuration(240L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_BOUNCE.equals(mode)) {
            panel.animate()
                    .alpha(0.35f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(90L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setScaleX(0.84f);
                        panel.setScaleY(0.84f);
                        panel.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.35f))
                                .setDuration(320L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_GLIDE_ZOOM.equals(mode)) {
            float distance = Math.max(panel.getWidth() * 0.18f, dp(44));
            panel.animate()
                    .alpha(0.35f)
                    .translationX(-distance * 0.45f)
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(90L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setTranslationX(distance);
                        panel.setScaleX(0.94f);
                        panel.setScaleY(0.94f);
                        panel.animate()
                                .alpha(1f)
                                .translationX(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(240L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_RISE.equals(mode)) {
            panel.animate()
                    .alpha(0.5f)
                    .translationY(-dp(9))
                    .setDuration(85L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setTranslationY(dp(34));
                        panel.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(220L)
                                .start();
                    })
                    .start();
            return;
        }
        if (NavigationAnimationController.MODE_DROP.equals(mode)) {
            panel.animate()
                    .alpha(0.5f)
                    .translationY(dp(9))
                    .setDuration(85L)
                    .withEndAction(() -> {
                        toggleAnimationPreviewTitle(title);
                        panel.setTranslationY(-dp(34));
                        panel.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(220L)
                                .start();
                    })
                    .start();
            return;
        }
        panel.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction(() -> {
                    toggleAnimationPreviewTitle(title);
                    panel.animate()
                            .alpha(1f)
                            .setDuration(180L)
                            .start();
                })
                .start();
    }

    private void toggleAnimationPreviewTitle(TextView title) {
        boolean showingHome = getString(R.string.navigation_animation_preview_home)
                .contentEquals(title.getText());
        title.setText(showingHome
                ? R.string.navigation_animation_preview_telegram
                : R.string.navigation_animation_preview_home);
    }

    private void showAlertSoundDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(12));
        content.setBackgroundResource(R.drawable.bg_dialog);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setPadding(0, 0, 0, dp(8));
        TextView title = new TextView(this);
        title.setText(R.string.alert_sound_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        TextView add = new TextView(this);
        add.setText("+");
        add.setGravity(Gravity.CENTER);
        add.setTextColor(getColor(R.color.text_primary));
        add.setTextSize(30);
        add.setPadding(dp(12), 0, 0, 0);
        add.setOnClickListener(view -> {
            alertSoundDialogWasOpenWhenPicking = true;
            openAlertSoundPicker();
        });
        titleRow.addView(title);
        titleRow.addView(add);
        content.addView(titleRow);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, 0, 0, dp(10));
        String savedSound = AlertSoundController.getSavedSound(this);
        String[] sounds = AlertSoundController.getKeys();
        List<LinearLayout> soundOptions = new ArrayList<>();
        List<LinearLayout> customOptions = new ArrayList<>();
        List<AlertSoundController.CustomSound> savedCustomSounds =
                AlertSoundController.getCustomSounds(this);
        View customSectionHeader = null;
        if (!savedCustomSounds.isEmpty()) {
            customSectionHeader = createSoundSectionHeader(R.string.alert_sound_custom_section);
            options.addView(customSectionHeader);
        }
        View finalCustomSectionHeader = customSectionHeader;
        for (AlertSoundController.CustomSound customSound : savedCustomSounds) {
            String customKey = customSound.getKey();
            LinearLayout customOption = createCustomSoundRow(
                    customKey,
                    customKey.equals(savedSound)
            );
            customOption.setTag(customKey);
            customOptions.add(customOption);
            TextView customRemove = customOption.findViewWithTag("custom_remove");
            LinearLayout finalCustomOption = customOption;
            customOption.setOnClickListener(view -> {
                AlertSoundController.saveExistingCustomSound(this, customKey);
                alertSoundSummary.setText(AlertSoundController.getProfileSummary(this));
                String currentSound = AlertSoundController.getSavedSound(this);
                for (LinearLayout customSoundOption : customOptions) {
                    String optionSound = (String) customSoundOption.getTag();
                    updateCustomSoundRow(
                            customSoundOption,
                            customSoundOption.findViewWithTag("custom_radio"),
                            customSoundOption.findViewWithTag("custom_file_name"),
                            optionSound,
                            optionSound.equals(currentSound)
                    );
                }
                for (LinearLayout soundOption : soundOptions) {
                    String optionSound = (String) soundOption.getTag();
                    updateDialogOptionRow(
                            soundOption,
                            soundOption.findViewWithTag("sound_radio"),
                            soundOption.findViewWithTag("sound_label"),
                            AlertSoundController.getLabel(this, optionSound),
                            optionSound.equals(currentSound)
                    );
                }
                playAlertSoundPreview(customKey);
            });
            customRemove.setOnClickListener(view -> {
                AlertSoundController.removeCustomSound(this, customKey);
                alertSoundSummary.setText(AlertSoundController.getProfileSummary(this));
                options.removeView(finalCustomOption);
                customOptions.remove(finalCustomOption);
                if (customOptions.isEmpty() && finalCustomSectionHeader != null) {
                    options.removeView(finalCustomSectionHeader);
                }
                String currentSound = AlertSoundController.getSavedSound(this);
                for (LinearLayout customSoundOption : customOptions) {
                    String optionSound = (String) customSoundOption.getTag();
                    updateCustomSoundRow(
                            customSoundOption,
                            customSoundOption.findViewWithTag("custom_radio"),
                            customSoundOption.findViewWithTag("custom_file_name"),
                            optionSound,
                            optionSound.equals(currentSound)
                    );
                }
                for (LinearLayout soundOption : soundOptions) {
                    String optionSound = (String) soundOption.getTag();
                    updateDialogOptionRow(
                            soundOption,
                            soundOption.findViewWithTag("sound_radio"),
                            soundOption.findViewWithTag("sound_label"),
                            AlertSoundController.getLabel(this, optionSound),
                            optionSound.equals(currentSound)
                    );
                }
            });
            options.addView(customOption);
        }
        options.addView(createSoundSectionHeader(R.string.alert_sound_builtin_section));

        GridLayout builtInGrid = new GridLayout(this);
        builtInGrid.setColumnCount(2);
        builtInGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        builtInGrid.setUseDefaultMargins(false);

        for (int soundIndex = 0; soundIndex < sounds.length; soundIndex++) {
            String sound = sounds[soundIndex];
            LinearLayout option = createDialogOption(
                    AlertSoundController.getLabel(this, sound),
                    sound.equals(savedSound)
            );
            option.setTag(sound);
            soundOptions.add(option);
            option.setOnClickListener(view -> {
                AlertSoundController.saveSound(this, sound);
                alertSoundSummary.setText(AlertSoundController.getProfileSummary(this));
                String currentSound = AlertSoundController.getSavedSound(this);
                for (LinearLayout soundOption : soundOptions) {
                    String optionSound = (String) soundOption.getTag();
                    updateDialogOptionRow(
                            soundOption,
                            soundOption.findViewWithTag("sound_radio"),
                            soundOption.findViewWithTag("sound_label"),
                            AlertSoundController.getLabel(this, optionSound),
                            optionSound.equals(currentSound)
                    );
                }
                for (LinearLayout customSoundOption : customOptions) {
                    String optionSound = (String) customSoundOption.getTag();
                    updateCustomSoundRow(
                            customSoundOption,
                            customSoundOption.findViewWithTag("custom_radio"),
                            customSoundOption.findViewWithTag("custom_file_name"),
                            optionSound,
                            optionSound.equals(currentSound)
                    );
                }
                playAlertSoundPreview(sound);
            });
            GridLayout.LayoutParams optionParams = new GridLayout.LayoutParams();
            optionParams.width = 0;
            optionParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            optionParams.columnSpec = GridLayout.spec(soundIndex % 2, 1, 1f);
            optionParams.rowSpec = GridLayout.spec(soundIndex / 2);
            optionParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            option.setLayoutParams(optionParams);
            builtInGrid.addView(option);
        }
        options.addView(builtInGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.addView(options);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(500), (int) (getResources().getDisplayMetrics().heightPixels * 0.62f))
        );
        content.addView(scrollView, scrollParams);

        content.addView(createAlertSoundVolumeBar());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
        content.addView(actions);

        dialog.setContentView(content);
        dialog.setOnDismissListener(value -> {
            if (alertSoundDialog == dialog) {
                alertSoundDialog = null;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        alertSoundDialog = dialog;
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(shownWindow.getAttributes());
            params.width = getResources().getDisplayMetrics().widthPixels - dp(24);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.65f;
            shownWindow.setAttributes(params);
            shownWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void playAlertSoundPreview(String sound) {
        releaseAlertSoundPreview();
        if (AlertSoundController.isCustomSound(sound)) {
            alertSoundPreview = MediaPlayer.create(this, AlertSoundController.getSoundUri(this, sound));
        } else {
            alertSoundPreview = MediaPlayer.create(this, AlertSoundController.getRawResource(sound));
        }
        if (alertSoundPreview == null) {
            return;
        }
        alertSoundPreview.setVolume(alertSoundPreviewVolume, alertSoundPreviewVolume);
        alertSoundPreview.setOnCompletionListener(player -> releaseAlertSoundPreview());
        alertSoundPreview.start();
    }

    private void releaseAlertSoundPreview() {
        if (alertSoundPreview == null) {
            return;
        }
        alertSoundPreview.setOnCompletionListener(null);
        alertSoundPreview.release();
        alertSoundPreview = null;
    }

    private LinearLayout createDialogOption(String text, boolean selected) {
        LinearLayout option = new LinearLayout(this);
        option.setOrientation(LinearLayout.HORIZONTAL);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinimumHeight(dp(44));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(1), 0, dp(1));
        option.setLayoutParams(params);

        View radio = createSoundRadio();
        radio.setTag("sound_radio");
        TextView label = new TextView(this);
        label.setTag("sound_label");
        label.setSingleLine(false);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        label.setLayoutParams(labelParams);
        option.addView(radio);
        option.addView(label);
        updateDialogOptionRow(option, radio, label, text, selected);
        return option;
    }

    private LinearLayout createCustomSoundRow(String sound, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(1), 0, dp(1));
        card.setLayoutParams(params);

        View radio = createSoundRadio();
        radio.setTag("custom_radio");

        TextView fileName = new TextView(this);
        fileName.setTag("custom_file_name");
        fileName.setSingleLine(true);
        fileName.setMaxLines(1);
        fileName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fileNameParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        fileName.setLayoutParams(fileNameParams);

        TextView remove = new TextView(this);
        remove.setTag("custom_remove");
        remove.setText("—");
        remove.setGravity(Gravity.CENTER);
        remove.setTextColor(getColor(R.color.danger));
        remove.setTextSize(24);
        remove.setTypeface(Typeface.DEFAULT_BOLD);
        remove.setPadding(dp(12), 0, 0, 0);

        card.addView(radio);
        card.addView(fileName);
        card.addView(remove);
        updateCustomSoundRow(card, radio, fileName, sound, selected);
        return card;
    }

    private FrameLayout createSoundRadio() {
        FrameLayout container = new FrameLayout(this);
        container.setPadding(0, 0, dp(6), 0);
        container.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(36)));

        View ring = new View(this);
        ring.setTag("radio_ring");
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(20), dp(20));
        ringParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        ring.setLayoutParams(ringParams);
        container.addView(ring);

        View dot = new View(this);
        dot.setTag("radio_dot");
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(8), dp(8));
        dotParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        dotParams.leftMargin = dp(6);
        dot.setLayoutParams(dotParams);
        container.addView(dot);

        return container;
    }

    private LinearLayout createSoundSectionHeader(int textResource) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(4));

        TextView label = new TextView(this);
        label.setText(textResource);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(label);

        View line = new View(this);
        line.setBackgroundResource(R.drawable.bg_alert_sound_section_line);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                0,
                dp(1),
                1f
        );
        lineParams.setMargins(dp(10), 0, 0, 0);
        header.addView(line, lineParams);
        return header;
    }

    private void updateDialogOptionRow(LinearLayout option, View radio, TextView label,
                                       String text, boolean selected) {
        updateSoundRadio(radio, selected);
        label.setText(text);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(14.0f);
        label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        option.setPadding(dp(5), dp(4), dp(7), dp(4));
        option.setBackgroundResource(selected
                ? R.drawable.bg_alert_sound_selected_option
                : R.drawable.bg_row_pressed);
    }

    private void updateCustomSoundRow(LinearLayout card, View radio, TextView fileName,
                                      String sound,
                                      boolean selected) {
        updateSoundRadio(radio, selected);
        fileName.setText(AlertSoundController.getCustomDisplayName(this, sound));
        fileName.setTextColor(getColor(R.color.text_primary));
        fileName.setTextSize(14.0f);
        fileName.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        card.setPadding(dp(5), dp(4), dp(7), dp(4));
        card.setBackgroundResource(selected
                ? R.drawable.bg_alert_sound_selected_option
                : R.drawable.bg_row_pressed);
    }

    private void updateSoundRadio(View radio, boolean selected) {
        View ring = radio.findViewWithTag("radio_ring");
        View dot = radio.findViewWithTag("radio_dot");
        int strokeColor = getColor(selected ? R.color.action : R.color.text_secondary);
        GradientDrawable ringDrawable = new GradientDrawable();
        ringDrawable.setShape(GradientDrawable.OVAL);
        ringDrawable.setColor(Color.TRANSPARENT);
        ringDrawable.setStroke(dp(2), strokeColor);
        ring.setBackground(ringDrawable);

        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(getColor(R.color.action));
        dot.setBackground(dotDrawable);
        dot.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }

    private LinearLayout createAlertSoundVolumeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(9), dp(12), dp(9));
        bar.setBackgroundResource(R.drawable.bg_alert_sound_volume_bar);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        barParams.setMargins(0, dp(8), 0, dp(2));
        bar.setLayoutParams(barParams);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_volume_preview);
        icon.setColorFilter(getColor(R.color.action));
        icon.setPadding(0, 0, dp(10), 0);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(28)));

        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(alertSoundPreviewVolume * 100f));
        volume.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alertSoundPreviewVolume = Math.max(0f, Math.min(1f, progress / 100f));
                if (alertSoundPreview != null) {
                    alertSoundPreview.setVolume(alertSoundPreviewVolume, alertSoundPreviewVolume);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        bar.addView(icon);
        bar.addView(volume);
        return bar;
    }

    private void openAlertSoundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        alertSoundPickerLauncher.launch(intent);
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

    private void showTelegramAccountDialog() {
        if (clientManager.getState() != TelegramClientManager.State.READY) {
            startActivity(new Intent(this, TelegramSetupActivity.class));
            return;
        }

        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.profile_telegram_details_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView summary = new TextView(this);
        summary.setText(R.string.profile_telegram_details_summary);
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setTextSize(14);
        summary.setPadding(0, dp(5), 0, dp(14));
        content.addView(summary);

        String accountName = clientManager.getAccountName();
        String accountPhone = clientManager.getAccountPhone();
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), dp(4), dp(12), dp(4));
        details.setBackgroundResource(R.drawable.bg_card_compact);
        details.addView(createTelegramDetailRow(
                R.string.profile_telegram_details_name,
                TextUtils.isEmpty(accountName)
                        ? getString(R.string.profile_telegram_details_unavailable)
                        : accountName
        ));
        details.addView(createTelegramDetailDivider());
        details.addView(createTelegramDetailRow(
                R.string.profile_telegram_details_phone,
                TextUtils.isEmpty(accountPhone)
                        ? getString(R.string.profile_telegram_details_unavailable)
                        : getString(R.string.profile_telegram_phone, accountPhone)
        ));
        details.addView(createTelegramDetailDivider());
        details.addView(createTelegramDetailRow(
                R.string.profile_telegram_details_status,
                getString(R.string.profile_telegram_status_connected)
        ));
        details.addView(createTelegramDetailDivider());
        details.addView(createTelegramDetailRow(
                R.string.profile_telegram_details_version,
                BuildConfig.VERSION_NAME
        ));
        details.addView(createTelegramDetailDivider());
        details.addView(createTelegramDetailRow(
                R.string.profile_telegram_details_build,
                String.valueOf(BuildConfig.VERSION_CODE)
        ));
        content.addView(details);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
        TextView disconnect = createDialogAction(R.string.profile_action_disconnect);
        disconnect.setTextColor(getColor(R.color.danger));
        disconnect.setOnClickListener(view -> {
            dialog.dismiss();
            showLogoutConfirmation();
        });
        actions.addView(disconnect);
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

    private LinearLayout createTelegramDetailRow(int labelResource, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(2), dp(7), dp(2), dp(7));

        TextView label = new TextView(this);
        label.setText(labelResource);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(13);
        row.addView(label);

        TextView valueText = new TextView(this);
        valueText.setText(value);
        valueText.setTextColor(getColor(R.color.text_primary));
        valueText.setTextSize(16);
        valueText.setPadding(0, dp(2), 0, 0);
        row.addView(valueText);
        return row;
    }

    private View createTelegramDetailDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));
        return divider;
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
        MonitorStatusStore.setServiceRunning(this, false);
        MonitorStatusStore.setTelegramState(this, TelegramClientManager.State.CLOSED);
        clientManager.logOut();
        refreshProfile();
    }

    private void showTermsDialog() {
        showInformationDialog(R.string.profile_terms_title, R.string.profile_terms_message);
    }

    private void refreshErrorSummary() {
        if (errorTitle == null || errorSummary == null) {
            return;
        }
        int count = AppErrorStore.getAll(this).size();
        int textColor = getColor(count == 0 ? R.color.text_primary : R.color.danger);
        int summaryColor = getColor(count == 0 ? R.color.text_secondary : R.color.danger);
        errorTitle.setTextColor(textColor);
        errorSummary.setTextColor(summaryColor);
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
