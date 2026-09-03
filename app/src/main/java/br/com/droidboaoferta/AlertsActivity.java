package br.com.droidboaoferta;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlertsActivity extends AlertouActivity {
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final String ALERTS_SORT_ORDER = "alerts_sort_order";
    private static final int REQUEST_NOTIFICATIONS = 1202;
    private static final int SORT_RECENT = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_PRICE_ASCENDING = 2;
    private static final int SORT_PRICE_DESCENDING = 3;

    private InterestRepository interestRepository;
    private OfferRepository offerRepository;
    private final ExecutorService alertUpdateExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout couponInterestsContainer;
    private LinearLayout propertyInterestsContainer;
    private LinearLayout priceInterestsContainer;
    private EditText interestsSearchInput;
    private TextView couponAlertsCountText;
    private TextView propertyAlertsCountText;
    private TextView priceAlertsCountText;
    private FloatingSearchController floatingSearchController;
    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderInterests();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alerts);
        BottomNavigationController.setup(
                this,
                BottomNavigationController.ITEM_ALERTS,
                R.id.navigation_animated_content
        );

        interestRepository = new InterestRepository(this);
        offerRepository = new OfferRepository(this);
        couponInterestsContainer = findViewById(R.id.container_coupon_interests);
        propertyInterestsContainer = findViewById(R.id.container_property_interests);
        priceInterestsContainer = findViewById(R.id.container_price_interests);
        floatingSearchController = FloatingSearchController.attach(
                this,
                "alerts",
                R.id.navigation_animated_content
        );
        interestsSearchInput = floatingSearchController.getInput();
        couponAlertsCountText = findViewById(R.id.text_coupon_alerts_count);
        propertyAlertsCountText = findViewById(R.id.text_property_alerts_count);
        priceAlertsCountText = findViewById(R.id.text_price_alerts_count);

        findViewById(R.id.button_profile).setOnClickListener(view -> startActivity(
                new Intent(this, ProfileActivity.class)
        ));
        findViewById(R.id.button_sort_alerts).setOnClickListener(view -> showSortDialog());
        findViewById(R.id.button_add_price_interest).setOnClickListener(
                view -> showInterestDialog(null, Interest.TYPE_PRICE));
        findViewById(R.id.button_add_coupon_interest).setOnClickListener(
                view -> showInterestDialog(null, Interest.TYPE_COUPON));
        findViewById(R.id.button_add_property_interest).setOnClickListener(
                view -> showPropertyDialog(null));
        interestsSearchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                renderInterests();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationController.resetInitialFocus(this);
        renderInterests();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelegramClientManager clientManager = TelegramClientManager.getInstance();
        clientManager.start(this);
        ContextCompat.registerReceiver(
                this,
                syncReceiver,
                new IntentFilter(TelegramClientManager.ACTION_CLOUD_SYNC_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        clientManager.refreshCloudConfigurationSoon();
    }

    @Override
    protected void onStop() {
        floatingSearchController.collapse(false);
        unregisterReceiver(syncReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        alertUpdateExecutor.shutdownNow();
        super.onDestroy();
    }

    private void renderInterests() {
        List<Interest> registeredInterests = interestRepository.getAll();
        int couponCount = 0;
        int propertyCount = 0;
        int priceCount = 0;
        for (Interest interest : registeredInterests) {
            if (interest.isCoupon()) {
                couponCount++;
            } else if (interest.isProperty()) {
                propertyCount++;
            } else if (interest.isPrice()) {
                priceCount++;
            }
        }
        couponAlertsCountText.setText(couponCount == 0
                ? getString(R.string.coupon_alerts_registered_count_empty)
                : getString(couponCount == 1
                        ? R.string.coupon_alerts_registered_count_one
                        : R.string.coupon_alerts_registered_count_many, couponCount));
        priceAlertsCountText.setText(priceCount == 0
                ? getString(R.string.price_alerts_registered_count_empty)
                : getString(priceCount == 1
                        ? R.string.price_alerts_registered_count_one
                        : R.string.price_alerts_registered_count_many, priceCount));
        propertyAlertsCountText.setText(propertyCount == 0
                ? getString(R.string.property_alerts_registered_count_empty)
                : getString(propertyCount == 1
                        ? R.string.property_alerts_registered_count_one
                        : R.string.property_alerts_registered_count_many, propertyCount));

        List<Interest> filteredInterests = filterInterests(
                registeredInterests,
                interestsSearchInput.getText().toString()
        );
        sortInterests(filteredInterests);
        List<Interest> coupons = new java.util.ArrayList<>();
        List<Interest> properties = new java.util.ArrayList<>();
        List<Interest> prices = new java.util.ArrayList<>();
        for (Interest interest : filteredInterests) {
            if (interest.isCoupon()) {
                coupons.add(interest);
            } else if (interest.isProperty()) {
                properties.add(interest);
            } else if (interest.isPrice()) {
                prices.add(interest);
            }
        }
        renderInterestSection(
                coupons, couponInterestsContainer, couponCount > 0);
        renderInterestSection(
                properties, propertyInterestsContainer, propertyCount > 0);
        renderInterestSection(
                prices, priceInterestsContainer, priceCount > 0);
    }

    private void renderInterestSection(List<Interest> interests, LinearLayout container,
                                       boolean hasRegisteredItems) {
        container.removeAllViews();
        if (interests.isEmpty()) {
            if (hasRegisteredItems) {
                container.addView(createEmptyText(R.string.alerts_search_no_results));
            }
            return;
        }

        NumberFormat amountFormat = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        amountFormat.setMinimumFractionDigits(2);
        amountFormat.setMaximumFractionDigits(2);
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            LinearLayout row = createInterestRow(interest, amountFormat);
            ImageButton edit = createEditInterestButton();
            edit.setOnClickListener(view -> {
                if (interest.isProperty()) {
                    showPropertyDialog(interest);
                } else {
                    showInterestDialog(interest, interest.getType());
                }
            });
            row.addView(edit, 0);
            container.addView(row);
            if (index < interests.size() - 1) {
                container.addView(createDivider());
            }
        }
    }

    private List<Interest> filterInterests(List<Interest> interests, String query) {
        String normalizedQuery = OfferTextParser.normalize(query);
        if (normalizedQuery.isEmpty()) {
            return interests;
        }
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        List<Interest> filtered = new java.util.ArrayList<>();
        for (Interest interest : interests) {
            String text = interest.getTerm() + " "
                    + (interest.isCoupon() ? getString(R.string.motorola_coupon_offer_title) : "")
                    + (interest.isProperty() ? " QuintoAndar Imóveis "
                    + interest.getMinimumArea() + " " + interest.getMaximumArea() : "")
                    + " " + currency.format(interest.getMaximumPrice())
                    + " " + interest.getMaximumPrice();
            if (OfferTextParser.normalize(text).contains(normalizedQuery)) {
                filtered.add(interest);
            }
        }
        return filtered;
    }

    private void sortInterests(List<Interest> interests) {
        int sortOrder = getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .getInt(ALERTS_SORT_ORDER, SORT_RECENT);
        Comparator<Interest> comparator;
        if (sortOrder == SORT_NAME) {
            comparator = (first, second) -> {
                int byName = OfferTextParser.normalize(getInterestSortName(first))
                        .compareTo(OfferTextParser.normalize(getInterestSortName(second)));
                return byName != 0 ? byName : Long.compare(second.getId(), first.getId());
            };
        } else if (sortOrder == SORT_PRICE_ASCENDING) {
            comparator = (first, second) -> {
                int byValue = Double.compare(first.getMaximumPrice(), second.getMaximumPrice());
                return byValue != 0 ? byValue : Long.compare(second.getId(), first.getId());
            };
        } else if (sortOrder == SORT_PRICE_DESCENDING) {
            comparator = (first, second) -> {
                int byValue = Double.compare(second.getMaximumPrice(), first.getMaximumPrice());
                return byValue != 0 ? byValue : Long.compare(second.getId(), first.getId());
            };
        } else {
            return;
        }
        interests.sort(comparator);
    }

    private String getInterestSortName(Interest interest) {
        return interest.isProperty() ? getPropertyDisplayName(interest) : interest.getTerm();
    }

    private String getPropertyDisplayName(Interest interest) {
        String name = PropertyPageResult.normalizeCondominiumName(interest.getPropertyName());
        return name.isEmpty() ? getString(R.string.property_interest_unknown_name) : name;
    }

    private void showSortDialog() {
        int selected = getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                .getInt(ALERTS_SORT_ORDER, SORT_RECENT);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.alerts_sort_title)
                .setSingleChoiceItems(R.array.alerts_sort_options, selected, (dialog, which) -> {
                    getSharedPreferences(OFFER_PREFS, MODE_PRIVATE).edit()
                            .putInt(ALERTS_SORT_ORDER, which)
                            .apply();
                    CloudSyncStore.syncAlertsSortChanged(
                            this,
                            which,
                            System.currentTimeMillis()
                    );
                    dialog.dismiss();
                    renderInterests();
                })
                .show();
    }

    private LinearLayout createInterestRow(Interest interest, NumberFormat amountFormat) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(getColor(R.color.card));
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(4), dp(4), dp(4), dp(4));

        if (interest.isCoupon()) {
            TextView label = createInterestText();
            label.setText(getString(
                    R.string.coupon_interest_single_line,
                    amountFormat.format(interest.getMaximumPrice())));
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        } else if (interest.isProperty()) {
            LinearLayout textContainer = new LinearLayout(this);
            textContainer.setOrientation(LinearLayout.VERTICAL);

            TextView title = createInterestText();
            title.setText(getPropertyDisplayName(interest));
            title.setTextSize(14);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textContainer.addView(title);

            TextView subtitle = createInterestText();
            subtitle.setText(getString(
                    R.string.property_interest_subtitle,
                    PropertyPageClient.getSourceName(interest.getTerm()),
                    formatArea(interest.getMinimumArea()),
                    formatArea(interest.getMaximumArea()),
                    amountFormat.format(interest.getMaximumPrice())
            ));
            subtitle.setTextColor(getColor(R.color.text_secondary));
            subtitle.setTextSize(12);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            textContainer.addView(subtitle);

            row.addView(textContainer, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        } else {
            LinearLayout textContainer = new LinearLayout(this);
            textContainer.setOrientation(LinearLayout.HORIZONTAL);
            textContainer.setGravity(Gravity.CENTER_VERTICAL);

            TextView product = createInterestText();
            product.setText(interest.getTerm());
            product.setEllipsize(TextUtils.TruncateAt.END);
            textContainer.addView(product, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView price = createInterestText();
            price.setText(getString(
                    R.string.price_interest_value,
                    amountFormat.format(interest.getMaximumPrice())));
            textContainer.addView(price, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            row.addView(textContainer, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private TextView createInterestText() {
        TextView text = new TextView(this);
        text.setTextColor(getColor(R.color.text_primary));
        text.setTextSize(13);
        text.setSingleLine(true);
        text.setPadding(0, 0, dp(3), 0);
        return text;
    }

    private String formatArea(double area) {
        NumberFormat format = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        format.setMaximumFractionDigits(1);
        return format.format(area);
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        divider.setLayoutParams(params);
        return divider;
    }

    private TextView createEmptyText(int textResource) {
        TextView text = new TextView(this);
        text.setText(textResource);
        text.setTextColor(getColor(R.color.text_secondary));
        text.setTextSize(13);
        text.setPadding(dp(10), dp(8), dp(10), dp(10));
        return text;
    }

    private ImageButton createEditInterestButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_edit);
        button.setColorFilter(getColor(R.color.action));
        button.setBackgroundResource(R.drawable.bg_icon_circle);
        button.setContentDescription(getString(R.string.action_edit_interest));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(7), dp(7), dp(7), dp(7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
        params.rightMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private void showRemoveInterestConfirmation(Interest interest) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(R.string.remove_alert_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(getString(R.string.remove_alert_dialog_message, interest.getTerm()));
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
            interestRepository.remove(interest.getId());
            CouponPageMonitor.getInstance().clearState(this, interest.getId());
            PropertyPageMonitor.getInstance().clearState(this, interest.getId());
            offerRepository.clearProcessedForInterest(interest.getId());
            offerRepository.reconcileRecentWithInterests(interestRepository.getAll());
            MonitorServiceController.update(this);
            dialog.dismiss();
            renderInterests();
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

    private void showInterestDialog(Interest interestToEdit, String requestedType) {
        boolean editing = interestToEdit != null;
        boolean couponAlert = Interest.TYPE_COUPON.equals(requestedType);
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(couponAlert
                ? (editing ? R.string.coupon_dialog_edit_title : R.string.coupon_dialog_title)
                : (editing ? R.string.interest_dialog_edit_title : R.string.interest_dialog_title));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(couponAlert
                ? R.string.coupon_dialog_summary
                : R.string.interest_dialog_summary);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(6), 0, dp(16));
        content.addView(message);

        EditText termInput = createDialogInput(
                couponAlert ? R.string.coupon_url_hint : R.string.interest_term_hint,
                couponAlert
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        if (editing) {
            termInput.setText(interestToEdit.getTerm());
            termInput.setSelection(couponAlert ? 0 : termInput.length());
        }
        if (couponAlert) {
            termInput.setSingleLine(false);
            termInput.setMinLines(2);
            termInput.setMaxLines(4);
            termInput.setHorizontallyScrolling(false);
            termInput.setTextSize(13);
            termInput.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            termInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        }
        content.addView(termInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                couponAlert ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(52)
        ));

        EditText priceInput = createDialogInput(
                couponAlert
                        ? R.string.coupon_minimum_value_hint
                        : R.string.interest_price_hint,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        if (editing) {
            priceInput.setText(formatEditablePrice(interestToEdit.getMaximumPrice()));
        }
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        priceParams.topMargin = dp(12);
        content.addView(priceInput, priceParams);

        View suggestionView;
        if (couponAlert) {
            HighestCouponSuggestionView couponSuggestion =
                    new HighestCouponSuggestionView(this);
            couponSuggestion.bind(termInput, priceInput);
            suggestionView = couponSuggestion;
        } else {
            LowestPriceSuggestionView priceSuggestion = new LowestPriceSuggestionView(this);
            priceSuggestion.bind(termInput, priceInput);
            suggestionView = priceSuggestion;
        }
        LinearLayout.LayoutParams suggestionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        suggestionParams.topMargin = dp(10);
        content.addView(suggestionView, suggestionParams);

        if (editing) {
            LinearLayout secondaryActions = new LinearLayout(this);
            secondaryActions.setGravity(Gravity.CENTER_VERTICAL);
            secondaryActions.setPadding(0, dp(12), 0, 0);
            TextView remove = createDialogAction(R.string.action_remove_interest);
            remove.setTextColor(getColor(R.color.danger));
            remove.setOnClickListener(view -> {
                dialog.dismiss();
                showRemoveInterestConfirmation(interestToEdit);
            });
            secondaryActions.addView(remove);
            if (!couponAlert) {
                TextView revalidate = createDialogAction(R.string.action_revalidate_history);
                revalidate.setOnClickListener(view -> {
                    dialog.dismiss();
                    revalidateInterestHistory(interestToEdit);
                });
                LinearLayout.LayoutParams revalidateParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                revalidateParams.leftMargin = dp(12);
                secondaryActions.addView(revalidate, revalidateParams);
            }
            content.addView(secondaryActions);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(14), 0, 0);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView save = createPrimaryDialogAction(R.string.action_save);
        save.setOnClickListener(view -> {
            String term = termInput.getText().toString().trim();
            String priceText = priceInput.getText().toString().trim().replace(',', '.');
            if (term.isEmpty()) {
                termInput.setError(getString(couponAlert
                        ? R.string.coupon_url_required
                        : R.string.interest_term_required));
                return;
            }
            if (couponAlert && !CouponPageClient.isSupported(term)) {
                termInput.setError(getString(R.string.coupon_url_unsupported));
                return;
            }
            double maximumPrice;
            try {
                maximumPrice = Double.parseDouble(priceText);
            } catch (NumberFormatException exception) {
                priceInput.setError(getString(couponAlert
                        ? R.string.coupon_value_required
                        : R.string.interest_price_required));
                return;
            }
            if (maximumPrice <= 0) {
                priceInput.setError(getString(couponAlert
                        ? R.string.coupon_value_required
                        : R.string.interest_price_required));
                return;
            }
            if (couponAlert) {
                term = CouponPageClient.normalizeSupportedUrl(term);
            }
            dialog.dismiss();
            updateInterestInBackground(interestToEdit, term, maximumPrice, couponAlert);
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
        );
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
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

    private void showPropertyDialog(Interest interestToEdit) {
        boolean editing = interestToEdit != null;
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(editing
                ? R.string.property_dialog_edit_title
                : R.string.property_dialog_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(R.string.property_dialog_summary);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setPadding(0, dp(6), 0, dp(16));
        content.addView(message);

        EditText propertyNameInput = createDialogInput(
                R.string.property_name_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
        );
        propertyNameInput.setSingleLine(true);
        if (editing) {
            propertyNameInput.setText(interestToEdit.getPropertyName());
            propertyNameInput.setSelection(propertyNameInput.length());
        }
        content.addView(propertyNameInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        EditText urlInput = createDialogInput(
                R.string.property_url_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setMaxLines(4);
        urlInput.setHorizontallyScrolling(false);
        urlInput.setTextSize(13);
        urlInput.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        urlInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        if (editing) {
            urlInput.setText(interestToEdit.getTerm());
            urlInput.setSelection(0);
        }
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        urlParams.topMargin = dp(12);
        content.addView(urlInput, urlParams);

        EditText minimumAreaInput = createPropertyNumberInput(
                R.string.property_minimum_area_hint,
                editing ? interestToEdit.getMinimumArea() : 0d
        );
        EditText maximumAreaInput = createPropertyNumberInput(
                R.string.property_maximum_area_hint,
                editing ? interestToEdit.getMaximumArea() : 0d
        );
        EditText maximumPriceInput = createDialogInput(
                R.string.property_maximum_price_hint,
                InputType.TYPE_CLASS_NUMBER
        );
        configureWholeCurrencyInput(
                maximumPriceInput,
                editing ? interestToEdit.getMaximumPrice() : 0d
        );

        LinearLayout areaInputs = new LinearLayout(this);
        areaInputs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams minimumAreaParams = new LinearLayout.LayoutParams(
                0,
                dp(52),
                1
        );
        areaInputs.addView(minimumAreaInput, minimumAreaParams);
        LinearLayout.LayoutParams maximumAreaParams = new LinearLayout.LayoutParams(
                0,
                dp(52),
                1
        );
        maximumAreaParams.leftMargin = dp(10);
        areaInputs.addView(maximumAreaInput, maximumAreaParams);
        LinearLayout.LayoutParams areaRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        areaRowParams.topMargin = dp(12);
        content.addView(areaInputs, areaRowParams);
        addDialogInputWithMargin(content, maximumPriceInput);

        PropertyLowestPriceSuggestionView propertySuggestion =
                new PropertyLowestPriceSuggestionView(this);
        propertySuggestion.bind(
                urlInput,
                minimumAreaInput,
                maximumAreaInput,
                maximumPriceInput
        );
        LinearLayout.LayoutParams suggestionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        suggestionParams.topMargin = dp(10);
        content.addView(propertySuggestion, suggestionParams);

        if (editing) {
            LinearLayout secondaryActions = new LinearLayout(this);
            secondaryActions.setGravity(Gravity.CENTER_VERTICAL);
            secondaryActions.setPadding(0, dp(12), 0, 0);
            TextView remove = createDialogAction(R.string.action_remove_interest);
            remove.setTextColor(getColor(R.color.danger));
            remove.setOnClickListener(view -> {
                dialog.dismiss();
                showRemoveInterestConfirmation(interestToEdit);
            });
            secondaryActions.addView(remove);
            content.addView(secondaryActions);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(14), 0, 0);
        TextView cancel = createDialogAction(R.string.action_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        TextView save = createPrimaryDialogAction(R.string.action_save);
        save.setOnClickListener(view -> {
            String pageUrl = urlInput.getText().toString().trim();
            if (pageUrl.isEmpty()) {
                urlInput.setError(getString(R.string.property_url_required));
                return;
            }
            String normalizedUrl = PropertyPageClient.normalizeSupportedUrl(pageUrl);
            if (normalizedUrl == null) {
                urlInput.setError(getString(R.string.property_url_unsupported));
                return;
            }
            Double minimumArea = parsePositiveNumber(minimumAreaInput);
            Double maximumArea = parsePositiveNumber(maximumAreaInput);
            if (minimumArea == null) {
                minimumAreaInput.setError(getString(R.string.property_area_required));
                return;
            }
            if (maximumArea == null) {
                maximumAreaInput.setError(getString(R.string.property_area_required));
                return;
            }
            if (maximumArea < minimumArea) {
                maximumAreaInput.setError(getString(R.string.property_area_range_invalid));
                return;
            }
            Double maximumPrice = CurrencyTextFormatter.parseWholeReais(
                    maximumPriceInput.getText()
            );
            if (maximumPrice == null) {
                maximumPriceInput.setError(getString(R.string.property_price_required));
                return;
            }
            dialog.dismiss();
            updatePropertyInBackground(
                    interestToEdit,
                    normalizedUrl,
                    propertyNameInput.getText().toString(),
                    minimumArea,
                    maximumArea,
                    maximumPrice
            );
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
        );
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
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

    private EditText createPropertyNumberInput(int hintResource, double initialValue) {
        EditText input = createDialogInput(
                hintResource,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        input.setOnFocusChangeListener((view, hasFocus) -> {
            Double value = parsePositiveNumber(input);
            if (value == null) {
                return;
            }
            String formatted = hasFocus
                    ? formatEditablePrice(value)
                    : getString(R.string.property_area_value, formatArea(value));
            input.setText(formatted);
            if (hasFocus) {
                input.setSelection(formatted.length());
            }
        });
        if (initialValue > 0d) {
            input.setText(getString(
                    R.string.property_area_value,
                    formatArea(initialValue)
            ));
        }
        return input;
    }

    private void configureWholeCurrencyInput(EditText input, double initialValue) {
        input.addTextChangedListener(new SimpleTextWatcher() {
            private boolean formatting;

            @Override
            public void afterTextChanged(Editable editable) {
                if (formatting) {
                    return;
                }
                String formatted = CurrencyTextFormatter.formatWholeReais(editable);
                if (formatted.contentEquals(editable)) {
                    return;
                }
                formatting = true;
                input.setText(formatted);
                input.setSelection(formatted.length());
                formatting = false;
            }
        });
        if (initialValue > 0d) {
            input.setText(CurrencyTextFormatter.formatWholeReais(initialValue));
            input.setSelection(input.length());
        }
    }

    private void addDialogInputWithMargin(LinearLayout content, EditText input) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        params.topMargin = dp(12);
        content.addView(input, params);
    }

    private Double parsePositiveNumber(EditText input) {
        try {
            String numericText = input.getText().toString()
                    .replaceAll("[^0-9,.]", "")
                    .trim();
            double value = Double.parseDouble(
                    numericText.replace(',', '.')
            );
            return value > 0d ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void revalidateInterestHistory(Interest interest) {
        Dialog updatingDialog = showUpdatingDialog();
        alertUpdateExecutor.execute(() -> {
            OfferMonitor.getInstance().revalidateInterestHistory(this, interest);
            runOnUiThread(() -> {
                if (updatingDialog.isShowing()) {
                    updatingDialog.dismiss();
                }
                renderInterests();
            });
        });
    }

    private void updateInterestInBackground(Interest interestToEdit, String term,
                                            double maximumPrice, boolean couponAlert) {
        boolean editing = interestToEdit != null;
        Dialog updatingDialog = showUpdatingDialog();
        long shownAt = SystemClock.elapsedRealtime();
        alertUpdateExecutor.execute(() -> {
            boolean succeeded = true;
            long savedInterestId = editing ? interestToEdit.getId() : 0L;
            try {
                if (editing) {
                    interestRepository.update(interestToEdit.getId(), term, maximumPrice);
                    CouponPageMonitor.getInstance().clearState(this, interestToEdit.getId());
                    offerRepository.clearProcessedForInterest(interestToEdit.getId());
                    offerRepository.clearRecentForInterest(interestToEdit.getId());
                } else {
                    savedInterestId = couponAlert
                            ? interestRepository.addCoupon(term, maximumPrice)
                            : interestRepository.add(term, maximumPrice);
                }
                offerRepository.reconcileRecentWithInterests(interestRepository.getAll());
                boolean monitorWasEnabled = getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                        .getBoolean(MONITOR_ENABLED, true);
                if (!monitorWasEnabled) {
                    getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(MONITOR_ENABLED, true)
                            .apply();
                    CloudSyncStore.rememberMonitorChanged(this, System.currentTimeMillis());
                }
            } catch (RuntimeException exception) {
                succeeded = false;
            }
            boolean updateSucceeded = succeeded;
            long interestIdForHistory = savedInterestId;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    updatingDialog.dismiss();
                    return;
                }
                if (updateSucceeded) {
                    requestNotificationPermissionIfNeeded();
                    MonitorServiceController.update(this);
                    if (couponAlert) {
                        CouponPageMonitor.getInstance().checkNow(this);
                    } else {
                        OfferMonitor.getInstance().refreshInterestHistory(
                                this,
                                interestIdForHistory,
                                term,
                                maximumPrice
                        );
                    }
                }
                long remaining = Math.max(
                        0L,
                        650L - (SystemClock.elapsedRealtime() - shownAt)
                );
                priceInterestsContainer.postDelayed(() -> {
                    if (updatingDialog.isShowing()) {
                        updatingDialog.dismiss();
                    }
                    if (updateSucceeded) {
                        renderInterests();
                    } else {
                        AppErrorStore.recordSerious(
                                this,
                                "Alertas",
                                getString(R.string.alert_update_failed)
                        );
                    }
                }, remaining);
            });
        });
    }

    private void updatePropertyInBackground(Interest interestToEdit, String pageUrl,
                                            String requestedPropertyName,
                                            double minimumArea, double maximumArea,
                                            double maximumPrice) {
        boolean editing = interestToEdit != null;
        Dialog updatingDialog = showUpdatingDialog();
        long shownAt = SystemClock.elapsedRealtime();
        alertUpdateExecutor.execute(() -> {
            boolean succeeded = true;
            long savedInterestId = editing ? interestToEdit.getId() : 0L;
            try {
                String propertyName = PropertyPageResult.normalizeCondominiumName(
                        requestedPropertyName);
                if (propertyName.isEmpty()) {
                    try {
                        PropertyPageResult propertyPage = PropertyPageClient.fetch(pageUrl);
                        if (propertyPage.hasCondominiumName()) {
                            propertyName = propertyPage.getCondominiumName();
                        }
                    } catch (Exception ignored) {
                        // Mantém o campo vazio quando a página estiver temporariamente indisponível.
                    }
                }
                if (editing) {
                    interestRepository.updateProperty(
                            interestToEdit.getId(),
                            pageUrl,
                            minimumArea,
                            maximumArea,
                            maximumPrice,
                            propertyName
                    );
                    PropertyPageMonitor.getInstance().clearState(this, interestToEdit.getId());
                    offerRepository.clearProcessedForInterest(interestToEdit.getId());
                    offerRepository.clearRecentForInterest(interestToEdit.getId());
                } else {
                    savedInterestId = interestRepository.addProperty(
                            pageUrl,
                            minimumArea,
                            maximumArea,
                            maximumPrice,
                            propertyName
                    );
                }
                offerRepository.reconcileRecentWithInterests(interestRepository.getAll());
                boolean monitorWasEnabled = getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                        .getBoolean(MONITOR_ENABLED, true);
                if (!monitorWasEnabled) {
                    getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(MONITOR_ENABLED, true)
                            .apply();
                    CloudSyncStore.rememberMonitorChanged(this, System.currentTimeMillis());
                }
            } catch (RuntimeException exception) {
                succeeded = false;
            }
            boolean updateSucceeded = succeeded;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    updatingDialog.dismiss();
                    return;
                }
                if (updateSucceeded) {
                    requestNotificationPermissionIfNeeded();
                    MonitorServiceController.update(this);
                    PropertyPageMonitor.getInstance().checkNow(this);
                }
                long remaining = Math.max(
                        0L,
                        650L - (SystemClock.elapsedRealtime() - shownAt)
                );
                propertyInterestsContainer.postDelayed(() -> {
                    if (updatingDialog.isShowing()) {
                        updatingDialog.dismiss();
                    }
                    if (updateSucceeded) {
                        renderInterests();
                    } else {
                        AppErrorStore.recordSerious(
                                this,
                                "Alertas",
                                getString(R.string.alert_update_failed)
                        );
                    }
                }, remaining);
            });
        });
    }

    private Dialog showUpdatingDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setCancelable(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(20));
        content.setBackgroundResource(R.drawable.bg_dialog);

        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setBackgroundResource(R.drawable.bg_icon_circle);
        gear.setPadding(dp(11), dp(11), dp(11), dp(11));
        content.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        textParams.leftMargin = dp(16);
        content.addView(texts, textParams);

        TextView title = new TextView(this);
        title.setText(R.string.alert_updating_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(18);
        texts.addView(title);

        TextView summary = new TextView(this);
        summary.setText(R.string.alert_updating_summary);
        summary.setTextColor(getColor(R.color.text_secondary));
        summary.setTextSize(14);
        summary.setPadding(0, dp(3), 0, 0);
        texts.addView(summary);

        ObjectAnimator rotation = ObjectAnimator.ofFloat(gear, View.ROTATION, 0f, 360f);
        rotation.setDuration(1100L);
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        rotation.setInterpolator(new LinearInterpolator());
        dialog.setOnShowListener(ignored -> rotation.start());
        dialog.setOnDismissListener(ignored -> rotation.cancel());
        dialog.setContentView(content);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = getResources().getDisplayMetrics().widthPixels - dp(44);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.55f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
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
        action.setSingleLine(true);
        action.setPadding(dp(10), dp(8), dp(10), dp(8));
        return action;
    }

    private TextView createPrimaryDialogAction(int textResource) {
        TextView action = createDialogAction(textResource);
        action.setTextColor(getColor(R.color.button_text));
        action.setBackgroundResource(R.drawable.bg_button_primary);
        action.setMinWidth(dp(96));
        action.setPadding(dp(20), 0, dp(20), 0);
        return action;
    }

    private String formatEditablePrice(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value).replace('.', ',');
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
