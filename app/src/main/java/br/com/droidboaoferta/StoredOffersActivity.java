package br.com.droidboaoferta;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

abstract class StoredOffersActivity extends AppCompatActivity {
    private OfferRepository offerRepository;
    private LinearLayout offersContainer;
    private ImageButton headerAction;

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

    int getHeaderConfirmationTitle() {
        return 0;
    }

    int getHeaderConfirmationMessage() {
        return 0;
    }

    void runHeaderAction(OfferRepository repository) {
    }

    abstract void deleteOffer(OfferRepository repository, String id);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offer_list);

        offerRepository = new OfferRepository(this);
        offersContainer = findViewById(R.id.container_offers);
        ((TextView) findViewById(R.id.text_screen_title)).setText(getTitleResource());
        findViewById(R.id.button_back).setOnClickListener(view -> finish());
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
    protected void onResume() {
        super.onResume();
        renderOffers();
    }

    private void renderOffers() {
        offersContainer.removeAllViews();
        List<ObservedOffer> offers = getOffers(offerRepository);
        if (headerAction != null) {
            headerAction.setVisibility(hasHeaderAction() && !offers.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (offers.isEmpty()) {
            offersContainer.addView(createEmptyText());
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
        for (int index = 0; index < offers.size(); index++) {
            ObservedOffer offer = offers.get(index);
            LinearLayout row = createOfferRow(
                    offer,
                    currency.format(offer.getPrice()),
                    timeFormat.format(new Date(offer.getObservedAt())),
                    offer.getSource()
            );
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (index > 0) {
                params.topMargin = dp(6);
            }
            offersContainer.addView(row, params);
        }
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
        row.setBackgroundResource(R.drawable.bg_offer_row);
        row.setPadding(dp(12), dp(9), dp(8), dp(8));

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
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        mainLine.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView priceView = new TextView(this);
        priceView.setText(price);
        priceView.setTextColor(getColor(R.color.text_primary));
        priceView.setTextSize(15);
        priceView.setSingleLine(true);
        priceView.setPadding(dp(10), 0, 0, 0);
        mainLine.addView(priceView);
        texts.addView(mainLine);

        LinearLayout metaLine = new LinearLayout(this);
        metaLine.setOrientation(LinearLayout.HORIZONTAL);
        metaLine.setGravity(Gravity.CENTER_VERTICAL);
        metaLine.setPadding(0, dp(2), 0, 0);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextColor(getColor(R.color.action));
        timeView.setTextSize(12);
        timeView.setSingleLine(true);
        metaLine.addView(timeView);

        TextView sourceView = new TextView(this);
        sourceView.setText(source);
        sourceView.setTextColor(getColor(R.color.text_secondary));
        sourceView.setTextSize(12);
        sourceView.setSingleLine(true);
        sourceView.setEllipsize(TextUtils.TruncateAt.END);
        sourceView.setPadding(dp(8), 0, 0, 0);
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
            row.addView(delete, deleteParams);
        }

        if (!offer.getLink().isEmpty()) {
            row.setOnClickListener(view -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getLink()))
            ));
        }
        return row;
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
}
