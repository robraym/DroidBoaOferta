package br.com.droidboaoferta;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Locale;

final class HighestCouponSuggestionView extends LinearLayout {
    private final LinearLayout actionRow;
    private final ImageView actionIcon;
    private final TextView actionText;
    private final TextView statusText;
    private final ObjectAnimator rotation;
    private EditText urlInput;
    private EditText valueInput;
    private boolean searching;

    HighestCouponSuggestionView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        actionRow = new LinearLayout(context);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setOrientation(HORIZONTAL);
        actionRow.setBackgroundResource(R.drawable.bg_button_secondary);
        actionRow.setClickable(true);
        actionRow.setFocusable(true);
        actionRow.setMinimumHeight(dp(46));
        actionRow.setPadding(dp(14), 0, dp(14), 0);

        actionIcon = new ImageView(context);
        actionIcon.setImageResource(R.drawable.ic_coupon_alert);
        actionIcon.setContentDescription(context.getString(R.string.highest_coupon_action));
        actionRow.addView(actionIcon, new LayoutParams(dp(21), dp(21)));

        actionText = new TextView(context);
        actionText.setText(R.string.highest_coupon_action);
        actionText.setTextColor(context.getColor(R.color.action));
        actionText.setTextSize(14.5f);
        LayoutParams textParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.leftMargin = dp(9);
        actionRow.addView(actionText, textParams);
        addView(actionRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(46)));

        statusText = new TextView(context);
        statusText.setTextColor(context.getColor(R.color.text_secondary));
        statusText.setTextSize(13);
        statusText.setPadding(dp(4), dp(7), dp(4), 0);
        statusText.setVisibility(GONE);
        addView(statusText, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        rotation = ObjectAnimator.ofFloat(actionIcon, View.ROTATION, 0f, 360f);
        rotation.setDuration(900L);
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        rotation.setInterpolator(new LinearInterpolator());
        actionRow.setOnClickListener(view -> search());
    }

    void bind(EditText urlInput, EditText valueInput) {
        this.urlInput = urlInput;
        this.valueInput = valueInput;
        this.valueInput.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    private void search() {
        if (searching || urlInput == null || valueInput == null) {
            return;
        }
        String url = urlInput.getText().toString().trim();
        if (!CouponPageClient.isSupported(url)) {
            urlInput.setError(getContext().getString(R.string.coupon_url_unsupported));
            return;
        }
        setSearching(true);
        CouponPageClient.fetchHighestAsync(url, (coupon, errorMessageResource) -> {
            setSearching(false);
            if (coupon == null) {
                statusText.setText(errorMessageResource);
                statusText.setTextColor(getContext().getColor(R.color.text_secondary));
            } else {
                String editableValue = formatEditableValue(coupon.getValue());
                valueInput.setText(editableValue);
                valueInput.setSelection(editableValue.length());
                statusText.setText(getContext().getString(
                        R.string.highest_coupon_found_format,
                        formatCurrency(coupon.getValue()),
                        coupon.getCode()
                ));
                statusText.setTextColor(getContext().getColor(R.color.action));
            }
            statusText.setVisibility(VISIBLE);
        });
    }

    private void setSearching(boolean searching) {
        this.searching = searching;
        actionRow.setEnabled(!searching);
        urlInput.setEnabled(!searching);
        valueInput.setEnabled(!searching);
        if (searching) {
            actionIcon.setImageResource(R.drawable.ic_sync);
            actionText.setText(R.string.highest_coupon_searching_action);
            statusText.setText(R.string.highest_coupon_searching);
            statusText.setTextColor(getContext().getColor(R.color.text_secondary));
            statusText.setVisibility(VISIBLE);
            rotation.start();
        } else {
            rotation.cancel();
            actionIcon.setRotation(0f);
            actionIcon.setImageResource(R.drawable.ic_coupon_alert);
            actionText.setText(R.string.highest_coupon_action);
        }
    }

    private String formatEditableValue(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.2f", value).replace('.', ',');
    }

    private String formatCurrency(double value) {
        return NumberFormat.getCurrencyInstance(new Locale("pt", "BR")).format(value);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
