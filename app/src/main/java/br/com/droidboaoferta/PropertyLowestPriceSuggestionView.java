package br.com.droidboaoferta;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PropertyLowestPriceSuggestionView extends LinearLayout {
    private final LinearLayout actionRow;
    private final ImageView actionIcon;
    private final TextView actionText;
    private final TextView statusText;
    private final ObjectAnimator rotation;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private EditText minimumAreaInput;
    private EditText maximumAreaInput;
    private EditText priceInput;
    private boolean searching;

    PropertyLowestPriceSuggestionView(Context context) {
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
        actionIcon.setContentDescription(context.getString(R.string.property_lowest_price_action));
        actionRow.addView(actionIcon, new LayoutParams(dp(21), dp(21)));

        actionText = new TextView(context);
        actionText.setText(R.string.property_lowest_price_action);
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

    void bind(EditText urlInput, EditText minimumAreaInput, EditText maximumAreaInput,
              EditText priceInput) {
        this.urlInput = urlInput;
        this.minimumAreaInput = minimumAreaInput;
        this.maximumAreaInput = maximumAreaInput;
        this.priceInput = priceInput;
        this.priceInput.setInputType(InputType.TYPE_CLASS_NUMBER);
    }

    private void search() {
        if (searching || urlInput == null || minimumAreaInput == null
                || maximumAreaInput == null || priceInput == null) {
            return;
        }
        String normalizedUrl = PropertyPageClient.normalizeSupportedUrl(
                urlInput.getText().toString().trim());
        if (normalizedUrl == null) {
            urlInput.setError(getContext().getString(R.string.property_url_unsupported));
            return;
        }
        Double minimumArea = parsePositiveNumber(minimumAreaInput);
        Double maximumArea = parsePositiveNumber(maximumAreaInput);
        if (minimumArea == null) {
            minimumAreaInput.setError(getContext().getString(R.string.property_area_required));
            return;
        }
        if (maximumArea == null) {
            maximumAreaInput.setError(getContext().getString(R.string.property_area_required));
            return;
        }
        if (maximumArea < minimumArea) {
            maximumAreaInput.setError(getContext().getString(R.string.property_area_range_invalid));
            return;
        }

        setSearching(true);
        executor.execute(() -> {
            PropertyPageListing lowest = null;
            try {
                PropertyPageResult page = PropertyPageClient.fetch(normalizedUrl);
                for (PropertyPageListing listing : page.getSaleListings()) {
                    if (listing.getArea() >= minimumArea && listing.getArea() <= maximumArea
                            && (lowest == null
                            || listing.getSalePrice() < lowest.getSalePrice())) {
                        lowest = listing;
                    }
                }
                PropertyPageListing bestListing = lowest;
                mainHandler.post(() -> showResult(bestListing, 0));
            } catch (Exception ignored) {
                mainHandler.post(() -> showResult(null, R.string.property_lowest_price_load_failed));
            }
        });
    }

    private void showResult(PropertyPageListing listing, int errorMessageResource) {
        setSearching(false);
        if (listing == null) {
            statusText.setText(errorMessageResource == 0
                    ? R.string.property_lowest_price_not_found
                    : errorMessageResource);
            statusText.setTextColor(getContext().getColor(R.color.text_secondary));
            statusText.setVisibility(VISIBLE);
            return;
        }
        String price = CurrencyTextFormatter.formatWholeReais(listing.getSalePrice());
        priceInput.setText(price);
        priceInput.setSelection(price.length());
        statusText.setText(getContext().getString(
                R.string.property_lowest_price_found_format,
                formatCurrency(listing.getSalePrice()),
                formatArea(listing.getArea())
        ));
        statusText.setTextColor(getContext().getColor(R.color.action));
        statusText.setVisibility(VISIBLE);
    }

    private Double parsePositiveNumber(EditText input) {
        try {
            String numericText = input.getText().toString()
                    .replaceAll("[^0-9,.]", "")
                    .trim();
            double value = Double.parseDouble(numericText.replace(',', '.'));
            return value > 0d ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatArea(double area) {
        NumberFormat areaFormat = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        areaFormat.setMaximumFractionDigits(1);
        return areaFormat.format(area);
    }

    private String formatCurrency(double price) {
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        currency.setMaximumFractionDigits(0);
        return currency.format(price);
    }

    private void setSearching(boolean searching) {
        this.searching = searching;
        actionRow.setEnabled(!searching);
        urlInput.setEnabled(!searching);
        minimumAreaInput.setEnabled(!searching);
        maximumAreaInput.setEnabled(!searching);
        priceInput.setEnabled(!searching);
        if (searching) {
            actionIcon.setImageResource(R.drawable.ic_sync);
            actionText.setText(R.string.property_lowest_price_searching_action);
            statusText.setText(R.string.property_lowest_price_searching);
            statusText.setTextColor(getContext().getColor(R.color.text_secondary));
            statusText.setVisibility(VISIBLE);
            rotation.start();
        } else {
            rotation.cancel();
            actionIcon.setRotation(0f);
            actionIcon.setImageResource(R.drawable.ic_search);
            actionText.setText(R.string.property_lowest_price_action);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
