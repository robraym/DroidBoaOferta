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
    private ImageButton trashAllOffersButton;
    private EditText offersSearchInput;
    private InterestRepository interestRepository;
    private OfferRepository offerRepository;
    private List<ObservedOffer> displayedOffers = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationController.setup(this, BottomNavigationController.ITEM_HOME);

        interestRepository = new InterestRepository(this);
        offerRepository = new OfferRepository(this);
        offersContainer = findViewById(R.id.container_offers);
        offersSearchInput = findViewById(R.id.input_search_offers);

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelegramClientManager.getInstance().start(this);
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
        unregisterReceiver(offerReceiver);
        super.onStop();
    }

    private void refreshDashboard() {
        int groupCount = getSelectedGroupCount();
        List<Interest> interests = interestRepository.getAll();
        boolean monitorEnabled = isMonitorEnabled();

        renderOffers(offerRepository.getRecent());

        if (groupCount == 0 || interests.isEmpty() || !monitorEnabled) {
            stopService(new Intent(this, OfferMonitorService.class));
        } else {
            requestNotificationPermissionIfNeeded();
            ContextCompat.startForegroundService(this, new Intent(this, OfferMonitorService.class));
        }
    }

    private void trashAllOffers() {
        if (offerRepository.getRecent().isEmpty()) {
            Toast.makeText(this, R.string.dashboard_no_offers, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.offers_trashed, Toast.LENGTH_SHORT).show();
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
        trashAllOffersButton.setVisibility(offers.isEmpty() ? View.GONE : View.VISIBLE);
        if (visibleOffers.isEmpty()) {
            offersContainer.addView(createEmptyText(R.string.dashboard_no_offers));
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
        int limit = visibleOffers.size();
        for (int index = 0; index < limit; index++) {
            ObservedOffer offer = visibleOffers.get(index);
            String contentDescription = getString(
                    R.string.dashboard_offer_summary,
                    currency.format(offer.getPrice()),
                    offer.getSource(),
                    timeFormat.format(new Date(offer.getObservedAt()))
            );
            LinearLayout row = createOfferRow(
                    offer.getInterest(),
                    currency.format(offer.getPrice()),
                    timeFormat.format(new Date(offer.getObservedAt())),
                    offer.getSource(),
                    contentDescription
            );
            FrameLayout swipeContainer = createSwipeContainer(row);
            attachSwipeActions(row, offer);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            offersContainer.addView(swipeContainer, rowParams);
            if (index < limit - 1) {
                offersContainer.addView(createOfferDivider());
            }
        }
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
                                        String contentDescription) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(getColor(R.color.card));
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(6), dp(7), dp(6), dp(7));
        row.setContentDescription(contentDescription);

        LinearLayout mainLine = new LinearLayout(this);
        mainLine.setOrientation(LinearLayout.HORIZONTAL);
        mainLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        mainLine.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView priceView = new TextView(this);
        priceView.setText(price);
        priceView.setTextColor(getColor(R.color.text_primary));
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
        timeView.setTextColor(getColor(R.color.action));
        timeView.setTextSize(11.5f);
        timeView.setSingleLine(true);
        metaLine.addView(timeView);

        TextView sourceView = new TextView(this);
        sourceView.setText(source);
        sourceView.setTextColor(getColor(R.color.text_secondary));
        sourceView.setTextSize(11.5f);
        sourceView.setSingleLine(true);
        sourceView.setEllipsize(TextUtils.TruncateAt.END);
        sourceView.setPadding(dp(4), 0, 0, 0);
        metaLine.addView(sourceView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(metaLine);
        return row;
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

    private void attachSwipeActions(View row, ObservedOffer offer) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final boolean[] swiping = new boolean[1];
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
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
                            Toast.makeText(this, R.string.offer_archived, Toast.LENGTH_SHORT).show();
                        } else {
                            offerRepository.trash(offer.getId());
                            Toast.makeText(this, R.string.offer_trashed, Toast.LENGTH_SHORT).show();
                        }
                        refreshDashboard();
                        return true;
                    }
                    if (!swiping[0]
                            && Math.abs(deltaX) < dp(10)
                            && Math.abs(deltaY) < dp(10)
                            && !offer.getLink().isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink())));
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
            CloudSyncStore.rememberMonitorChanged(this, System.currentTimeMillis());
            CloudSyncStore.markLocalChanged(this);
            TelegramClientManager.getInstance().refreshSelectedGroupsHistory();
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

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }
}
