package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CurrencyTextFormatterTest {
    @Test
    public void formatsWholeReaisWhileTyping() {
        String formatted = CurrencyTextFormatter.formatWholeReais("506000");

        assertTrue(formatted.startsWith("R$"));
        assertTrue(formatted.contains("506.000"));
    }

    @Test
    public void parsesFormattedCurrencyWithoutChangingTheValue() {
        assertEquals(
                506000d,
                CurrencyTextFormatter.parseWholeReais("R$ 506.000"),
                0.001d
        );
        assertNull(CurrencyTextFormatter.parseWholeReais(""));
    }
}
