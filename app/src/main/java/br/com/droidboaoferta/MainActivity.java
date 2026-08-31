package br.com.droidboaoferta;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AlertouActivity {
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final String STARTUP_PREFS = "startup_preferences";
    private static final String BATTERY_NOTICE_SHOWN = "battery_notice_shown";
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
    private ImageButton trashAllOffersButton;
    private EditText offersSearchInput;
    private FloatingSearchController floatingSearchController;
    private InterestRepository interestRepository;
    private OfferRepository offerRepository;
    private List<ObservedOffer> displayedOffers = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationController.setup(
                this,
                BottomNavigationController.ITEM_HOME,
                R.id.navigation_animated_content
        );

        interestRepository = new InterestRepository(this);
        offerRepository = new OfferRepository(this);
        offersContainer = findViewById(R.id.container_offers);
        floatingSearchController = FloatingSearchController.attach(
                this,
                "home",
                R.id.floating_search_dismiss_surface
        );
        offersSearchInput = floatingSearchController.getInput();

        findViewById(R.id.button_profile).setOnClickListener(view -> startActivity(
                new Intent(this, ProfileActivity.class)
        ));
        trashAllOffersButton = findViewById(R.id.button_trash_all_offers);
        trashAllOffersButton.setOnClickListener(view -> trashAllOffers());
        offersSearchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                renderOffers(displayedOffers);
            }
        });
        findViewById(android.R.id.content).post(this::showBatteryNoticeIfNeeded);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelegramClientManager clientManager = TelegramClientManager.getInstance();
        clientManager.start(this);
        clientManager.refreshCloudBackupSoon();
        IntentFilter filter = new IntentFilter(OfferMonitor.ACTION_OFFER_FOUND);
        filter.addAction(MonitorStatusStore.ACTION_STATUS_CHANGED);
        filter.addAction(TelegramClientManager.ACTION_CLOUD_SYNC_CHANGED);
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
        BottomNavigationController.resetInitialFocus(this);
        refreshDashboard();
    }

    @Override
    protected void onStop() {
        floatingSearchController.collapse(false);
        unregisterReceiver(offerReceiver);
        super.onStop();
    }

    private void refreshDashboard() {
        int groupCount = getSelectedGroupCount();
        List<Interest> interests = interestRepository.getAll();
        boolean monitorEnabled = isMonitorEnabled();

        offerRepository.reconcileRecentWithInterests(interests);
        renderOffers(offerRepository.getRecent());

        boolean hasCouponAlert = false;
        boolean hasPriceAlert = false;
        boolean hasPropertyAlert = false;
        for (Interest interest : interests) {
            if (interest.isCoupon()) {
                hasCouponAlert = true;
            } else if (interest.isProperty()) {
                hasPropertyAlert = true;
            } else if (interest.isPrice()) {
                hasPriceAlert = true;
            }
        }
        if (monitorEnabled
                && (hasCouponAlert || hasPropertyAlert || (hasPriceAlert && groupCount > 0))) {
            requestNotificationPermissionIfNeeded();
        }
        MonitorServiceController.update(this);
    }

    private void showBatteryNoticeIfNeeded() {
        SharedPreferences preferences = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE);
        if (preferences.getBoolean(BATTERY_NOTICE_SHOWN, false)) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            preferences.edit().putBoolean(BATTERY_NOTICE_SHOWN, true).apply();
            return;
        }
        preferences.edit().putBoolean(BATTERY_NOTICE_SHOWN, true).apply();

        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.battery_notice_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(R.string.battery_notice_message);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(8), 0, dp(16));
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView later = createDialogAction(R.string.action_not_now);
        later.setOnClickListener(view -> dialog.dismiss());
        actions.addView(later);
        TextView allow = createDialogAction(R.string.battery_notice_allow);
        allow.setOnClickListener(view -> {
            dialog.dismiss();
            openBatteryOptimizationRequest();
        });
        actions.addView(allow);
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

    private void openBatteryOptimizationRequest() {
        Intent intent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())
        );
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        }
    }

    private void trashAllOffers() {
        if (offerRepository.getRecent().isEmpty()) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.trash_all_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(R.string.trash_all_dialog_message);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(8), 0, dp(16));
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView confirm = createDialogAction(R.string.action_confirm);
        confirm.setTextColor(getColor(R.color.danger));
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            performTrashAllOffers();
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

    private void performTrashAllOffers() {
        if (offerRepository.trashAllRecent()) {
            refreshDashboard();
        }
    }

    private void trashOfferSection(List<ObservedOffer> offers) {
        if (offers == null || offers.isEmpty()) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.trash_offer_section_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(R.string.trash_offer_section_dialog_message);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(8), 0, dp(16));
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView confirm = createDialogAction(R.string.action_confirm);
        confirm.setTextColor(getColor(R.color.danger));
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            performTrashOfferSection(offers);
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

    private void performTrashOfferSection(List<ObservedOffer> offers) {
        List<String> ids = new java.util.ArrayList<>();
        for (ObservedOffer offer : offers) {
            ids.add(offer.getId());
        }
        if (offerRepository.trashRecent(ids)) {
            refreshDashboard();
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
        String runtime = getTelegramConnectionText(snapshot);
        return configuration + "\n" + runtime;
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

    private String getLastAnalysisText(MonitorStatusStore.Snapshot snapshot) {
        if (!snapshot.serviceRunning) {
            return getString(R.string.dashboard_monitor_starting);
        }
        if (snapshot.lastAnalyzedMessageAt > 0) {
            return getString(R.string.dashboard_last_analysis_format, formatRelativeTime(snapshot.lastAnalyzedMessageAt));
        }
        if (snapshot.lastSelectedMessageAt > 0) {
            return getString(R.string.dashboard_last_message_format, formatRelativeTime(snapshot.lastSelectedMessageAt));
        }
        return getString(R.string.dashboard_waiting_messages);
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

    private void renderOffers(List<ObservedOffer> offers) {
        offersContainer.removeAllViews();
        displayedOffers = offers;
        List<ObservedOffer> visibleOffers = filterOffers(offers, offersSearchInput.getText().toString());
        trashAllOffersButton.setVisibility(View.GONE);
        if (visibleOffers.isEmpty()) {
            offersContainer.addView(createEmptyText(R.string.dashboard_no_offers));
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        PropertyHistoryRepository propertyHistoryRepository = new PropertyHistoryRepository(this);
        List<ObservedOffer> couponOffers = new java.util.ArrayList<>();
        List<ObservedOffer> propertyOffers = new java.util.ArrayList<>();
        List<ObservedOffer> productOffers = new java.util.ArrayList<>();
        for (ObservedOffer offer : visibleOffers) {
            if (isPropertyOffer(offer)) {
                propertyOffers.add(offer);
            } else if (isCouponOffer(offer)) {
                couponOffers.add(offer);
            } else {
                productOffers.add(offer);
            }
        }
        addOfferSection(R.string.coupon_alerts_list_title, couponOffers, currency,
                propertyHistoryRepository);
        addOfferSection(R.string.property_alerts_list_title, propertyOffers, currency,
                propertyHistoryRepository);
        addOfferSection(R.string.product_alerts_list_title, productOffers, currency,
                propertyHistoryRepository);
    }

    private void addOfferSection(int titleResource, List<ObservedOffer> offers,
                                 NumberFormat currency,
                                 PropertyHistoryRepository propertyHistoryRepository) {
        if (offers.isEmpty()) {
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_compact);
        card.setPadding(dp(6), dp(4), dp(6), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(2), 0, dp(3));

        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(18);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        ImageButton trash = new ImageButton(this);
        trash.setImageResource(R.drawable.ic_trash_outline);
        trash.setBackgroundResource(R.drawable.bg_icon_danger);
        trash.setContentDescription(getString(R.string.action_trash_offer_section));
        trash.setPadding(dp(8), dp(8), dp(8), dp(8));
        trash.setScaleType(ImageView.ScaleType.CENTER);
        trash.setOnClickListener(view -> trashOfferSection(offers));
        header.addView(trash, new LinearLayout.LayoutParams(dp(36), dp(36)));
        card.addView(header);

        String previousGroup = null;
        for (int index = 0; index < offers.size(); index++) {
            ObservedOffer offer = offers.get(index);
            String group = OfferDateFormatter.getGroupKey(offer.getObservedAt());
            String groupLabel = OfferDateFormatter.formatGroupLabel(this, offer.getObservedAt());
            if (!group.equals(previousGroup)) {
                if (previousGroup != null) {
                    card.addView(createDateGroupDivider());
                }
                card.addView(createOfferGroupHeader(groupLabel, previousGroup != null));
                previousGroup = group;
            } else {
                card.addView(createOfferDivider());
            }
            String displayedTime = OfferDateFormatter.formatTime(offer.getObservedAt());
            String contentDescription = getString(
                    R.string.dashboard_offer_summary,
                    currency.format(offer.getPrice()),
                    offer.getSource(),
                    groupLabel + " " + displayedTime
            );
            GroupSpeedRepository speed = new GroupSpeedRepository(this);
            boolean expired = speed.isOfferExpired(offer);
            PropertyHistoryEntry propertyHistory = propertyHistoryRepository.getForOffer(offer);
            LinearLayout row = createOfferRow(
                    offer.getInterest(),
                    currency.format(offer.getPrice()),
                    displayedTime,
                    offer.getSource(),
                    contentDescription,
                    expired,
                    propertyHistory != null && propertyHistory.isRecent(System.currentTimeMillis()),
                    propertyHistory == null ? 0L : propertyHistory.getFirstPublicationAt()
            );
            FrameLayout swipeContainer = createSwipeContainer(row);
            attachSwipeActions(row, offer, expired);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            card.addView(swipeContainer, rowParams);
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        offersContainer.addView(card, cardParams);
    }

    private TextView createOfferGroupHeader(String label, boolean hasPreviousGroup) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextColor(getColor(R.color.text_secondary));
        header.setTextSize(13);
        header.setPadding(dp(6), dp(hasPreviousGroup ? 10 : 8), dp(8), dp(5));
        return header;
    }

    private List<ObservedOffer> filterOffers(List<ObservedOffer> offers, String query) {
        String normalizedQuery = OfferTextParser.normalize(query);
        if (normalizedQuery.isEmpty()) {
            return offers;
        }
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        List<ObservedOffer> filtered = new java.util.ArrayList<>();
        for (ObservedOffer offer : offers) {
            String text = offer.getInterest() + " " + offer.getSource() + " "
                    + currency.format(offer.getPrice()) + " " + offer.getPrice();
            if (OfferTextParser.normalize(text).contains(normalizedQuery)) {
                filtered.add(offer);
            }
        }
        return filtered;
    }

    private FrameLayout createSwipeContainer(View foreground) {
        FrameLayout container = new FrameLayout(this);
        container.setClipChildren(false);

        LinearLayout background = new LinearLayout(this);
        background.setGravity(Gravity.CENTER_VERTICAL);
        background.setOrientation(LinearLayout.HORIZONTAL);
        background.setPadding(dp(12), 0, dp(12), 0);

        ImageView trashIcon = createSwipeActionIcon(R.drawable.ic_trash_outline, R.drawable.bg_icon_danger);
        background.addView(trashIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        View spacer = new View(this);
        background.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        ImageView archiveIcon = createSwipeActionIcon(R.drawable.ic_archive, R.drawable.bg_button_inline);
        background.addView(archiveIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        container.addView(background, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        container.addView(foreground, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        return container;
    }

    private ImageView createSwipeActionIcon(int iconResource, int backgroundResource) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setBackgroundResource(backgroundResource);
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        return icon;
    }

    private LinearLayout createOfferRow(String title, String price, String time, String source,
                                        String contentDescription, boolean expired,
                                        boolean newPropertyAd, long propertyPublishedAt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(getColor(R.color.card));
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(6), dp(7), dp(6), dp(7));
        row.setContentDescription(contentDescription);

        LinearLayout mainLine = new LinearLayout(this);
        mainLine.setOrientation(LinearLayout.HORIZONTAL);
        mainLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(expired ? R.color.text_secondary : R.color.text_primary));
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        mainLine.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView priceView = new TextView(this);
        priceView.setText(price);
        priceView.setTextColor(getColor(expired ? R.color.text_secondary : R.color.text_primary));
        priceView.setTextSize(14);
        priceView.setSingleLine(true);
        priceView.setPadding(dp(6), 0, 0, 0);
        mainLine.addView(priceView);
        row.addView(mainLine);

        LinearLayout metaLine = new LinearLayout(this);
        metaLine.setOrientation(LinearLayout.HORIZONTAL);
        metaLine.setGravity(Gravity.CENTER_VERTICAL);
        metaLine.setPadding(0, dp(2), 0, 0);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextColor(getColor(expired ? R.color.text_secondary : R.color.action));
        timeView.setTextSize(11.5f);
        timeView.setSingleLine(true);
        metaLine.addView(timeView);

        if (newPropertyAd) {
            TextView badge = createPropertyNewBadge();
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.leftMargin = dp(5);
            metaLine.addView(badge, badgeParams);
        }

        if (propertyPublishedAt > 0L) {
            TextView badge = createPropertyPublishedBadge(propertyPublishedAt);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.leftMargin = dp(5);
            metaLine.addView(badge, badgeParams);
        }

        TextView sourceView = new TextView(this);
        sourceView.setText(source);
        sourceView.setTextColor(getColor(R.color.text_secondary));
        sourceView.setTextSize(11.5f);
        sourceView.setSingleLine(true);
        sourceView.setEllipsize(TextUtils.TruncateAt.END);
        sourceView.setPadding(dp(4), 0, 0, 0);
        metaLine.addView(sourceView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(metaLine);
        if (expired) {
            TextView status = new TextView(this);
            status.setText(R.string.telegram_group_promotion_expired);
            status.setTextColor(getColor(R.color.text_secondary));
            status.setTextSize(11.5f);
            status.setPadding(0, dp(2), 0, 0);
            row.addView(status);
        }
        return row;
    }

    private TextView createPropertyNewBadge() {
        TextView badge = new TextView(this);
        badge.setText(R.string.property_new_ad_badge);
        badge.setTextColor(getColor(R.color.action_green));
        badge.setTextSize(10.5f);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setSingleLine(true);
        badge.setBackgroundResource(R.drawable.bg_property_new_badge);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        return badge;
    }

    private TextView createPropertyPublishedBadge(long publishedAt) {
        TextView badge = new TextView(this);
        badge.setText(getString(
                R.string.property_published_badge,
                formatPropertyPublishedBadgeDate(publishedAt)
        ));
        badge.setTextColor(getColor(R.color.text_secondary));
        badge.setTextSize(10.5f);
        badge.setSingleLine(true);
        badge.setBackgroundResource(R.drawable.bg_property_published_badge);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        return badge;
    }

    private View createOfferDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        divider.setLayoutParams(params);
        return divider;
    }

    private View createDateGroupDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.section_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
        );
        params.setMargins(dp(6), dp(12), dp(6), dp(4));
        divider.setLayoutParams(params);
        return divider;
    }

    private void attachSwipeActions(View row, ObservedOffer offer, boolean expired) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final long[] downAt = new long[1];
        final boolean[] swiping = new boolean[1];
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    downAt[0] = event.getEventTime();
                    swiping[0] = false;
                    view.animate().cancel();
                    view.setTranslationX(0);
                    requestParentIntercept(view, false);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getRawX() - downX[0];
                    float moveY = event.getRawY() - downY[0];
                    if (Math.abs(moveX) > dp(12) && Math.abs(moveX) > Math.abs(moveY)) {
                        swiping[0] = true;
                        requestParentIntercept(view, true);
                        float limitedMove = Math.max(-dp(96), Math.min(dp(96), moveX));
                        view.setTranslationX(limitedMove);
                    } else if (Math.abs(moveY) > dp(12) && Math.abs(moveY) > Math.abs(moveX)) {
                        requestParentIntercept(view, false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getRawX() - downX[0];
                    float deltaY = event.getRawY() - downY[0];
                    requestParentIntercept(view, false);
                    view.animate().translationX(0).setDuration(120).start();
                    if (Math.abs(deltaX) > dp(56) && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f) {
                        if (deltaX < 0) {
                            offerRepository.archive(offer.getId());
                        } else {
                            offerRepository.trash(offer.getId());
                        }
                        refreshDashboard();
                        return true;
                    }
                    if (!swiping[0] && Math.abs(deltaX) < dp(10) && Math.abs(deltaY) < dp(10)
                            && expired) {
                        showPromotionValidityDialog(offer, true);
                    } else if (!swiping[0] && Math.abs(deltaX) < dp(10) && Math.abs(deltaY) < dp(10)
                            && event.getEventTime() - downAt[0] >= 500L
                            && isPropertyOffer(offer)) {
                        showPropertyHistoryDialog(offer);
                    } else if (!swiping[0] && Math.abs(deltaX) < dp(10) && Math.abs(deltaY) < dp(10)
                            && event.getEventTime() - downAt[0] >= 500L) {
                        showPromotionValidityDialog(offer, false);
                    } else if (!swiping[0]
                            && Math.abs(deltaX) < dp(10)
                            && Math.abs(deltaY) < dp(10)
                            && openCouponOffer(offer)) {
                        // O cupom foi copiado e a página oficial foi aberta.
                    } else if (!swiping[0]
                            && Math.abs(deltaX) < dp(10)
                            && Math.abs(deltaY) < dp(10)
                            && !offer.getTelegramPostLink().isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(offer.getTelegramPostLink())));
                    } else if (!swiping[0]
                            && Math.abs(deltaX) < dp(10)
                            && Math.abs(deltaY) < dp(10)
                            && offer.getLink() != null
                            && !offer.getLink().trim().isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(getOfferOpenLink(offer))));
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    requestParentIntercept(view, false);
                    view.animate().translationX(0).setDuration(120).start();
                    return true;
                default:
                    return true;
            }
        });
    }

    private boolean isPropertyOffer(ObservedOffer offer) {
        return offer != null && offer.getId().startsWith("property|");
    }

    private boolean isCouponOffer(ObservedOffer offer) {
        return offer != null && (offer.getId().startsWith("coupon|")
                || !extractCouponCode(offer.getSource()).isEmpty());
    }

    private String getOfferOpenLink(ObservedOffer offer) {
        if (isPropertyOffer(offer)) {
            String normalizedUrl = PropertyPageClient.normalizeListingUrl(offer.getLink());
            if (normalizedUrl != null) {
                return normalizedUrl;
            }
        }
        return offer.getLink().trim();
    }

    private boolean openCouponOffer(ObservedOffer offer) {
        String couponCode = extractCouponCode(offer.getSource());
        if (couponCode.isEmpty()) {
            return false;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.coupon_clipboard_label), couponCode));
        }
        Toast.makeText(
                this,
                getString(R.string.coupon_copied, couponCode),
                Toast.LENGTH_SHORT
        ).show();
        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(offer.getLink())
            ));
        } catch (RuntimeException exception) {
            AppErrorStore.recordSerious(
                    this,
                    "Cupom",
                    getString(R.string.coupon_store_open_failed)
            );
        }
        return true;
    }

    private String extractCouponCode(String source) {
        if (source == null) {
            return "";
        }
        String marker = "• cupom ";
        int markerIndex = source.toLowerCase(Locale.ROOT).indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        return source.substring(markerIndex + marker.length()).trim();
    }

    private void showPropertyHistoryDialog(ObservedOffer offer) {
        PropertyHistoryEntry entry = new PropertyHistoryRepository(this).getForOffer(offer);
        if (entry == null || entry.getPoints().isEmpty()) {
            Toast.makeText(this, R.string.property_history_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);
        scroll.addView(content);

        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(offer.getInterest());
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleLine.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        if (entry.isRecent(System.currentTimeMillis())) {
            TextView badge = createPropertyNewBadge();
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.leftMargin = dp(8);
            titleLine.addView(badge, badgeParams);
        }
        content.addView(titleLine);

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        currency.setMaximumFractionDigits(0);
        TextView summary = new TextView(this);
        summary.setText(getString(
                R.string.property_history_current_summary,
                currency.format(offer.getPrice()),
                offer.getSource()
        ));
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setTextSize(14);
        summary.setPadding(0, dp(6), 0, dp(8));
        content.addView(summary);

        if (!entry.getTitle().trim().isEmpty()
                && !OfferTextParser.normalize(entry.getTitle())
                .equals(OfferTextParser.normalize(offer.getInterest()))) {
            TextView description = new TextView(this);
            description.setText(getString(R.string.property_history_listing_title, entry.getTitle()));
            description.setTextColor(getColor(R.color.text_secondary));
            description.setTextSize(13);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            description.setPadding(0, 0, 0, dp(10));
            content.addView(description);
        }

        content.addView(createPropertyHistoryFact(
                R.string.property_history_first_seen,
                formatPropertyHistoryDate(entry.getFirstSeenAt())
        ));
        content.addView(createPropertyHistoryFact(
                R.string.property_history_last_seen,
                formatPropertyHistoryDate(entry.getLastSeenAt())
        ));
        if (entry.getFirstPublicationAt() > 0L) {
            content.addView(createPropertyHistoryFact(
                    R.string.property_history_published,
                    formatPropertyHistoryDate(entry.getFirstPublicationAt())
            ));
        }

        TextView chartTitle = new TextView(this);
        chartTitle.setText(R.string.property_history_chart_title);
        chartTitle.setTextColor(getColor(R.color.text_primary));
        chartTitle.setTextSize(16);
        chartTitle.setPadding(0, dp(14), 0, dp(4));
        content.addView(chartTitle);

        PropertyPriceTrendView trendView = new PropertyPriceTrendView(this);
        trendView.setPoints(entry.getPoints());
        content.addView(trendView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210)
        ));

        TextView readingsTitle = new TextView(this);
        readingsTitle.setText(R.string.property_history_readings_title);
        readingsTitle.setTextColor(getColor(R.color.text_primary));
        readingsTitle.setTextSize(16);
        readingsTitle.setPadding(0, dp(8), 0, dp(4));
        content.addView(readingsTitle);

        int firstIndex = Math.max(0, entry.getPoints().size() - 8);
        for (int index = entry.getPoints().size() - 1; index >= firstIndex; index--) {
            content.addView(createPropertyHistoryPointRow(entry.getPoints().get(index), currency));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        TextView close = createDialogAction(R.string.action_close);
        close.setOnClickListener(view -> dialog.dismiss());
        actions.addView(close);
        content.addView(actions);

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(shownWindow.getAttributes());
            params.width = getResources().getDisplayMetrics().widthPixels - dp(44);
            params.height = getResources().getDisplayMetrics().heightPixels - dp(72);
            params.dimAmount = 0.65f;
            shownWindow.setAttributes(params);
            shownWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private LinearLayout createPropertyHistoryFact(int labelResource, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView label = new TextView(this);
        label.setText(labelResource);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(13);
        row.addView(label);

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.text_primary));
        text.setTextSize(13);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.END);
        text.setPadding(dp(8), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        return row;
    }

    private LinearLayout createPropertyHistoryPointRow(PropertyHistoryPoint point,
                                                       NumberFormat currency) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView date = new TextView(this);
        date.setText(formatPropertyHistoryDate(point.getObservedAt()));
        date.setTextColor(getColor(R.color.text_secondary));
        date.setTextSize(12.5f);
        row.addView(date, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        TextView details = new TextView(this);
        details.setText(getString(
                R.string.property_history_point,
                currency.format(point.getPrice()),
                formatArea(point.getArea())
        ));
        details.setTextColor(getColor(R.color.text_primary));
        details.setTextSize(12.5f);
        details.setSingleLine(true);
        row.addView(details);
        return row;
    }

    private String formatPropertyHistoryDate(long timestamp) {
        if (timestamp <= 0L) {
            return getString(R.string.property_history_unknown_date);
        }
        return new SimpleDateFormat("dd/MM HH:mm", new Locale("pt", "BR"))
                .format(new java.util.Date(timestamp));
    }

    private String formatPropertyPublishedBadgeDate(long timestamp) {
        return new SimpleDateFormat("dd/MM", new Locale("pt", "BR"))
                .format(new java.util.Date(timestamp));
    }

    private String formatArea(double area) {
        NumberFormat areaFormat = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        areaFormat.setMaximumFractionDigits(1);
        return areaFormat.format(area);
    }

    private void showPromotionValidityDialog(ObservedOffer offer, boolean expired) {
        GroupSpeedRepository speed = new GroupSpeedRepository(this);
        GroupPromotionExpiryRepository expiry = new GroupPromotionExpiryRepository(this);
        String product = offer.getInterest();
        long roundStartedAt = speed.getRoundStartedAt(offer);
        if (expired) {
            new AlertDialog.Builder(this)
                    .setTitle(product)
                    .setMessage(R.string.telegram_group_promotion_expired)
                    .setPositiveButton(R.string.telegram_group_promotion_resume_action, (dialog, which) -> {
                        boolean resumed = expiry.resumeForOffer(OfferTextParser.normalize(product),
                                offer.getObservedAt(), System.currentTimeMillis());
                        CloudSyncStore.syncPromotionExpiryChanged(this);
                        refreshDashboard();
                        if (resumed) {
                            Toast.makeText(this, R.string.telegram_group_promotion_resumed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.promotion_review_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        content.addView(title);

        TextView productView = new TextView(this);
        productView.setText(product);
        productView.setTextColor(getColor(R.color.text_primary));
        productView.setTextSize(16);
        productView.setMaxLines(2);
        productView.setEllipsize(TextUtils.TruncateAt.END);
        productView.setPadding(0, dp(7), 0, 0);
        content.addView(productView);

        TextView summary = new TextView(this);
        summary.setText(R.string.promotion_review_summary);
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setTextSize(14);
        summary.setPadding(0, dp(5), 0, dp(16));
        content.addView(summary);

        LinearLayout endedAction = createPromotionReviewAction(
                R.string.telegram_group_promotion_expire_action,
                R.string.telegram_group_promotion_expire_summary,
                R.color.action,
                R.drawable.bg_button_secondary
        );
        endedAction.setOnClickListener(view -> {
            expiry.markExpired(OfferTextParser.normalize(product), roundStartedAt,
                    offer.getObservedAt());
            CloudSyncStore.syncPromotionExpiryChanged(this);
            dialog.dismiss();
            refreshDashboard();
        });
        content.addView(endedAction);

        LinearLayout invalidAction = createPromotionReviewAction(
                R.string.promotion_invalid_action,
                R.string.promotion_invalid_summary,
                R.color.danger,
                R.drawable.bg_button_danger
        );
        LinearLayout.LayoutParams invalidParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        invalidParams.topMargin = dp(10);
        content.addView(invalidAction, invalidParams);
        invalidAction.setOnClickListener(view -> {
            speed.invalidateOffer(offer);
            offerRepository.trash(offer.getId());
            dialog.dismiss();
            refreshDashboard();
            Toast.makeText(this, R.string.promotion_invalid_confirmed,
                    Toast.LENGTH_SHORT).show();
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(10), 0, 0);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        content.addView(actions);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
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

    private LinearLayout createPromotionReviewAction(int titleResource, int summaryResource,
                                                       int titleColor, int backgroundResource) {
        LinearLayout action = new LinearLayout(this);
        action.setOrientation(LinearLayout.VERTICAL);
        action.setPadding(dp(16), dp(12), dp(16), dp(12));
        action.setBackgroundResource(backgroundResource);
        action.setClickable(true);
        action.setFocusable(true);

        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextColor(getColor(titleColor));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        action.addView(title);

        TextView summary = new TextView(this);
        summary.setText(summaryResource);
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setTextSize(13);
        summary.setPadding(0, dp(3), 0, 0);
        action.addView(summary);
        return action;
    }

    private void requestParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
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

        LowestPriceSuggestionView priceSuggestion = new LowestPriceSuggestionView(this);
        priceSuggestion.bind(termInput, priceInput);
        LinearLayout.LayoutParams suggestionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        suggestionParams.topMargin = dp(10);
        content.addView(priceSuggestion, suggestionParams);

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
            long interestId = interestRepository.add(term, maximumPrice);
            getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MONITOR_ENABLED, true)
                    .apply();
            CloudSyncStore.rememberMonitorChanged(this, System.currentTimeMillis());
            CloudSyncStore.markLocalChanged(this);
            OfferMonitor.getInstance().refreshInterestHistory(
                    this,
                    interestId,
                    term,
                    maximumPrice
            );
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
        long changedAt = System.currentTimeMillis();
        getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MONITOR_ENABLED, enabled)
                .apply();
        CloudSyncStore.rememberMonitorChanged(this, changedAt);
        CloudSyncStore.markLocalChanged(this);
        refreshDashboard();
    }

    private boolean isMonitorEnabled() {
        return MonitorServiceController.isEnabled(this);
    }

    private int getSelectedGroupCount() {
        return MonitorServiceController.selectedGroupCount(this);
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }
}
