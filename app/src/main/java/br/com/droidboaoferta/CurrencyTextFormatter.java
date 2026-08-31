package br.com.droidboaoferta;

import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.Locale;

final class CurrencyTextFormatter {
    private static final Locale BRAZIL = new Locale("pt", "BR");

    private CurrencyTextFormatter() {
    }

    static String formatWholeReais(CharSequence input) {
        String digits = digitsOnly(input);
        if (digits.isEmpty()) {
            return "";
        }
        return formatter().format(new BigInteger(digits));
    }

    static String formatWholeReais(double value) {
        if (!(value > 0d)) {
            return "";
        }
        return formatter().format(Math.round(value));
    }

    static Double parseWholeReais(CharSequence input) {
        String digits = digitsOnly(input);
        if (digits.isEmpty()) {
            return null;
        }
        double value = new BigInteger(digits).doubleValue();
        return value > 0d && Double.isFinite(value) ? value : null;
    }

    private static NumberFormat formatter() {
        NumberFormat format = NumberFormat.getCurrencyInstance(BRAZIL);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(0);
        return format;
    }

    private static String digitsOnly(CharSequence input) {
        if (input == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        return digits.toString();
    }
}
