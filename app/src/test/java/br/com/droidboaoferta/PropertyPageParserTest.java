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
    public void acceptsLoftCondominiumPages() {
        String expected = "https://loft.com.br/condominio/condominio-edificio-santos-dumont-bela-vista-sao-paulo-sp/O7VA6E7N";

        assertTrue(PropertyPageClient.isSupported(expected + "/?utm_source=alertou"));
        assertEquals(expected, PropertyPageClient.normalizeSupportedUrl(
                "https://www.loft.com.br/condominio/condominio-edificio-santos-dumont-bela-vista-sao-paulo-sp/O7VA6E7N/"));
        assertNull(PropertyPageClient.normalizeSupportedUrl(
                "https://loft.com.br/imovel/apartamento/0ael70og"));
    }

    @Test
    public void normalizesQuintoAndarListingPages() {
        String expected = "https://www.quintoandar.com.br/imovel/114071763/comprar";

        assertEquals(expected, PropertyPageClient.normalizeListingUrl(expected + "/"));
        assertEquals("https://www.quintoandar.com.br/classificado/114071763/comprar",
                PropertyPageClient.normalizeListingUrl(
                        "https://www.quintoandar.com.br/classificado/114071763/comprar"));
        assertNull(PropertyPageClient.normalizeListingUrl(
                "https://www.quintoandar.com.br/condominio/vn-frei-caneca"));
        assertNull(PropertyPageClient.normalizeListingUrl(
                "https://exemplo.com/classificado/114071763/comprar"));
    }

    @Test
    public void buildsClassifiedQuintoAndarListingUrlWhenOriginIsClassified() {
        String nextData = "{\"listings\":{\"saleListings\":["
                + "{\"origin\":\"CLASSIFIED\",\"_id\":\"114059848\",\"_source\":{"
                + "\"area\":40,\"salePrice\":585000,\"listingTags\":[\"CLASSIFIED\"]}},"
                + "{\"origin\":\"TRANSACTIONAL\",\"_id\":\"895258855\",\"_source\":{"
                + "\"area\":42,\"salePrice\":650000,\"listingTags\":[\"NEW_AD\"]}}]}}";
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextData + "</script>";

        List<PropertyPageListing> listings = PropertyPageParser.parse(html).getSaleListings();

        assertEquals("https://www.quintoandar.com.br/classificado/114059848/comprar",
                listings.get(0).getUrl());
        assertEquals("https://www.quintoandar.com.br/imovel/895258855/comprar",
                listings.get(1).getUrl());
    }

    @Test
    public void normalizesLoftListingPages() {
        String expected = "https://loft.com.br/imovel/apartamento-rua-doutor-plinio-barreto/0ael70og";

        assertEquals(expected, PropertyPageClient.normalizeListingUrl(
                "https://www.loft.com.br/imovel/apartamento-rua-doutor-plinio-barreto/0ael70og/"));
        assertNull(PropertyPageClient.normalizeListingUrl(
                "https://loft.com.br/condominio/condominio-edificio-santos-dumont/O7VA6E7N"));
    }

    @Test
    public void parsesLoftCondominiumSaleListingsOnly() {
        String nextData = "{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":["
                + "{\"state\":{\"data\":{"
                + "\"shortId\":\"O7VA6E7N\","
                + "\"name\":\"Condominio Edifício Santos Dumont\","
                + "\"listings\":["
                + "{\"id\":\"0ael70og\",\"status\":\"FOR_SALE\",\"price\":947000,\"area\":100,"
                + "\"propertyType\":\"default\",\"bedrooms\":4},"
                + "{\"id\":\"sold-1\",\"status\":\"SOLD\",\"price\":580000,\"area\":99},"
                + "{\"id\":\"missing-area\",\"status\":\"FOR_SALE\",\"price\":665000,\"area\":0},"
                + "{\"id\":\"missing-price\",\"status\":\"FOR_SALE\",\"area\":82}],"
                + "\"similarListings\":[{\"id\":\"nearby-1\",\"status\":\"FOR_SALE\","
                + "\"price\":220000,\"area\":14}]}}}]}}}}";
        String html = "<script type=\"application/ld+json\">{\"@graph\":["
                + "{\"@type\":\"RealEstateListing\",\"url\":\"https://loft.com.br/imovel/apartamento-100m2/0ael70og\"},"
                + "{\"@type\":\"RealEstateListing\",\"url\":\"https://loft.com.br/imovel/studio-14m2/nearby-1\"}]}</script>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">" + nextData
                + "</script>";

        PropertyPageResult result = PropertyPageParser.parse(html);
        List<PropertyPageListing> listings = result.getSaleListings();

        assertEquals("Edifício Santos Dumont", result.getCondominiumName());
        assertEquals(1, listings.size());
        assertEquals("0ael70og", listings.get(0).getId());
        assertEquals(100d, listings.get(0).getArea(), 0.001d);
        assertEquals(947000d, listings.get(0).getSalePrice(), 0.001d);
        assertEquals("Apartamento com 4 quartos", listings.get(0).getDescription());
        assertEquals("https://loft.com.br/imovel/apartamento-100m2/0ael70og",
                listings.get(0).getUrl());
    }

    @Test
    public void parsesCurrentLoftApiResponseWithoutChangingQuintoAndarParser() {
        String response = "{\"shortId\":\"XN5WUG6T\",\"name\":\"Facto Paulista\","
                + "\"listings\":["
                + "{\"id\":\"eg1rm7gf\",\"status\":\"FOR_SALE\",\"price\":400000,"
                + "\"area\":24,\"type\":\"apartment\",\"bedrooms\":1},"
                + "{\"id\":\"rental-1\",\"status\":\"FOR_SALE\",\"price\":null,"
                + "\"rentalPrice\":3500,\"area\":27,\"transactionType\":\"FOR_RENT\"}]}";

        PropertyPageResult result = PropertyPageParser.parseLoftApiResponse(response);

        assertEquals("Facto Paulista", result.getCondominiumName());
        assertEquals(1, result.getSaleListings().size());
        PropertyPageListing listing = result.getSaleListings().get(0);
        assertEquals("eg1rm7gf", listing.getId());
        assertEquals(24d, listing.getArea(), 0.001d);
        assertEquals(400000d, listing.getSalePrice(), 0.001d);
        assertEquals("Apartamento com 1 quartos", listing.getDescription());
        assertEquals("https://loft.com.br/imovel/eg1rm7gf", listing.getUrl());
    }

    @Test
    public void buildsLoftApiUrlOnlyForLoftCondominiums() {
        assertEquals(
                "https://informational-pages-api.loft.com.br/condominiums/XN5WUG6T",
                PropertyPageClient.buildLoftDataUrl(
                        "https://loft.com.br/condominio/facto-paulista/XN5WUG6T"
                )
        );
        assertNull(PropertyPageClient.buildLoftDataUrl(
                "https://www.quintoandar.com.br/condominio/vn-frei-caneca"
        ));
    }

    @Test
    public void rebuildsListingUrlFromPropertyOfferId() {
        assertEquals("https://www.quintoandar.com.br/imovel/114071763/comprar",
                PropertyPageClient.buildListingUrlFromOfferId("property|123|114071763"));
        assertEquals("", PropertyPageClient.buildListingUrlFromOfferId("1|produto"));
        assertEquals("", PropertyPageClient.buildListingUrlFromOfferId("property|123|"));
    }

    @Test
    public void addsUniqueParameterToBypassProviderCache() {
        assertEquals(
                "https://www.quintoandar.com.br/condominio/go-portugal?alertou_request=123",
                PropertyPageClient.buildFreshRequestUrl(
                        "https://www.quintoandar.com.br/condominio/go-portugal", 123L)
        );
        assertEquals(
                "https://www.quintoandar.com.br/condominio/go-portugal?origem=app&alertou_request=456",
                PropertyPageClient.buildFreshRequestUrl(
                        "https://www.quintoandar.com.br/condominio/go-portugal?origem=app", 456L)
        );
    }

    @Test
    public void parsesCurrentPriceFromIndividualListingPage() throws Exception {
        org.json.JSONObject root = listingPage("895590942", "895590942");
        PropertyListingMetadata metadata = PropertyPageParser.parseListingMetadata(wrap(root), "895590942");

        assertEquals(399000d, metadata.getSalePrice(), 0.001d);
        assertTrue(metadata.isVerifiedFor("895590942"));
        assertTrue(metadata.getFirstPublicationAt() > 0L);
    }

    @Test
    public void unavailablePageNeverUsesRecommendationPrice() throws Exception {
        org.json.JSONObject root = listingPage("118413857", "");
        root.put("page", "/indisponivel/[houseId]/comprar");
        PropertyListingMetadata metadata = PropertyPageParser.parseListingMetadata(wrap(root), "118413857");
        assertTrue(metadata.isUnavailableFor("118413857"));
        assertFalse(metadata.isVerifiedFor("118413857"));
        assertTrue(Double.isNaN(metadata.getSalePrice()));
    }

    @Test
    public void rejectsDifferentMainListingAndRedirectedQuery() throws Exception {
        assertFalse(PropertyPageParser.parseListingMetadata(
                wrap(listingPage("118413857", "895691891")), "118413857")
                .isVerifiedFor("118413857"));
        org.json.JSONObject redirected = listingPage("895691891", "895691891");
        redirected.put("page", "/indisponivel/[houseId]/comprar");
        PropertyListingMetadata metadata = PropertyPageParser.parseListingMetadata(wrap(redirected), "118413857");
        assertFalse(metadata.isUnavailableFor("118413857"));
        assertTrue(Double.isNaN(metadata.getSalePrice()));
    }

    @Test
    public void ignoresRecommendationsWhenMainPriceIsMissing() throws Exception {
        org.json.JSONObject root = listingPage("118413857", "118413857");
        mainInfo(root).put("salePrice", org.json.JSONObject.NULL);
        PropertyListingMetadata metadata = PropertyPageParser.parseListingMetadata(wrap(root), "118413857");
        assertTrue(Double.isNaN(metadata.getSalePrice()));
        assertFalse(metadata.isUnavailableFor("118413857"));
    }

    @Test
    public void recognizesDelistedSaleAndRejectsNonFinitePrice() throws Exception {
        org.json.JSONObject root = listingPage("123", "123");
        mainInfo(root).put("forSale", false);
        assertTrue(PropertyPageParser.parseListingMetadata(wrap(root), "123").isUnavailableFor("123"));
        mainInfo(root).put("forSale", true).put("salePrice", "Infinity");
        assertFalse(PropertyPageParser.parseListingMetadata(wrap(root), "123").isVerifiedFor("123"));
        assertFalse(PropertyPageParser.parseListingMetadata("<html>Error</html>", "123").isVerifiedFor("123"));
    }

    @Test
    public void datesComeOnlyFromSaleOfSameListing() throws Exception {
        org.json.JSONObject root = listingPage("123", "123");
        mainInfo(root).put("listings", new org.json.JSONArray()
                .put(new org.json.JSONObject().put("businessContext", "RENT")
                        .put("firstPublicationDate", "2020-01-01T00:00:00-03:00"))
                .put(new org.json.JSONObject().put("businessContext", "SALE").put("imovelId", "other")
                        .put("firstPublicationDate", "2021-01-01T00:00:00-03:00")));
        assertEquals(0L, PropertyPageParser.parseListingMetadata(wrap(root), "123").getFirstPublicationAt());
    }

    private static org.json.JSONObject listingPage(String queryId, String mainId) throws Exception {
        org.json.JSONObject info = new org.json.JSONObject()
                .put("id", mainId).put("forSale", true).put("area", 27).put("salePrice", 399000)
                .put("listings", new org.json.JSONArray().put(new org.json.JSONObject()
                        .put("imovelId", mainId).put("businessContext", "SALE").put("status", "publicado")
                        .put("firstPublicationDate", "2026-07-21T01:51:27-03:00")
                        .put("lastPublicationDate", "2026-07-21T01:51:27.000+0000")));
        org.json.JSONObject props = new org.json.JSONObject()
                .put("condominiumHouses", new org.json.JSONObject().put("houses", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("id", "895691891").put("salePrice", 320000).put("area", 53))))
                .put("initialState", new org.json.JSONObject().put("house", new org.json.JSONObject().put("houseInfo", info)));
        return new org.json.JSONObject().put("page", "/imovel/[houseId]/comprar/[[...slug]]")
                .put("query", new org.json.JSONObject().put("houseId", queryId))
                .put("props", new org.json.JSONObject().put("pageProps", props));
    }

    private static org.json.JSONObject mainInfo(org.json.JSONObject root) throws Exception {
        return root.getJSONObject("props").getJSONObject("pageProps").getJSONObject("initialState")
                .getJSONObject("house").getJSONObject("houseInfo");
    }

    private static String wrap(org.json.JSONObject root) {
        return "<script id=\"__NEXT_DATA__\">" + root + "</script>";
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
