package br.com.droidboaoferta;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final int REQUEST_NOTIFICATIONS = 1201;

    private final BroadcastReceiver offerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshDashboard();
        }
    };

    private TextView statusTitle;
    private TextView statusSummary;
    private ImageButton monitorToggle;
    private TextView groupsSummary;
    private TextView alertsSummary;
    private LinearLayout offersContainer;
    private InterestRepository interestRepository;
    private OfferRepository offerRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        interestRepository = new InterestRepository(this);
        offerRepository = new OfferRepository(this);
        statusTitle = findViewById(R.id.text_monitor_status_title);
        statusSummary = findViewById(R.id.text_monitor_status_summary);
        monitorToggle = findViewById(R.id.button_monitor_toggle);
        groupsSummary = findViewById(R.id.text_groups_summary);
        alertsSummary = findViewById(R.id.text_alerts_summary);
        offersContainer = findViewById(R.id.container_offers);

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
        IntentFilter filter = new IntentFilter(OfferMonitor.ACTION_OFFER_FOUND);
        ContextCompat.registerReceiver(
                this,
                offerReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(offerReceiver);
        super.onStop();
    }

    private void refreshDashboard() {
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
        renderOffers(offerRepository.getRecent());

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
            statusSummary.setText(getString(
                    R.string.dashboard_status_active_summary,
                    groupCountText,
                    interestCountText
            ));
            monitorToggle.setImageResource(R.drawable.ic_pause);
            monitorToggle.setContentDescription(getString(R.string.action_pause));
            monitorToggle.setVisibility(View.VISIBLE);
            ContextCompat.startForegroundService(this, new Intent(this, OfferMonitorService.class));
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

    private void renderOffers(List<ObservedOffer> offers) {
        offersContainer.removeAllViews();
        if (offers.isEmpty()) {
            offersContainer.addView(createEmptyText(R.string.dashboard_no_offers));
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
        int limit = Math.min(offers.size(), 5);
        for (int index = 0; index < limit; index++) {
            ObservedOffer offer = offers.get(index);
            String summary = getString(
                    R.string.dashboard_offer_summary,
                    currency.format(offer.getPrice()),
                    offer.getSource(),
                    timeFormat.format(new Date(offer.getObservedAt()))
            );
            LinearLayout row = createDataRow(
                    offer.getInterest(),
                    summary,
                    "%",
                    R.color.action
            );
            if (!offer.getLink().isEmpty()) {
                row.setBackgroundResource(R.drawable.bg_card_pressed);
                row.setOnClickListener(view -> startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink()))
                ));
            }
            offersContainer.addView(row);
        }
    }

    private LinearLayout createDataRow(String title, String summary, String iconText, int iconColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(6), dp(7));

        TextView icon = new TextView(this);
        icon.setText(iconText);
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(14);
        icon.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBackground = new android.graphics.drawable.GradientDrawable();
        iconBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBackground.setColor(getColor(iconColor));
        icon.setBackground(iconBackground);
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(6), 0);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(16);
        texts.addView(titleView);
        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(getColor(R.color.text_secondary));
        summaryView.setTextSize(13);
        summaryView.setPadding(0, dp(1), 0, 0);
        texts.addView(summaryView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private LinearLayout createInterestRow(String text, String contentDescription) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(3), dp(4), dp(3));

        TextView label = new TextView(this);
        label.setText(text);
        label.setContentDescription(contentDescription);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, 0, dp(6), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView createEmptyText(int textResource) {
        TextView text = new TextView(this);
        text.setText(textResource);
        text.setTextColor(getColor(R.color.text_secondary));
        text.setTextSize(13);
        text.setPadding(dp(10), dp(8), dp(10), dp(10));
        return text;
    }

    private TextView createInlineAction(int textResource) {
        TextView action = new TextView(this);
        action.setText(textResource);
        action.setTextColor(getColor(R.color.action));
        action.setTextSize(14);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(12), dp(9), dp(12), dp(9));
        action.setBackgroundResource(R.drawable.bg_button_inline);
        return action;
    }

    private ImageButton createRemoveInterestButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_delete);
        button.setColorFilter(getColor(R.color.danger));
        button.setBackgroundResource(R.drawable.bg_icon_danger);
        button.setContentDescription(getString(R.string.action_remove_interest));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(7), dp(7), dp(7), dp(7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        params.leftMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private void showInterestDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.interest_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(R.string.interest_dialog_summary);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(6), 0, dp(16));
        content.addView(message);

        EditText termInput = createDialogInput(
                R.string.interest_term_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        content.addView(termInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        EditText priceInput = createDialogInput(
                R.string.interest_price_hint,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        priceParams.topMargin = dp(12);
        content.addView(priceInput, priceParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(18), 0, 0);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView save = createDialogAction(R.string.action_save);
        save.setOnClickListener(view -> {
            String term = termInput.getText().toString().trim();
            String priceText = priceInput.getText().toString().trim().replace(',', '.');
            if (term.isEmpty()) {
                termInput.setError(getString(R.string.interest_term_required));
                return;
            }
            double maximumPrice;
            try {
                maximumPrice = Double.parseDouble(priceText);
            } catch (NumberFormatException exception) {
                priceInput.setError(getString(R.string.interest_price_required));
                return;
            }
            if (maximumPrice <= 0) {
                priceInput.setError(getString(R.string.interest_price_required));
                return;
            }
            interestRepository.add(term, maximumPrice);
            getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MONITOR_ENABLED, true)
                    .apply();
            dialog.dismiss();
            refreshDashboard();
        });
        actions.addView(save);
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

    private EditText createDialogInput(int hintResource, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hintResource);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setTextSize(16);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackgroundResource(R.drawable.bg_input);
        return input;
    }

    private TextView createDialogAction(int textResource) {
        TextView action = new TextView(this);
        action.setText(textResource);
        action.setTextColor(getColor(R.color.action));
        action.setTextSize(15);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(18), dp(10), 0, dp(10));
        return action;
    }

    private void toggleMonitor() {
        boolean enabled = !isMonitorEnabled();
        getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MONITOR_ENABLED, enabled)
                .apply();
        refreshDashboard();
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
