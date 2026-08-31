package br.com.droidboaoferta;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PropertyPageParserTest {
    @Test
    public void parsesOnlyCondominiumSaleListingsAndKeepsComingSoon() {
        String nextData = "{\"props\":{\"pageProps\":{\"condominium\":{"
                + "\"nameFormatted\":\"VN Frei Caneca\","
                + "\"listings\":{"
                + "\"rentListings\":[{\"_id\":\"rent-1\",\"_source\":{"
                + "\"area\":30,\"salePrice\":300000,\"forSale\":true}}],"
                + "\"saleListings\":["
                + "{\"_id\":\"sale-1\",\"_source\":{\"area\":25,"
                + "\"salePrice\":410000,\"forSale\":false,"
                + "\"shortSaleDescription\":\"Studio mobiliado\","
                + "\"listingTags\":[\"NEW_AD\"]}},"
                + "{\"_id\":\"sale-2\",\"_source\":{\"area\":40,"
                + "\"salePrice\":520000,\"forSale\":true}},"
                + "{\"_id\":\"sale-1\",\"_source\":{\"area\":25,"
                + "\"salePrice\":410000,\"forSale\":true}}]}}},"
                + "\"recommendedListings\":[{\"_id\":\"nearby-1\",\"_source\":{"
                + "\"area\":35,\"salePrice\":390000,\"forSale\":true}}]}}}";
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextData + "</script></html>";

        PropertyPageResult result = PropertyPageParser.parse(html);
        List<PropertyPageListing> listings = result.getSaleListings();

        assertEquals("VN Frei Caneca", result.getCondominiumName());
        assertEquals(2, listings.size());
        assertEquals("sale-1", listings.get(0).getId());
        assertEquals(25d, listings.get(0).getArea(), 0.001d);
        assertEquals(410000d, listings.get(0).getSalePrice(), 0.001d);
        assertEquals("Studio mobiliado", listings.get(0).getDescription());
        assertEquals("https://www.quintoandar.com.br/imovel/sale-1/comprar",
                listings.get(0).getUrl());
        assertTrue(listings.get(0).isNewAd());
        assertFalse(listings.stream().anyMatch(item -> "rent-1".equals(item.getId())));
        assertFalse(listings.stream().anyMatch(item -> "nearby-1".equals(item.getId())));
    }

    @Test
    public void skipsListingsWithoutAreaOrSalePrice() {
        String html = "<script id='__NEXT_DATA__'>{\"listings\":{"
                + "\"saleListings\":["
                + "{\"_id\":\"no-price\",\"_source\":{\"area\":25,"
                + "\"salePrice\":0}},"
                + "{\"_id\":\"no-area\",\"_source\":{"
                + "\"salePrice\":400000,\"forSale\":true}}]}}</script>";

        assertTrue(PropertyPageParser.parse(html).getSaleListings().isEmpty());
    }

    @Test
    public void acceptsOnlyQuintoAndarCondominiumPages() {
        String expected = "https://www.quintoandar.com.br/condominio/vn-frei-caneca";

        assertTrue(PropertyPageClient.isSupported(
                expected + "/?utm_source=alertou"));
        assertEquals(expected, PropertyPageClient.normalizeSupportedUrl(
                "https://quintoandar.com.br/condominio/vn-frei-caneca/?origem=app"));
        assertNull(PropertyPageClient.normalizeSupportedUrl(
                "http://www.quintoandar.com.br/condominio/vn-frei-caneca"));
        assertNull(PropertyPageClient.normalizeSupportedUrl(
                "https://www.quintoandar.com.br/imovel/123/comprar"));
        assertNull(PropertyPageClient.normalizeSupportedUrl(
                "https://exemplo.com/condominio/vn-frei-caneca"));
    }

    @Test
    public void matchesAreaAndMaximumPurchasePriceInclusively() {
        PropertyPageListing listing = new PropertyPageListing(
                "sale-1", 30d, 450000d, "", ""
        );

        assertTrue(listing.matches(30d, 40d, 450000d));
        assertFalse(listing.matches(31d, 40d, 500000d));
        assertFalse(listing.matches(20d, 29d, 500000d));
        assertFalse(listing.matches(20d, 40d, 449999d));
    }

    @Test
    public void removesCondominiumWordFromDisplayedName() {
        PropertyPageResult result = new PropertyPageResult(
                "Condomínio Facto Paulista", java.util.Collections.emptyList());

        assertEquals("Facto Paulista", result.getCondominiumName());
    }
}
