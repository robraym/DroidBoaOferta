package br.com.droidboaoferta;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PriceContextClassifierTest {
    @Test
    public void classifiesSavingsAsDiscount() {
        assertMeaning(
                "Faça uma economia de R$ 500 na compra",
                PriceContextClassifier.Meaning.DISCOUNT
        );
        assertMeaning(
                "S25 Ultra com descontão de R$ 1.700",
                PriceContextClassifier.Meaning.DISCOUNT
        );
        assertMeaning(
                "Poupe até R$ 700 nessa oferta",
                PriceContextClassifier.Meaning.DISCOUNT
        );
        assertMeaning(
                "Abatimento especial de R$ 450",
                PriceContextClassifier.Meaning.DISCOUNT
        );
        assertMeaning(
                "Redução de R$ 300 no valor",
                PriceContextClassifier.Meaning.DISCOUNT
        );
    }

    @Test
    public void classifiesOffAsDiscount() {
        assertMeaning(
                "Ganhe R$ 500 OFF no Edge 70 Pro",
                PriceContextClassifier.Meaning.DISCOUNT
        );
    }

    @Test
    public void classifiesCashbackAndCredit() {
        assertMeaning(
                "Receba R$ 300 de cashback",
                PriceContextClassifier.Meaning.CASHBACK
        );
        assertMeaning(
                "Receba R$ 200 de volta",
                PriceContextClassifier.Meaning.CREDIT
        );
        assertMeaning(
                "Bônus de R$ 800 na troca do usado",
                PriceContextClassifier.Meaning.CREDIT
        );
        assertMeaning(
                "Ganhe R$ 250 em saldo",
                PriceContextClassifier.Meaning.CREDIT
        );
    }

    @Test
    public void classifiesFreightAndInstallment() {
        assertMeaning(
                "Frete de R$ 29,90 para todo o Brasil",
                PriceContextClassifier.Meaning.FREIGHT
        );
        assertMeaning(
                "Em 12x de R$ 199,90 sem juros",
                PriceContextClassifier.Meaning.INSTALLMENT
        );
        assertMeaning(
                "Entrada de R$ 999 e o restante parcelado",
                PriceContextClassifier.Meaning.INSTALLMENT
        );
        assertMeaning(
                "Envio de R$ 19,90 para todo o país",
                PriceContextClassifier.Meaning.FREIGHT
        );
    }

    @Test
    public void classifiesFinalPixValueAsProductPrice() {
        assertMeaning(
                "Preço final por R$ 3.499,00 no Pix",
                PriceContextClassifier.Meaning.PRODUCT_PRICE
        );
        assertMeaning(
                "Leve por R$ 3.499,00 hoje",
                PriceContextClassifier.Meaning.PRODUCT_PRICE
        );
        assertMeaning(
                "A partir de R$ 2.999,00",
                PriceContextClassifier.Meaning.PRODUCT_PRICE
        );
        assertMeaning(
                "Com desconto especial, por R$ 3.499,00",
                PriceContextClassifier.Meaning.PRODUCT_PRICE
        );
    }

    private static void assertMeaning(String text, PriceContextClassifier.Meaning expected) {
        int start = text.indexOf("R$");
        int end = start + 2;
        while (end < text.length()) {
            char character = text.charAt(end);
            if (!Character.isDigit(character)
                    && character != ' '
                    && character != '.'
                    && character != ',') {
                break;
            }
            end++;
        }
        assertEquals(expected, PriceContextClassifier.classify(text, start, end));
    }
}
