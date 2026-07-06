package br.com.droidboaoferta;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TelegramMessagePayloadTest {
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
