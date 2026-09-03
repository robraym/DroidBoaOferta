package br.com.droidboaoferta;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TelegramMessagePayloadTest {
    @Test
    public void duplicatedBaseNameInFeTitleDoesNotMakeItsLinkValidForStandardModel() {
        String text = "Celular Samsung Galaxy Z Flip7 FE 128GB - Galaxy Z Flip 7\nR$ 2599\n"
                + "https://ofertalink.com.br/Magalu/EbrQDP5";
        TelegramMessagePayload payload = TelegramMessagePayload.fromCandidates(text,
                new String[]{"https://ofertalink.com.br/Magalu/EbrQDP5"},
                new int[]{text.indexOf("https://")}, new String[]{""});
        assertEquals("", payload.findBestLink("Z Flip7"));
        assertEquals("https://ofertalink.com.br/Magalu/EbrQDP5", payload.findBestLink("Z Flip7 FE"));
    }

    @Test
    public void selectsLinkOfExactFlipEdition() {
        String text = "Z Flip7 FE R$ 2599\nhttps://loja.example/fe\n"
                + "Z Flip7 R$ 3999\nhttps://loja.example/base";
        TelegramMessagePayload payload = TelegramMessagePayload.fromCandidates(text,
                new String[]{"https://loja.example/fe", "https://loja.example/base"},
                new int[]{text.indexOf("https://loja.example/fe"), text.indexOf("https://loja.example/base")},
                new String[]{"", ""});
        assertEquals("https://loja.example/base", payload.findBestLink("Z Flip 7"));
        assertEquals("https://loja.example/fe", payload.findBestLink("Z Flip7 FE"));
    }

    @Test
    public void doesNotUseFeLinkWhenStandardFlipHasNoLink() {
        String text = "Z Flip7 FE R$ 2599\nhttps://loja.example/fe\nZ Flip7 R$ 3999";
        TelegramMessagePayload payload = TelegramMessagePayload.fromCandidates(text,
                new String[]{"https://loja.example/fe"}, new int[]{text.indexOf("https://")}, new String[]{""});
        assertEquals("", payload.findBestLink("Z Flip7"));
    }

    @Test
    public void selectsLinkNearestToRequestedProductInsteadOfFirstLink() {
        String text = "Motorola Edge 60 por R$ 2.499\n"
                + "https://loja.example/edge60\n\n"
                + "Galaxy Z Flip 7 por R$ 5.999\n"
                + "https://loja.example/zflip7";
        TelegramMessagePayload payload = TelegramMessagePayload.fromCandidates(
                text,
                new String[]{"https://loja.example/edge60", "https://loja.example/zflip7"},
                new int[]{text.indexOf("https://loja.example/edge60"),
                        text.indexOf("https://loja.example/zflip7")},
                new String[]{"", ""}
        );

        assertEquals(
                "https://loja.example/zflip7",
                payload.findBestLink("Galaxy Z Flip 7")
        );
    }

    @Test
    public void selectsButtonWhoseLabelMatchesRequestedProduct() {
        TelegramMessagePayload payload = TelegramMessagePayload.fromCandidates(
                "Motorola Edge 60 e Galaxy Z Flip 7 em promoção",
                new String[]{"https://loja.example/edge60", "https://loja.example/zflip7"},
                new int[]{20, 35},
                new String[]{"Comprar Edge 60", "Comprar Galaxy Z Flip 7"}
        );

        assertEquals(
                "https://loja.example/zflip7",
                payload.findBestLink("Galaxy Z Flip 7")
        );
    }
}
