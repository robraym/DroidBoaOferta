package br.com.droidboaoferta;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

abstract class StoredOffersActivity extends AppCompatActivity {
    private OfferRepository offerRepository;
    private LinearLayout offersContainer;
    private EditText searchInput;
    private ImageButton headerAction;
    private LinearLayout cardHeader;
    private FloatingSearchController floatingSearchController;
    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderOffers();
        }
    };

    abstract int getTitleResource();

    abstract int getEmptyTextResource();

    abstract List<ObservedOffer> getOffers(OfferRepository repository);

    boolean hasLeadingAction() {
        return false;
    }

    int getLeadingActionIcon() {
        return 0;
    }

    int getLeadingActionDescription() {
        return 0;
    }

    int getLeadingActionBackground() {
        return R.drawable.bg_button_inline;
    }

    void runLeadingAction(OfferRepository repository, String id) {
    }

    boolean hasSecondaryAction() {
        return false;
    }

    int getSecondaryActionIcon() {
        return 0;
    }

    int getSecondaryActionDescription() {
        return 0;
    }

    int getSecondaryActionBackground() {
        return R.drawable.bg_button_inline;
    }

    void runSecondaryAction(OfferRepository repository, String id) {
    }

    boolean hasDeleteAction() {
        return true;
    }

    int getDeleteConfirmationTitle() {
        return R.string.delete_offer_dialog_title;
    }

    int getDeleteConfirmationMessage() {
        return R.string.delete_offer_dialog_message;
    }

    boolean hasHeaderAction() {
        return false;
    }

    int getHeaderActionIcon() {
        return 0;
    }

    int getHeaderActionDescription() {
        return 0;
    }

    int getHeaderActionBackground() {
        return R.drawable.bg_button_inline;
    }

    int getCardTitleResource() {
        return getTitleResource();
    }

    int getHeaderConfirmationTitle() {
        return 0;
    }

    int getHeaderConfirmationMessage() {
        return 0;
    }

    void runHeaderAction(OfferRepository repository) {
    }

    abstract void deleteOffer(OfferRepository repository, String id);

    int getBottomNavigationItem() {
        return BottomNavigationController.ITEM_NONE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offer_list);
        BottomNavigationController.setup(
                this,
                getBottomNavigationItem(),
                R.id.navigation_animated_content
        );

        offerRepository = new OfferRepository(this);
        offersContainer = findViewById(R.id.container_offers);
        floatingSearchController = FloatingSearchController.attach(
                this,
                "stored_" + getBottomNavigationItem(),
                R.id.floating_search_dismiss_surface
        );
        searchInput = floatingSearchController.getInput();
        ((TextView) findViewById(R.id.text_screen_title)).setText(getTitleResource());
        findViewById(R.id.button_profile).setOnClickListener(view -> startActivity(
                new Intent(this, ProfileActivity.class)
        ));
        searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                renderOffers();
            }
        });
        cardHeader = findViewById(R.id.container_card_header);
        ((TextView) findViewById(R.id.text_card_title)).setText(getCardTitleResource());
        headerAction = findViewById(R.id.button_header_action);
        if (hasHeaderAction()) {
            headerAction.setImageResource(getHeaderActionIcon());
            headerAction.setBackgroundResource(getHeaderActionBackground());
            headerAction.setContentDescription(getString(getHeaderActionDescription()));
            headerAction.setOnClickListener(view -> {
                if (getOffers(offerRepository).isEmpty()) {
                    headerAction.setVisibility(View.GONE);
                    return;
                }
                if (getHeaderConfirmationTitle() != 0 && getHeaderConfirmationMessage() != 0) {
                    showHeaderConfirmationDialog();
                } else {
                    performHeaderAction();
                }
            });
        } else {
            headerAction.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelegramClientManager.getInstance().start(this);
        ContextCompat.registerReceiver(
                this,
                syncReceiver,
                new IntentFilter(TelegramClientManager.ACTION_CLOUD_SYNC_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onStop() {
        floatingSearchController.collapse(false);
        unregisterReceiver(syncReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationController.resetInitialFocus(this);
        renderOffers();
    }

    private void renderOffers() {
        offersContainer.removeAllViews();
        List<ObservedOffer> offers = getOffers(offerRepository);
        List<ObservedOffer> visibleOffers = filterOffers(offers, searchInput.getText().toString());
        if (headerAction != null) {
            cardHeader.setVisibility(View.VISIBLE);
            headerAction.setVisibility(hasHeaderAction() && !offers.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (visibleOffers.isEmpty()) {
            offersContainer.addView(createEmptyText());
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        String previousGroup = null;
        for (int index = 0; index < visibleOffers.size(); index++) {
            ObservedOffer offer = visibleOffers.get(index);
            String group = OfferDateFormatter.getGroupKey(offer.getObservedAt());
            if (!group.equals(previousGroup)) {
                if (previousGroup != null) {
                    offersContainer.addView(createDateGroupDivider());
                }
                offersContainer.addView(createOfferGroupHeader(
                        OfferDateFormatter.formatGroupLabel(this, offer.getObservedAt()),
                        previousGroup != null
                ));
                previousGroup = group;
            } else {
                offersContainer.addView(createOfferDivider());
            }
            LinearLayout row = createOfferRow(
                    offer,
                    currency.format(offer.getPrice()),
                    OfferDateFormatter.formatTime(offer.getObservedAt()),
                    offer.getSource()
            );
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            offersContainer.addView(row, params);
        }
    }

    private TextView createOfferGroupHeader(String label, boolean hasPreviousGroup) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextColor(getColor(R.color.text_secondary));
        header.setTextSize(13);
        int titleStart = hasLeadingAction() ? 48 : 6;
        header.setPadding(dp(titleStart), dp(hasPreviousGroup ? 10 : 8), dp(8), dp(5));
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

    private void showHeaderConfirmationDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(getHeaderConfirmationTitle());
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(getHeaderConfirmationMessage());
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
            performHeaderAction();
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

    private void performHeaderAction() {
        runHeaderAction(offerRepository);
        renderOffers();
    }

    private LinearLayout createOfferRow(ObservedOffer offer, String price, String time, String source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(getColor(R.color.card));
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(6), dp(7), dp(6), dp(7));

        if (hasLeadingAction()) {
            ImageButton leading = createActionButton(
                    getLeadingActionIcon(),
                    getLeadingActionBackground(),
                    R.color.action,
                    getLeadingActionDescription()
            );
            leading.setOnClickListener(view -> {
                runLeadingAction(offerRepository, offer.getId());
                renderOffers();
            });
            LinearLayout.LayoutParams leadingParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            leadingParams.rightMargin = dp(8);
            row.addView(leading, leadingParams);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        LinearLayout mainLine = new LinearLayout(this);
        mainLine.setGravity(Gravity.CENTER_VERTICAL);
        mainLine.setOrientation(LinearLayout.HORIZONTAL);

        TextView titleView = new TextView(this);
        titleView.setText(offer.getInterest());
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
        texts.addView(mainLine);

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
        texts.addView(metaLine);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (hasSecondaryAction()) {
            ImageButton secondary = createActionButton(
                    getSecondaryActionIcon(),
                    getSecondaryActionBackground(),
                    R.color.action,
                    getSecondaryActionDescription()
            );
            secondary.setOnClickListener(view -> {
                runSecondaryAction(offerRepository, offer.getId());
                renderOffers();
            });
            LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            secondaryParams.leftMargin = dp(8);
            secondaryParams.rightMargin = dp(4);
            row.addView(secondary, secondaryParams);
        }

        if (hasDeleteAction()) {
            ImageButton delete = createActionButton(
                    R.drawable.ic_delete,
                    R.drawable.bg_icon_danger,
                    R.color.danger,
                    R.string.action_delete_offer
            );
            delete.setOnClickListener(view -> {
                showDeleteConfirmationDialog(offer);
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            deleteParams.leftMargin = dp(8);
            deleteParams.rightMargin = dp(4);
            row.addView(delete, deleteParams);
        }

        if (!offer.getLink().isEmpty()) {
            row.setOnClickListener(view -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink()))
            ));
        }
        return row;
    }

    private View createOfferDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(10);
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

    private void showDeleteConfirmationDialog(ObservedOffer offer) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(getDeleteConfirmationTitle());
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(getString(getDeleteConfirmationMessage(), offer.getInterest()));
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
            deleteOffer(offerRepository, offer.getId());
            dialog.dismiss();
            renderOffers();
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

    private ImageButton createActionButton(int iconResource, int backgroundResource, int colorResource,
                                           int descriptionResource) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setColorFilter(getColor(colorResource));
        button.setBackgroundResource(backgroundResource);
        button.setContentDescription(getString(descriptionResource));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        return button;
    }

    private TextView createEmptyText() {
        TextView text = new TextView(this);
        text.setText(getEmptyTextResource());
        text.setTextColor(getColor(R.color.text_secondary));
        text.setTextSize(13);
        text.setPadding(dp(10), dp(8), dp(10), dp(10));
        return text;
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
