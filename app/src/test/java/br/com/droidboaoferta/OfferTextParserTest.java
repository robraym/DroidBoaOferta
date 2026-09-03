package br.com.droidboaoferta;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OfferTextParserTest {
    @Test
    public void rejectsFeTitleEvenWhenItEndsWithShortenedBaseModelName() {
        String text = "🔵 Loja Magalu\n\n"
                + "✅ Celular Samsung Galaxy Z Flip7\nFE 128GB Preto 5G 8GB RAM Tela\n"
                + "6,7\" Câm. Dupla de 50MP Galaxy AI -\nGalaxy Z Flip 7\n\n"
                + "💰 R$ 2.599,00 com cupom TEL400\n"
                + "💳 Em até 10x R$ 333,22 sem juros\n\n"
                + "🔗 Link do produto:\nhttps://ofertalink.com.br/Magalu/EbrQDP5\n\n"
                + "⚠️ Preço do produto pode Mudar!!";
        org.junit.Assert.assertFalse(OfferTextParser.matchesInterest(text, "Z Flip7"));
        assertTrue(OfferTextParser.hasDifferentFlipEdition(text, "Z Flip7"));
        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(text, "Z Flip7")));
        assertTrue(OfferTextParser.matchesInterest(text, "Z Flip7 FE"));
        assertEquals(2599d, OfferTextParser.extractPriceForInterest(text, "Z Flip7 FE"), 0.001d);
    }

    @Test
    public void shortenedFeTitleDoesNotStealPriceOfSeparateStandardOffer() {
        String text = "Z Flip7 FE 128GB - Galaxy Z Flip 7 R$ 2599\n"
                + "https://loja.example/fe\nZ Flip7 256GB R$ 3999\nhttps://loja.example/base";
        assertTrue(OfferTextParser.matchesInterest(text, "Z Flip7"));
        assertEquals(3999d, OfferTextParser.extractPriceForInterest(text, "Z Flip7"), 0.001d);
        assertEquals(2599d, OfferTextParser.extractPriceForInterest(text, "Z Flip7 FE"), 0.001d);
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition(text, "Z Flip7"));
    }

    @Test
    public void handlesShortenedModelBeforeFeAndPreservesStandardOffer() {
        assertTrue(OfferTextParser.hasDifferentFlipEdition(
                "Galaxy Z Flip 7 - Samsung Z Flip7 FE 128GB R$ 2599", "Z Flip7"));
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition(
                "Samsung Z Flip7 256GB - Galaxy Z Flip 7 R$ 3999", "Z Flip7"));
        assertEquals(3999d, OfferTextParser.extractPriceForInterest(
                "Samsung Z Flip7 256GB - Galaxy Z Flip 7 R$ 3999", "Z Flip7"), 0.001d);
    }

    @Test
    public void rejectsFlip7FeForStandardFlip7Alert() {
        String text = "Celular Samsung Galaxy Z Flip7 FE 128GB Preto 5G\n"
                + "R$ 2599,00\nMagalu\nhttps://pelando.promo/apV7T";
        org.junit.Assert.assertFalse(OfferTextParser.matchesInterest(text, "Z Flip7"));
        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(text, "Z Flip7")));
        assertTrue(OfferTextParser.matchesInterest(text, "Z Flip7 FE"));
        assertEquals(2599d, OfferTextParser.extractPriceForInterest(text, "Z Flip7 FE"), 0.001d);
    }

    @Test
    public void distinguishesFlipEditionsRegardlessOfSpacesAndCase() {
        for (String base : Arrays.asList("Z Flip7", "Z Flip 7", "ZFlip7")) {
            assertTrue(OfferTextParser.matchesInterest("Samsung Galaxy Z Flip 7 256GB R$ 3999", base));
            org.junit.Assert.assertFalse(OfferTextParser.matchesInterest("Samsung ZFlip7 FE R$ 2599", base));
            org.junit.Assert.assertFalse(OfferTextParser.matchesInterest("Samsung Z Flip 7 Fan Edition R$ 2599", base));
            org.junit.Assert.assertFalse(OfferTextParser.matchesInterest("Samsung Z Flip7 R$ 3999", base + " FE"));
        }
        assertTrue(OfferTextParser.matchesInterest("Samsung Z Flip7 Fan Edition R$ 2599", "Z Flip7 FE"));
    }

    @Test
    public void assignsSeparatePricesWhenBothFlipEditionsAreInOneMessage() {
        String text = "Samsung Z Flip7 FE R$ 2599\nhttps://loja.example/fe\n"
                + "Samsung Z Flip7 R$ 3999\nhttps://loja.example/base";
        assertTrue(OfferTextParser.matchesInterest(text, "Z Flip7"));
        assertEquals(3999d, OfferTextParser.extractPriceForInterest(text, "Z Flip7"), 0.001d);
        assertEquals(2599d, OfferTextParser.extractPriceForInterest(text, "Z Flip7 FE"), 0.001d);
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition(text, "Z Flip7"));
    }

    @Test
    public void flipEditionRepairDoesNotRejectOtherGenerationsOrUnrelatedProducts() {
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition("Z Flip6 FE", "Z Flip7"));
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition("Galaxy S25 FE", "Galaxy S25"));
        org.junit.Assert.assertFalse(OfferTextParser.hasDifferentFlipEdition("Texto indisponível", "Z Flip7"));
        org.junit.Assert.assertFalse(OfferTextParser.matchesInterest("Z Flip7 FE", "Z Flip70"));
    }
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
    public void readsFourDigitPriceWithoutThousandsSeparator() {
        double price = OfferTextParser.extractPrice(
                "Apple iPhone SE (3ª geração) 64 GB por R$ 2199,00"
        );
        assertEquals(2199.00, price, 0.001);
    }

    @Test
    public void readsFourDigitPriceWithBrazilianThousandsSeparator() {
        double price = OfferTextParser.extractPrice(
                "Apple iPhone SE (3ª geração) 64 GB por R$ 2.199,00"
        );
        assertEquals(2199.00, price, 0.001);
    }

    @Test
    public void neverAcceptsPartialPrefixOfFourDigitPrice() {
        double price = OfferTextParser.extractPriceForInterest(
                "Apple iPhone SE (3ª geração) 64 GB - Meia noite R$ 2199,00",
                "Iphone SE"
        );
        assertEquals(2199.00, price, 0.001);
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
    public void ignoresRepeatedSavingsAmountFromEdge70CouponPost() {
        String text = "R$ 500 OFF no Smartphone Motorola Edge 70 Pro 5G 256GB\n"
                + "Vem de cupom Motorola pra fazer economia de R$ 500 na compra "
                + "do seu novo celular Edge 70 Pro.\n"
                + "Cupom EDGE500";

        assertTrue(Double.isNaN(OfferTextParser.extractPrice(text)));
        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(
                text,
                "Motorola Edge 70 Pro"
        )));
    }

    @Test
    public void stillUsesRealProductPriceAfterSavingsAmount() {
        String text = "Economia de R$ 500 no Motorola Edge 70 Pro. "
                + "Preço final por R$ 3.499,00 no Pix.";

        assertEquals(
                3499.00,
                OfferTextParser.extractPriceForInterest(text, "Motorola Edge 70 Pro"),
                0.001
        );
    }

    @Test
    public void ignoresDescontaoAndUsesRealS25UltraPrice() {
        String text = "S25 Ultra COM DESCONTÃO DE R$ 1.700\n"
                + "Samsung Galaxy S25 Ultra 5G 256GB Galaxy AI Titânio Azul\n"
                + "Por: R$ 5.399,00\n"
                + "Cupom: TELL1700";

        assertEquals(5399.00, OfferTextParser.extractPrice(text), 0.001);
        assertEquals(
                5399.00,
                OfferTextParser.extractPriceForInterest(text, "S25 Ultra"),
                0.001
        );
    }

    @Test
    public void ignoresMinimumPurchaseCouponValueAndUsesOfferValue() {
        String text = "Samsung Galaxy A07\n\n"
                + "Resgate o cupom TODAS AS LOJAS R$25 OFF Nas compras acima de R$219 "
                + "clicando aqui. Após, adicione o aparelho no carrinho e marque para aplicar "
                + "o cupom resgatado na hora do pagamento.\n\n"
                + "VALOR DA OFERTA R$ 552 (Pix), R$ 604 (12x) - ANTES R$ 663";

        assertEquals(552.00, OfferTextParser.extractPrice(text), 0.001);
        assertEquals(
                552.00,
                OfferTextParser.extractPriceForInterest(text, "Galaxy A07"),
                0.001
        );
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
    public void ignoresThePriceOfAnotherItemInBundlePromotion() {
        String text = "Galaxy A57 5G (128GB) - Cinza + Galaxy Watch8 BT 44mm - Grafite\n\n"
                + "O Watch8 sozinho custa R$1200\n\n"
                + "R$2.216,28";

        assertEquals(
                2216.28,
                OfferTextParser.extractPriceForInterest(text, "Galaxy A57"),
                0.001
        );
    }

    @Test
    public void keepsPricesInsideTheirLinkedOfferBlocks() {
        String text = "Produto anterior em promoção\n"
                + "R$ 186\n"
                + "LINK https://loja.example/anterior\n\n"
                + "Produto procurado com recursos avançados\n"
                + "De R$ 899 por DESTAQUE R$ 469\n"
                + "LINK https://loja.example/procurado";

        assertEquals(
                469.00,
                OfferTextParser.extractPriceForInterest(text, "Produto procurado"),
                0.001
        );
    }

    @Test
    public void neverBorrowsPriceAcrossLinkedOfferBlocks() {
        String text = "Produto anterior por R$ 186\n"
                + "LINK https://loja.example/anterior\n\n"
                + "Produto procurado sem preço informado";

        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(
                text,
                "Produto procurado"
        )));
    }

    @Test
    public void rejectsModelMentionedOnlyInEditorialHeadlineBeforeAnotherProduct() {
        String text = "Câmera melhor que iPhone 17 Pro Max e S26 Ultra segundo DXOmark\n\n"
                + "➡️ Smartphone HUAWEI Pura 80 Pro 12GB+512GB\n"
                + "Câmera Ultra-iluminação de 1 Polegada Câmera Teleobjetiva Macro "
                + "Ultra-iluminação Cancelamento de Ruído por IA 5.17 Ah "
                + "Dual SuperCharge Celular Vermelho\n\n"
                + "✅ R$ 3.699 10X SEM JUROS\n"
                + "Cupom: OFERTA8D08";

        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(text, "S26 Ultra")));
        assertTrue(Double.isNaN(OfferTextParser.extractPriceForInterest(text, "iPhone 17")));
    }

    @Test
    public void acceptsRepeatedInterestInsideNewProductSection() {
        String text = "S26 Ultra ganha destaque em teste de câmera\n\n"
                + "➡️ Smartphone Samsung Galaxy S26 Ultra 512GB\n"
                + "Por R$ 6.999,00";

        assertEquals(
                6999.00,
                OfferTextParser.extractPriceForInterest(text, "S26 Ultra"),
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
    public void rejectsPrivacyFilmForIphoneInterest() {
        String text = "Kit de Películas 9D Cerâmica Privacidade Fosca Anti Spy "
                + "para iPhone 17, 16, 15, 14, 13 Pro Max, 12, 11, XR\n"
                + "R$11,00 -> R$ 10,45\n"
                + "VER AGORA";

        assertTrue(!OfferTextParser.matchesInterest(text, "iPhone 17"));
    }

    @Test
    public void rejectsImplausibleMainDevicePrice() {
        assertTrue(!OfferTextParser.isPlausiblePriceForInterest(11.00, "iPhone 17"));
        assertTrue(!OfferTextParser.isPlausiblePriceForInterest(149.00, "Galaxy Watch Ultra"));
    }

    @Test
    public void acceptsImplausiblePhonePriceWhenInterestIsAccessory() {
        assertTrue(OfferTextParser.isPlausiblePriceForInterest(11.00, "Película iPhone 17"));
    }

    @Test
    public void acceptsMainProductWithIncludedAccessory() {
        assertTrue(OfferTextParser.matchesInterest(
                "Galaxy Watch Ultra LTE com pulseira extra por R$ 2.999,00",
                "Galaxy Watch Ultra"
        ));
    }

    @Test
    public void doesNotMatchBaseModelInsideNumberedSuccessor() {
        String text = "HUAWEI FreeClip 2 por R$ 999,00";

        assertTrue(!OfferTextParser.matchesInterest(text, "FreeClip"));
        assertTrue(OfferTextParser.matchesInterest(text, "FreeClip 2"));
    }

    @Test
    public void stillMatchesBaseModelWhenItIsMentionedSeparately() {
        String text = "FreeClip por R$ 799,00. Também disponível o FreeClip 2.";

        assertTrue(OfferTextParser.matchesInterest(text, "FreeClip"));
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
