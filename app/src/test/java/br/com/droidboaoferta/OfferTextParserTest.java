package br.com.droidboaoferta;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OfferTextParserTest {
    @Test
    public void prioritizesPromotionalPriceAfterPor() {
        double price = OfferTextParser.extractPrice("De R$ 2.999,00 por R$ 1.899,90");
        assertEquals(1899.90, price, 0.001);
    }

    @Test
    public void readsSingleBrazilianPrice() {
        double price = OfferTextParser.extractPrice("Oferta do dia: R$ 99,90");
        assertEquals(99.90, price, 0.001);
    }

    @Test
    public void prefersPixPriceOverInstallmentValue() {
        double price = OfferTextParser.extractPrice(
                "Galaxy A05s em 10x de R$ 89,90 ou R$ 849,00 no Pix"
        );
        assertEquals(849.00, price, 0.001);
    }

    @Test
    public void convertsInstallmentsToTotalWhenNoCashPriceExists() {
        double price = OfferTextParser.extractPrice("Galaxy A05s em 10x de R$ 89,90 sem juros");
        assertEquals(899.00, price, 0.001);
    }

    @Test
    public void ignoresCouponDiscountAsProductPrice() {
        double price = OfferTextParser.extractPrice("Galaxy A05s com cupom de R$ 9 de desconto");
        assertTrue(Double.isNaN(price));
    }

    @Test
    public void usesProductPriceInsteadOfCouponDiscount() {
        double price = OfferTextParser.extractPrice(
                "Cupom de R$ 9 de desconto. Galaxy A05s por R$ 799,00"
        );
        assertEquals(799.00, price, 0.001);
    }

    @Test
    public void selectsPriceNearestToRequestedProductInMultiOfferPost() {
        String text = "Motorola Edge 60 por R$ 2.499,00\n\n"
                + "Galaxy Z Flip 7 por R$ 5.999,00";

        assertEquals(
                5999.00,
                OfferTextParser.extractPriceForInterest(text, "Galaxy Z Flip 7"),
                0.001
        );
    }

    @Test
    public void keepsFirstProductPriceWhenItIsTheRequestedProduct() {
        String text = "Motorola Edge 60 por R$ 2.499,00\n\n"
                + "Galaxy Z Flip 7 por R$ 5.999,00";

        assertEquals(
                2499.00,
                OfferTextParser.extractPriceForInterest(text, "Motorola Edge 60"),
                0.001
        );
    }

    @Test
    public void rejectsExtremeLowOutlierFromPriceSuggestion() {
        double price = OfferTextParser.selectPlausibleLowest(Arrays.asList(9.0, 799.0, 849.0));
        assertEquals(799.00, price, 0.001);
    }

    @Test
    public void keepsLegitimateLowPricesInSameRange() {
        double price = OfferTextParser.selectPlausibleLowest(Arrays.asList(8.0, 9.0, 12.0));
        assertEquals(8.00, price, 0.001);
    }

    @Test
    public void rejectsAccessoryForMainProductInterest() {
        assertTrue(!OfferTextParser.matchesInterest(
                "Pulseira de titânio para Galaxy Watch Ultra por R$ 149,00",
                "Galaxy Watch Ultra"
        ));
    }

    @Test
    public void rejectsAccessoryNamedImmediatelyBeforeProduct() {
        assertTrue(!OfferTextParser.matchesInterest(
                "Capa Galaxy Watch Ultra com proteção reforçada por R$ 59,00",
                "Galaxy Watch Ultra"
        ));
    }

    @Test
    public void acceptsMainProductWithIncludedAccessory() {
        assertTrue(OfferTextParser.matchesInterest(
                "Galaxy Watch Ultra LTE com pulseira extra por R$ 2.999,00",
                "Galaxy Watch Ultra"
        ));
    }

    @Test
    public void acceptsAccessoryWhenAccessoryIsTheInterest() {
        assertTrue(OfferTextParser.matchesInterest(
                "Pulseira para Galaxy Watch Ultra por R$ 149,00",
                "Pulseira para Galaxy Watch Ultra"
        ));
    }

    @Test
    public void validatedBatchRejectsPriceBelowPlausibleFloor() {
        assertTrue(!OfferTextParser.isWithinValidatedRange(149.0, 934.15, 934.15));
    }

    @Test
    public void validatedBatchPublishesAcceptedReferencePrice() {
        assertTrue(OfferTextParser.isWithinValidatedRange(934.15, 934.15, 934.15));
    }

    @Test
    public void returnsNotANumberWithoutPrice() {
        assertTrue(Double.isNaN(OfferTextParser.extractPrice("sem preço informado")));
    }

    @Test
    public void extractsCleanPurchaseLink() {
        assertEquals(
                "https://amazon.com.br/produto",
                OfferTextParser.extractLink("Confira: https://amazon.com.br/produto).")
        );
    }

    @Test
    public void normalizesAccentsForInterestMatching() {
        assertEquals("cafe eletrico", OfferTextParser.normalize("Café Elétrico"));
    }
}
