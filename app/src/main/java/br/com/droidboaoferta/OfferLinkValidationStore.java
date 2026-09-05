package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;

final class OfferLinkValidationStore {
    private final SharedPreferences preferences;

    OfferLinkValidationStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences("offer_link_validation", Context.MODE_PRIVATE));
    }

    OfferLinkValidationStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    static boolean requiresValidation(ObservedOffer offer) {
        return offer != null && !offer.getId().startsWith("property|")
                && !offer.getId().startsWith("coupon|")
                && !offer.getId().startsWith("vivo|");
    }

    boolean canDisplay(ObservedOffer offer) {
        return !requiresValidation(offer) || "valid".equals(preferences.getString(offer.getId(), ""));
    }

    boolean setValidated(ObservedOffer offer, boolean valid) {
        String previous = preferences.getString(offer.getId(), "");
        String next = valid ? "valid" : "pending";
        if (previous.equals(next)) return false;
        preferences.edit().putString(offer.getId(), next).apply();
        return "valid".equals(previous) != valid;
    }
}
