package br.com.droidboaoferta;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfferLinkValidationStoreTest {
    @Test
    public void onlyActualVisibilityChangesRequestARefresh() {
        OfferLinkValidationStore store = new OfferLinkValidationStore(preferences());
        ObservedOffer offer = offer("product-id");
        assertFalse(store.setValidated(offer, false));
        for (int index = 0; index < 1000; index++) assertFalse(store.setValidated(offer, false));
        assertTrue(store.setValidated(offer, true));
        for (int index = 0; index < 1000; index++) assertFalse(store.setValidated(offer, true));
        assertTrue(store.setValidated(offer, false));
        assertFalse(store.setValidated(offer, false));
    }

    @Test
    public void hidesUncheckedAndFailedCardsAndAllowsSuccessfulRetry() {
        OfferLinkValidationStore store = new OfferLinkValidationStore(preferences());
        ObservedOffer offer = offer("product-id");
        assertFalse(store.canDisplay(offer));
        store.setValidated(offer, true);
        assertTrue(store.canDisplay(offer));
        store.setValidated(offer, false);
        assertFalse(store.canDisplay(offer));
        store.setValidated(offer, true);
        assertTrue(store.canDisplay(offer));
    }

    @Test
    public void leavesPropertyAndCouponValidationWithTheirOwnProviders() {
        OfferLinkValidationStore store = new OfferLinkValidationStore(preferences());
        assertTrue(store.canDisplay(offer("property|1|123")));
        assertTrue(store.canDisplay(offer("coupon|1|123")));
    }

    private ObservedOffer offer(String id) {
        return new ObservedOffer(id, 1, "Z Flip7", "Grupo", 3999, 4000, 1,
                "https://loja.example/flip7", "https://t.me/group/1");
    }

    private SharedPreferences preferences() {
        Map<String, String> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return values.getOrDefault((String) args[0], (String) args[1]);
                    if ("edit".equals(method.getName())) {
                        Map<String, String> pending = new HashMap<>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editor, action, params) -> {
                                    if ("putString".equals(action.getName())) {
                                        pending.put((String) params[0], (String) params[1]);
                                        return editor;
                                    }
                                    if ("apply".equals(action.getName())) { values.putAll(pending); return null; }
                                    throw new UnsupportedOperationException(action.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
