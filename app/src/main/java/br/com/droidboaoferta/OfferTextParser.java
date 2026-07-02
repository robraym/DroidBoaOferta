package br.com.droidboaoferta;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfferTextParser {
    private static final Pattern PRIORITY_PRICE = Pattern.compile(
            "(?i)(?:por|agora|oferta|à vista|avista|preço|valor)\\s*:?\\s*(?:apenas\\s*)?R\\$\\s*([0-9.]+(?:,[0-9]{2})?)"
    );
    private static final Pattern ANY_PRICE = Pattern.compile(
            "(?i)R\\$\\s*([0-9.]+(?:,[0-9]{2})?)"
    );
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    private OfferTextParser() {
    }

    static double extractPrice(String text) {
        Matcher priorityMatcher = PRIORITY_PRICE.matcher(text);
        if (priorityMatcher.find()) {
            return parseBrazilianPrice(priorityMatcher.group(1));
        }
        Matcher matcher = ANY_PRICE.matcher(text);
        if (matcher.find()) {
            return parseBrazilianPrice(matcher.group(1));
        }
        return Double.NaN;
    }

    static String extractLink(String text) {
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group().replaceAll("[),.;]+$", "");
    }

    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static double parseBrazilianPrice(String value) {
        try {
            return Double.parseDouble(value.replace(".", "").replace(',', '.'));
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }
}
