package br.com.droidboaoferta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void formatsWholeReaisWithCentsWhileTyping() {
        String formatted = CurrencyTextFormatter.formatWholeReaisWithCents("900000");

        assertTrue(formatted.startsWith("R$"));
        assertTrue(formatted.contains("900.000,00"));
    }

    @Test
    public void ignoresDisplayedCentsWhenReformattingWholeReais() {
        String fullyFormatted = CurrencyTextFormatter.formatWholeReaisWithCents("R$ 900.000,00");
        String afterBackspace = CurrencyTextFormatter.formatWholeReaisWithCents("R$ 900.000,0");

        assertTrue(fullyFormatted.contains("900.000,00"));
        assertTrue(afterBackspace.contains("900.000,00"));
        assertFalse(afterBackspace.contains("90.000.000"));
    }

    @Test
    public void backspaceAtEndRemovesOneRealDigitInsteadOfRestoringCents() {
        String formatted = CurrencyTextFormatter.formatWholeReaisWithCents(
                "R$ 900.000,00",
                "R$ 900.000,0",
                true
        );

        assertTrue(formatted.contains("90.000,00"));
        assertFalse(formatted.contains("900.000,00"));
    }

    @Test
    public void deletingTheLastVisibleDigitLeavesTheFieldEmpty() {
        assertEquals(
                "",
                CurrencyTextFormatter.formatWholeReaisWithCents(
                        "R$ 6,00", "", true
                )
        );
    }

    @Test
    public void parsesFormattedCurrencyWithoutChangingTheValue() {
        assertEquals(
                506000d,
                CurrencyTextFormatter.parseWholeReais("R$ 506.000"),
                0.001d
        );
        assertEquals(
                900000d,
                CurrencyTextFormatter.parseWholeReais("R$ 900.000,00"),
                0.001d
        );
        assertNull(CurrencyTextFormatter.parseWholeReais(""));
    }
}
