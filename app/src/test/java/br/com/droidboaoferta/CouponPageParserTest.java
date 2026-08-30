package br.com.droidboaoferta;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CouponPageParserTest {
    @Test
    public void findsLargestPublicCouponWithoutDependingOnSpecificCode() {
        String html = "<section><h2>Cupom de R$ 100 de desconto</h2>"
                + "<p>Use o cupom PRIMEIRO100</p></section>"
                + "<section><h2>Cupom de R$250 de desconto</h2>"
                + "<p>Use o cupom MELHOR250</p></section>";

        CouponPageCoupon highest = CouponPageParser.findHighest(html);

        assertEquals("MELHOR250", highest.getCode());
        assertEquals(250d, highest.getValue(), 0.001d);
    }

    @Test
    public void ignoresPersonalCouponWithoutPublicCode() {
        String html = "<h2>Cupom de primeira compra Motorola</h2>"
                + "<p>Cadastre-se e receba o cupom no seu email.</p>";

        assertNull(CouponPageParser.findHighest(html));
    }

    @Test
    public void acceptsOnlyOfficialMotorolaCouponPage() {
        assertTrue(CouponPageClient.isSupported(
                "https://www.motorola.com.br/cupons-de-desconto-motorola?origem=alertou"));
        assertEquals(CouponPageClient.MOTOROLA_COUPONS_URL,
                CouponPageClient.normalizeSupportedUrl(
                        "https://motorola.com.br/cupons-de-desconto-motorola/"));
        assertNull(CouponPageClient.normalizeSupportedUrl(
                "https://exemplo.com/cupons-de-desconto-motorola"));
    }

    @Test
    public void ordersEveryParsedCouponFromLargestToSmallest() {
        List<CouponPageCoupon> coupons = CouponPageParser.parse(
                "Cupom de R$ 80 de desconto. Use o cupom OITENTA80. "
                        + "Cupom de R$ 1.200,50 de desconto. Use o cupom GRANDE1200."
        );

        assertEquals(2, coupons.size());
        assertEquals(1200.50d, coupons.get(0).getValue(), 0.001d);
    }
}
