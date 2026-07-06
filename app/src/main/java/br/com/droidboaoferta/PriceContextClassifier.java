package br.com.droidboaoferta;

final class PriceContextClassifier {
    enum Meaning {
        PRODUCT_PRICE,
        DISCOUNT,
        CASHBACK,
        INSTALLMENT,
        FREIGHT,
        CREDIT,
        UNKNOWN
    }

    private PriceContextClassifier() {
    }

    static Meaning classify(String text, int priceStart, int priceEnd) {
        if (text == null || priceStart < 0 || priceEnd < priceStart) {
            return Meaning.UNKNOWN;
        }
        String before = OfferTextParser.normalize(text.substring(
                Math.max(0, priceStart - 64),
                Math.min(priceStart, text.length())
        ));
        String after = OfferTextParser.normalize(text.substring(
                Math.min(priceEnd, text.length()),
                Math.min(text.length(), priceEnd + 64)
        ));
        String closeBefore = lastWords(before, 7);
        String closeAfter = firstWords(after, 7);

        if (isInstallment(closeBefore)) {
            return Meaning.INSTALLMENT;
        }
        if (containsCashbackCue(closeBefore, closeAfter)) {
            return Meaning.CASHBACK;
        }
        if (containsFreightCue(closeBefore, closeAfter)) {
            return Meaning.FREIGHT;
        }
        if (containsDiscountCue(closeBefore, closeAfter)) {
            return Meaning.DISCOUNT;
        }
        if (containsCreditCue(closeBefore, closeAfter)) {
            return Meaning.CREDIT;
        }
        if (containsProductPriceCue(closeBefore, closeAfter)) {
            return Meaning.PRODUCT_PRICE;
        }
        return Meaning.UNKNOWN;
    }

    private static boolean isInstallment(String before) {
        return before.matches(".*\\b[0-9]{1,2}\\s*x(?:\\s+de|\\s+por)?$")
                || before.endsWith("parcela")
                || before.endsWith("parcelas");
    }

    private static boolean containsCashbackCue(String before, String after) {
        return endsWithAny(before, "cashback", "cashback de")
                || startsWithAny(after, "cashback", "de cashback", "em cashback");
    }

    private static boolean containsFreightCue(String before, String after) {
        return endsWithAny(before, "frete", "frete de", "entrega", "entrega por")
                || startsWithAny(after, "de frete", "no frete", "para entrega");
    }

    private static boolean containsCreditCue(String before, String after) {
        return endsWithAny(before, "receba", "ganhe", "credito de", "vale de")
                || startsWithAny(after, "de volta", "em creditos", "em credito", "de credito");
    }

    private static boolean containsDiscountCue(String before, String after) {
        return endsWithAny(
                before,
                "desconto de",
                "economia de",
                "economize",
                "economize ate",
                "poupe",
                "poupe ate",
                "cupom de",
                "abatimento de"
        ) || startsWithAny(
                after,
                "off",
                "de desconto",
                "em desconto",
                "de economia",
                "a menos"
        );
    }

    private static boolean containsProductPriceCue(String before, String after) {
        return endsWithAny(
                before,
                "por",
                "agora",
                "preco",
                "preco final",
                "valor",
                "valor final",
                "sai por",
                "a vista",
                "no pix",
                "via pix"
        ) || startsWithAny(
                after,
                "a vista",
                "avista",
                "no pix",
                "via pix",
                "pelo pix"
        );
    }

    private static boolean endsWithAny(String value, String... possibilities) {
        for (String possibility : possibilities) {
            if (value.equals(possibility) || value.endsWith(" " + possibility)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String value, String... possibilities) {
        for (String possibility : possibilities) {
            if (value.equals(possibility) || value.startsWith(possibility + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String lastWords(String value, int maximumWords) {
        String[] words = value.isEmpty() ? new String[0] : value.split(" ");
        StringBuilder result = new StringBuilder();
        for (int index = Math.max(0, words.length - maximumWords); index < words.length; index++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(words[index]);
        }
        return result.toString();
    }

    private static String firstWords(String value, int maximumWords) {
        String[] words = value.isEmpty() ? new String[0] : value.split(" ");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < Math.min(words.length, maximumWords); index++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(words[index]);
        }
        return result.toString();
    }
}
