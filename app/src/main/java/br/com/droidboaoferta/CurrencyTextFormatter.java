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

    static String formatWholeReaisWithCents(CharSequence input) {
        String digits = wholeReaisDigitsOnly(input);
        if (digits.isEmpty()) {
            return "";
        }
        return formatterWithCents().format(new BigInteger(digits));
    }

    static String formatTypedCurrency(CharSequence input) {
        if (input == null) {
            return "";
        }
        String text = input.toString();
        int comma = text.lastIndexOf(',');
        String integer = digitsOnly(comma >= 0 ? text.substring(0, comma) : text);
        if (integer.isEmpty()) {
            return "";
        }
        String fraction = comma >= 0 ? digitsOnly(text.substring(comma + 1)) : "";
        if (fraction.length() > 2) {
            fraction = fraction.substring(0, 2);
        }
        String formattedInteger = formatter().format(new BigInteger(integer));
        return comma >= 0 ? formattedInteger + "," + fraction : formattedInteger;
    }

    static String formatWholeReaisWithCents(CharSequence previousText, CharSequence editedText,
                                            boolean deleting) {
        if (deleting && digitsOnly(editedText).isEmpty()) {
            return "";
        }
        if (!deleting) {
            return formatWholeReaisWithCents(editedText);
        }
        String previousDigits = wholeReaisDigitsOnly(previousText);
        if (previousDigits.isEmpty()) {
            return "";
        }
        String editedDigits = wholeReaisDigitsOnly(editedText);
        if (!previousDigits.equals(editedDigits)) {
            return formatWholeReaisWithCents(editedText);
        }
        String reducedDigits = previousDigits.substring(0, previousDigits.length() - 1);
        return reducedDigits.isEmpty() ? "" : formatterWithCents().format(new BigInteger(reducedDigits));
    }

    static String formatWholeReais(double value) {
        if (!(value > 0d)) {
            return "";
        }
        return formatter().format(Math.round(value));
    }

    static String formatWholeReaisWithCents(double value) {
        if (!(value > 0d)) {
            return "";
        }
        return formatterWithCents().format(Math.round(value));
    }

    static Double parseWholeReais(CharSequence input) {
        String numeric = numericOnly(input);
        if (numeric.isEmpty()) {
            return null;
        }
        double value;
        int commaIndex = numeric.lastIndexOf(',');
        if (commaIndex >= 0 && numeric.length() - commaIndex == 3) {
            String normalized = numeric.substring(0, commaIndex).replace(".", "")
                    + "."
                    + numeric.substring(commaIndex + 1);
            try {
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException exception) {
                return null;
            }
        } else {
            String digits = digitsOnly(input);
            if (digits.isEmpty()) {
                return null;
            }
            value = new BigInteger(digits).doubleValue();
        }
        return value > 0d && Double.isFinite(value) ? value : null;
    }

    private static NumberFormat formatter() {
        NumberFormat format = NumberFormat.getCurrencyInstance(BRAZIL);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(0);
        return format;
    }

    private static NumberFormat formatterWithCents() {
        NumberFormat format = NumberFormat.getCurrencyInstance(BRAZIL);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
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

    private static String wholeReaisDigitsOnly(CharSequence input) {
        if (input == null) {
            return "";
        }
        String text = input.toString();
        int commaIndex = text.lastIndexOf(',');
        if (commaIndex >= 0) {
            return digitsOnly(text.substring(0, commaIndex));
        }
        return digitsOnly(text);
    }

    private static String numericOnly(CharSequence input) {
        if (input == null) {
            return "";
        }
        StringBuilder numeric = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if ((character >= '0' && character <= '9') || character == '.' || character == ',') {
                numeric.append(character);
            }
        }
        return numeric.toString();
    }
}
