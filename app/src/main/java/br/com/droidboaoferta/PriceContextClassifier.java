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
                || endsWithAny(
                before,
                "parcela",
                "parcelas",
                "parcela de",
                "mensalidade",
                "mensalidade de",
                "entrada",
                "entrada de",
                "sinal",
                "sinal de"
        );
    }

    private static boolean containsCashbackCue(String before, String after) {
        return endsWithAny(
                before,
                "cashback",
                "cashback de",
                "cash back",
                "cash back de",
                "reembolso de",
                "retorno de"
        ) || startsWithAny(
                after,
                "cashback",
                "de cashback",
                "em cashback",
                "de cash back",
                "em cash back",
                "de reembolso"
        );
    }

    private static boolean containsFreightCue(String before, String after) {
        return endsWithAny(
                before,
                "frete",
                "frete de",
                "envio",
                "envio de",
                "entrega",
                "entrega por",
                "taxa de entrega"
        ) || startsWithAny(
                after,
                "de frete",
                "no frete",
                "para entrega",
                "de envio"
        );
    }

    private static boolean containsCreditCue(String before, String after) {
        return endsWithAny(
                before,
                "receba",
                "ganhe",
                "bonus de",
                "bonificacao de",
                "credito de",
                "vale de",
                "vale compra de",
                "vale compras de"
        ) || startsWithAny(
                after,
                "de volta",
                "em creditos",
                "em credito",
                "de credito",
                "em saldo",
                "em pontos",
                "no usado",
                "na troca",
                "pela troca"
        );
    }

    private static boolean containsDiscountCue(String before, String after) {
        boolean explicitAfter = after.matches(
                "^(?:off|de\\s+descont[a-z0-9]*|em\\s+descont[a-z0-9]*|"
                        + "de\\s+econom[a-z0-9]*|a\\s+menos)\\b.*"
        );
        if (explicitAfter) {
            return true;
        }
        if (hasImmediateProductPriceCue(before)) {
            return false;
        }
        return before.matches(
                ".*\\b(?:descont[a-z0-9]*|econom[a-z0-9]*|poup[a-z0-9]*|"
                        + "abat[a-z0-9]*|reduc[a-z0-9]*)"
                        + "(?:\\s+[a-z0-9]+){0,2}\\s+(?:de|ate)$"
        )
                || endsWithAny(
                before,
                "desconto de",
                "economia de",
                "economize",
                "economize ate",
                "poupe",
                "poupe ate",
                "cupom de",
                "compra acima de",
                "compras acima de",
                "compra acima dos",
                "compras acima dos",
                "compra a partir de",
                "compras a partir de",
                "pedido minimo de",
                "valor minimo de",
                "abatimento de"
        );
    }

    private static boolean containsProductPriceCue(String before, String after) {
        return hasImmediateProductPriceCue(before) || startsWithAny(
                after,
                "a vista",
                "avista",
                "no pix",
                "via pix",
                "pelo pix"
        );
    }

    private static boolean hasImmediateProductPriceCue(String before) {
        return endsWithAny(
                before,
                "por",
                "agora",
                "apenas",
                "somente",
                "preco",
                "preco final",
                "valor",
                "valor final",
                "sai por",
                "leve por",
                "a partir de",
                "a vista",
                "no pix",
                "via pix"
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
