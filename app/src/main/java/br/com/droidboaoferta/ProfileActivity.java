package br.com.droidboaoferta;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class ProfileActivity extends AppCompatActivity implements TelegramClientManager.Listener {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";

    private TelegramClientManager clientManager;
    private TextView avatarText;
    private TextView profileName;
    private TextView profileSummary;
    private TextView profileStatus;
    private TextView appVersion;
    private LinearLayout accountCard;
    private LinearLayout logoutRow;
    private View accountChevron;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        clientManager = TelegramClientManager.getInstance();
        avatarText = findViewById(R.id.text_profile_avatar);
        profileName = findViewById(R.id.text_profile_name);
        profileSummary = findViewById(R.id.text_profile_summary);
        profileStatus = findViewById(R.id.text_profile_status);
        appVersion = findViewById(R.id.text_app_version);
        accountCard = findViewById(R.id.card_telegram_account);
        logoutRow = findViewById(R.id.row_logout);
        accountChevron = findViewById(R.id.image_account_chevron);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        accountCard.setOnClickListener(view -> {
            if (clientManager.getState() != TelegramClientManager.State.READY) {
                startActivity(new Intent(this, TelegramSetupActivity.class));
            }
        });
        findViewById(R.id.row_settings).setOnClickListener(view -> startActivity(
                new Intent(this, SettingsActivity.class)
        ));
        findViewById(R.id.row_terms).setOnClickListener(view -> showTermsDialog());
        logoutRow.setOnClickListener(view -> showLogoutConfirmation());
        appVersion.setText(getString(R.string.profile_app_version, BuildConfig.VERSION_NAME));
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientManager.setListener(this);
        clientManager.start(this);
        refreshProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfile();
    }

    @Override
    protected void onStop() {
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
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(
                this,
                getString(R.string.telegram_error_format, message),
                Toast.LENGTH_LONG
        ).show());
    }

    @Override
    public void onAccountChanged() {
        runOnUiThread(this::refreshProfile);
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
        logoutRow.setVisibility(connected ? View.VISIBLE : View.GONE);
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
        Toast.makeText(this, R.string.profile_telegram_disconnected_toast, Toast.LENGTH_SHORT).show();
    }

    private void showTermsDialog() {
        showConfirmationDialog(
                R.string.profile_terms_title,
                R.string.profile_terms_message,
                R.string.action_confirm,
                false,
                () -> {
                }
        );
    }

    private void showConfirmationDialog(int titleResource, int messageResource, int confirmResource,
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
        message.setText(messageResource);
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
