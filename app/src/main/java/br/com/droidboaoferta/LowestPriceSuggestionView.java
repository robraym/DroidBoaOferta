package br.com.droidboaoferta;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.Locale;

final class LowestPriceSuggestionView extends LinearLayout {
    private final LinearLayout actionRow;
    private final ImageView actionIcon;
    private final TextView actionText;
    private final TextView statusText;
    private final ObjectAnimator rotation;
    private ValueAnimator priceAnimator;
    private final ArrayDeque<Double> pendingPrices = new ArrayDeque<>();
    private boolean priceAnimationRunning;
    private boolean completionPending;
    private double completedLowestPrice;
    private int completedStatusMessage;
    private EditText termInput;
    private EditText priceInput;
    private boolean searching;

    LowestPriceSuggestionView(Context context) {
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
        actionIcon.setImageResource(R.drawable.ic_search);
        actionIcon.setContentDescription(context.getString(R.string.lowest_price_action));
        actionRow.addView(actionIcon, new LayoutParams(dp(21), dp(21)));

        actionText = new TextView(context);
        actionText.setText(R.string.lowest_price_action);
        actionText.setTextColor(context.getColor(R.color.action));
        actionText.setTextSize(14.5f);
        LayoutParams textParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        textParams.leftMargin = dp(9);
        actionRow.addView(actionText, textParams);
        addView(actionRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(46)));

        statusText = new TextView(context);
        statusText.setTextColor(context.getColor(R.color.text_secondary));
        statusText.setTextSize(13);
        statusText.setPadding(dp(4), dp(7), dp(4), 0);
        statusText.setVisibility(GONE);
        addView(statusText, new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));

        rotation = ObjectAnimator.ofFloat(actionIcon, View.ROTATION, 0f, 360f);
        rotation.setDuration(900L);
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        rotation.setInterpolator(new LinearInterpolator());
        actionRow.setOnClickListener(view -> search());
    }

    void bind(EditText termInput, EditText priceInput) {
        this.termInput = termInput;
        this.priceInput = priceInput;
        this.priceInput.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
    }

    private void search() {
        if (searching || termInput == null || priceInput == null) {
            return;
        }
        String term = termInput.getText().toString().trim();
        if (term.isEmpty()) {
            termInput.setError(getContext().getString(R.string.interest_term_required));
            return;
        }
        setSearching(true);
        TelegramClientManager.getInstance().findLowestObservedPrice(term,
                new TelegramClientManager.LowestPriceCallback() {
                    @Override
                    public void onPriceFound(double lowestPrice) {
                        if (pendingPrices.size() >= 4) {
                            pendingPrices.clear();
                        }
                        pendingPrices.add(lowestPrice);
                        animateNextFoundPrice();
                    }

                    @Override
                    public void onCompleted(double lowestPrice, int statusMessageResource) {
                        completionPending = true;
                        completedLowestPrice = lowestPrice;
                        completedStatusMessage = statusMessageResource;
                        finishSearchWhenAnimationsEnd();
                    }
                }
        );
    }

    private void animateNextFoundPrice() {
        if (priceAnimationRunning || pendingPrices.isEmpty()) {
            finishSearchWhenAnimationsEnd();
            return;
        }
        double lowestPrice = pendingPrices.removeFirst();
        double currentValue = parseCurrentPrice();
        if (Double.isNaN(currentValue) || currentValue <= lowestPrice) {
            currentValue = lowestPrice + Math.max(1.0d, lowestPrice * 0.06d);
        }
        priceAnimator = ValueAnimator.ofFloat((float) currentValue, (float) lowestPrice);
        priceAnimator.setDuration(420L);
        priceAnimator.setInterpolator(new DecelerateInterpolator());
        priceAnimator.addUpdateListener(animation -> {
            double animatedPrice = ((Float) animation.getAnimatedValue()).doubleValue();
            setPriceValue(animatedPrice);
            statusText.setText(getContext().getString(
                    R.string.lowest_price_found_so_far,
                    formatCurrency(animatedPrice)
            ));
        });
        priceAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                priceAnimationRunning = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                priceAnimationRunning = false;
                animateNextFoundPrice();
            }
        });
        priceAnimator.start();
    }

    private void finishSearchWhenAnimationsEnd() {
        if (!completionPending || priceAnimationRunning || !pendingPrices.isEmpty()) {
            return;
        }
        completionPending = false;
        setSearching(false);
        if (!Double.isNaN(completedLowestPrice) && !Double.isInfinite(completedLowestPrice)) {
            setPriceValue(completedLowestPrice);
            statusText.setText(getContext().getString(
                    R.string.lowest_price_found_format,
                    formatCurrency(completedLowestPrice)
            ));
            statusText.setTextColor(getContext().getColor(R.color.action));
        } else {
            statusText.setText(completedStatusMessage);
            statusText.setTextColor(getContext().getColor(R.color.text_secondary));
        }
        statusText.setVisibility(VISIBLE);
    }

    private void setPriceValue(double price) {
        String value = formatEditablePrice(price);
        priceInput.setText(value);
        priceInput.setSelection(value.length());
        statusText.setTextColor(getContext().getColor(R.color.action));
        statusText.setVisibility(VISIBLE);
    }

    private double parseCurrentPrice() {
        try {
            return Double.parseDouble(priceInput.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private String formatCurrency(double price) {
        return NumberFormat.getCurrencyInstance(new Locale("pt", "BR")).format(price);
    }

    private void setSearching(boolean searching) {
        this.searching = searching;
        actionRow.setEnabled(!searching);
        termInput.setEnabled(!searching);
        priceInput.setEnabled(!searching);
        if (searching) {
            actionIcon.setImageResource(R.drawable.ic_sync);
            actionText.setText(R.string.lowest_price_searching_action);
            statusText.setText(R.string.lowest_price_searching);
            statusText.setTextColor(getContext().getColor(R.color.text_secondary));
            statusText.setVisibility(VISIBLE);
            rotation.start();
        } else {
            rotation.cancel();
            actionIcon.setRotation(0f);
            actionIcon.setImageResource(R.drawable.ic_search);
            actionText.setText(R.string.lowest_price_action);
        }
    }

    private String formatEditablePrice(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.2f", value).replace('.', ',');
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
