package br.com.droidboaoferta;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class AlertsActivity extends AppCompatActivity {
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String MONITOR_ENABLED = "monitor_enabled";

    private InterestRepository interestRepository;
    private OfferRepository offerRepository;
    private LinearLayout interestsContainer;
    private EditText interestsSearchInput;
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
        BottomNavigationController.setup(this, BottomNavigationController.ITEM_ALERTS);

        interestRepository = new InterestRepository(this);
        offerRepository = new OfferRepository(this);
        interestsContainer = findViewById(R.id.container_interests);
        interestsSearchInput = findViewById(R.id.input_search_interests);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        findViewById(R.id.button_add_interest).setOnClickListener(view -> showInterestDialog(null));
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
        unregisterReceiver(syncReceiver);
        super.onStop();
    }

    private void renderInterests() {
        List<Interest> interests = filterInterests(
                interestRepository.getAll(),
                interestsSearchInput.getText().toString()
        );
        interestsContainer.removeAllViews();
        if (interests.isEmpty()) {
            interestsContainer.addView(createEmptyText(R.string.dashboard_no_interests));
            return;
        }

        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        for (int index = 0; index < interests.size(); index++) {
            Interest interest = interests.get(index);
            LinearLayout row = createInterestRow(getString(
                    R.string.dashboard_interest_single_line,
                    interest.getTerm(),
                    currency.format(interest.getMaximumPrice())
            ));
            ImageButton edit = createEditInterestButton();
            edit.setOnClickListener(view -> showInterestDialog(interest));
            row.addView(edit, 0);
            ImageButton remove = createRemoveInterestButton();
            remove.setOnClickListener(view -> showRemoveInterestConfirmation(interest));
            row.addView(remove);
            interestsContainer.addView(row);
            if (index < interests.size() - 1) {
                interestsContainer.addView(createDivider());
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
            String text = interest.getTerm() + " " + currency.format(interest.getMaximumPrice())
                    + " " + interest.getMaximumPrice();
            if (OfferTextParser.normalize(text).contains(normalizedQuery)) {
                filtered.add(interest);
            }
        }
        return filtered;
    }

    private LinearLayout createInterestRow(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(getColor(R.color.card));
        row.setPadding(dp(6), dp(7), dp(6), dp(7));

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, 0, dp(4), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View createDivider() {
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton createRemoveInterestButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_delete);
        button.setColorFilter(getColor(R.color.danger));
        button.setBackgroundResource(R.drawable.bg_icon_danger);
        button.setContentDescription(getString(R.string.action_remove_interest));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
        params.leftMargin = dp(6);
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
            offerRepository.clearProcessedForInterest(interest.getId());
            offerRepository.reconcileRecentWithInterests(interestRepository.getAll());
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

    private void showInterestDialog(Interest interestToEdit) {
        boolean editing = interestToEdit != null;
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(16));
        content.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(editing ? R.string.interest_dialog_edit_title : R.string.interest_dialog_title);
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
        if (editing) {
            termInput.setText(interestToEdit.getTerm());
            termInput.setSelection(termInput.length());
        }
        content.addView(termInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        EditText priceInput = createDialogInput(
                R.string.interest_price_hint,
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
            if (editing) {
                interestRepository.update(interestToEdit.getId(), term, maximumPrice);
                offerRepository.clearProcessedForInterest(interestToEdit.getId());
            } else {
                interestRepository.add(term, maximumPrice);
            }
            offerRepository.reconcileRecentWithInterests(interestRepository.getAll());
            getSharedPreferences(OFFER_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MONITOR_ENABLED, true)
                    .apply();
            CloudSyncStore.rememberMonitorChanged(this, System.currentTimeMillis());
            CloudSyncStore.markLocalChanged(this);
            TelegramClientManager.getInstance().refreshSelectedGroupsHistory();
            dialog.dismiss();
            renderInterests();
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
