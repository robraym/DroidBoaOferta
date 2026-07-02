package br.com.droidboaoferta;

import org.junit.Test;

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
