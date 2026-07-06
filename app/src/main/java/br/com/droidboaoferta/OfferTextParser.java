package br.com.droidboaoferta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfferTextParser {
    private static final String[] ACCESSORY_TERMS = {
            "pulseira", "bracelete", "correia", "capa", "case", "cover",
            "pelicula", "vidro", "protetor", "carregador", "cabo", "adaptador",
            "suporte", "base", "dock", "bumper", "acessorio"
    };
    private static final String PRICE_VALUE =
            "([0-9]{1,3}(?:\\.[0-9]{3})+(?:,[0-9]{1,2})?|[0-9]+(?:,[0-9]{1,2})?)"
                    + "(?![0-9.,])";
    private static final Pattern PRIORITY_PRICE = Pattern.compile(
            "(?i)(?:por|agora|oferta|à vista|avista|preço|valor|no pix|via pix)"
                    + "\\s*:?\\s*(?:apenas\\s*)?R\\$\\s*" + PRICE_VALUE
    );
    private static final Pattern AT_SIGHT_PRICE = Pattern.compile(
            "(?i)R\\$\\s*" + PRICE_VALUE
                    + "\\s*(?:à vista|avista|no pix|via pix|pelo pix)"
    );
    private static final Pattern INSTALLMENT_PRICE = Pattern.compile(
            "(?i)([0-9]{1,2})\\s*x\\s*(?:de|por)?\\s*R\\$\\s*" + PRICE_VALUE
    );
    private static final Pattern ANY_PRICE = Pattern.compile(
            "(?i)R\\$\\s*" + PRICE_VALUE
    );
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    private OfferTextParser() {
    }

    static double extractPrice(String text) {
        Matcher priorityMatcher = PRIORITY_PRICE.matcher(text);
        while (priorityMatcher.find()) {
            if (!isInstallmentContext(text, priorityMatcher.start())
                    && !isDiscountContext(text, priorityMatcher.start(), priorityMatcher.end())) {
                return parseBrazilianPrice(priorityMatcher.group(1));
            }
        }
        Matcher atSightMatcher = AT_SIGHT_PRICE.matcher(text);
        if (atSightMatcher.find()) {
            return parseBrazilianPrice(atSightMatcher.group(1));
        }
        Matcher installmentMatcher = INSTALLMENT_PRICE.matcher(text);
        if (installmentMatcher.find()) {
            double installment = parseBrazilianPrice(installmentMatcher.group(2));
            if (!Double.isNaN(installment)) {
                return installmentMatcher.group(1).isEmpty()
                        ? installment
                        : Integer.parseInt(installmentMatcher.group(1)) * installment;
            }
        }
        Matcher matcher = ANY_PRICE.matcher(text);
        while (matcher.find()) {
            if (!isInstallmentContext(text, matcher.start())
                    && !isDiscountContext(text, matcher.start(), matcher.end())) {
                return parseBrazilianPrice(matcher.group(1));
            }
        }
        return Double.NaN;
    }

    static double extractPriceForInterest(String text, String interest) {
        int interestOffset = findInterestOffset(text, interest);
        if (interestOffset < 0) {
            return extractPrice(text);
        }
        List<PriceCandidate> candidates = collectPriceCandidates(text);
        PriceCandidate best = null;
        long bestScore = Long.MAX_VALUE;
        for (PriceCandidate candidate : candidates) {
            long score = Math.abs((long) candidate.offset - interestOffset)
                    + candidate.priority * 8L;
            if (candidate.offset >= interestOffset) {
                score -= 12L;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? Double.NaN : best.price;
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
        return decomposed
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static int findInterestOffset(String text, String interest) {
        if (text == null || interest == null) {
            return -1;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerInterest = interest.toLowerCase(Locale.ROOT).trim();
        int exactOffset = lowerText.indexOf(lowerInterest);
        if (exactOffset >= 0) {
            return exactOffset;
        }
        String normalizedInterest = normalize(interest);
        String normalizedText = normalize(text);
        int normalizedOffset = normalizedText.indexOf(normalizedInterest);
        return normalizedOffset;
    }

    private static List<PriceCandidate> collectPriceCandidates(String text) {
        List<PriceCandidate> candidates = new ArrayList<>();
        Matcher priorityMatcher = PRIORITY_PRICE.matcher(text);
        while (priorityMatcher.find()) {
            if (!isInstallmentContext(text, priorityMatcher.start())
                    && !isDiscountContext(text, priorityMatcher.start(), priorityMatcher.end())) {
                addPriceCandidate(candidates, priorityMatcher.start(),
                        parseBrazilianPrice(priorityMatcher.group(1)), 0);
            }
        }
        Matcher atSightMatcher = AT_SIGHT_PRICE.matcher(text);
        while (atSightMatcher.find()) {
            addPriceCandidate(candidates, atSightMatcher.start(),
                    parseBrazilianPrice(atSightMatcher.group(1)), 0);
        }
        Matcher installmentMatcher = INSTALLMENT_PRICE.matcher(text);
        while (installmentMatcher.find()) {
            double installment = parseBrazilianPrice(installmentMatcher.group(2));
            if (!Double.isNaN(installment)) {
                addPriceCandidate(candidates, installmentMatcher.start(),
                        Integer.parseInt(installmentMatcher.group(1)) * installment, 1);
            }
        }
        Matcher anyMatcher = ANY_PRICE.matcher(text);
        while (anyMatcher.find()) {
            if (!isInstallmentContext(text, anyMatcher.start())
                    && !isDiscountContext(text, anyMatcher.start(), anyMatcher.end())) {
                addPriceCandidate(candidates, anyMatcher.start(),
                        parseBrazilianPrice(anyMatcher.group(1)), 2);
            }
        }
        return candidates;
    }

    private static void addPriceCandidate(List<PriceCandidate> candidates, int offset,
                                          double price, int priority) {
        if (!Double.isNaN(price) && price > 0.0d) {
            candidates.add(new PriceCandidate(offset, price, priority));
        }
    }

    static boolean matchesInterest(String message, String interest) {
        String normalizedMessage = " " + normalize(message) + " ";
        String normalizedInterest = normalize(interest);
        if (normalizedInterest.isEmpty()) {
            return false;
        }
        int interestStart = normalizedMessage.indexOf(" " + normalizedInterest + " ");
        if (interestStart < 0) {
            return false;
        }
        if (containsAccessoryTerm(normalizedInterest)) {
            return true;
        }
        return !looksLikeAccessoryOffer(normalizedMessage.trim(), normalizedInterest);
    }

    static double selectPlausibleLowest(List<Double> prices) {
        if (prices == null || prices.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        List<Double> ordered = new ArrayList<>(prices);
        Collections.sort(ordered);
        if (ordered.size() == 1) {
            return ordered.get(0);
        }
        int middle = ordered.size() / 2;
        double median = ordered.size() % 2 == 0
                ? (ordered.get(middle - 1) + ordered.get(middle)) / 2.0d
                : ordered.get(middle);
        double plausibleFloor = median * 0.25d;
        for (double price : ordered) {
            if (price >= plausibleFloor) {
                return price;
            }
        }
        return ordered.get(0);
    }

    static boolean isWithinValidatedRange(double price, double plausibleFloor,
                                          double maximumPrice) {
        return price + 0.005d >= plausibleFloor
                && price <= maximumPrice + 0.005d;
    }

    private static double parseBrazilianPrice(String value) {
        try {
            return Double.parseDouble(value.replace(".", "").replace(',', '.'));
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private static boolean isInstallmentContext(String text, int priceStart) {
        return PriceContextClassifier.classify(text, priceStart, priceStart)
                == PriceContextClassifier.Meaning.INSTALLMENT;
    }

    private static boolean isDiscountContext(String text, int priceStart, int priceEnd) {
        PriceContextClassifier.Meaning meaning = PriceContextClassifier.classify(
                text,
                priceStart,
                priceEnd
        );
        return meaning == PriceContextClassifier.Meaning.DISCOUNT
                || meaning == PriceContextClassifier.Meaning.CASHBACK
                || meaning == PriceContextClassifier.Meaning.FREIGHT
                || meaning == PriceContextClassifier.Meaning.CREDIT;
    }

    private static boolean looksLikeAccessoryOffer(String message, String interest) {
        if (!containsAccessoryTerm(message)) {
            return false;
        }
        if (message.contains("para " + interest)
                || message.contains("compativel com " + interest)) {
            return true;
        }
        int interestStart = message.indexOf(interest);
        if (interestStart < 0) {
            return false;
        }
        String before = message.substring(0, interestStart).trim();
        String after = message.substring(interestStart + interest.length()).trim();
        String[] beforeWords = before.isEmpty() ? new String[0] : before.split(" ");
        for (int index = Math.max(0, beforeWords.length - 4); index < beforeWords.length; index++) {
            if (isAccessoryTerm(beforeWords[index])) {
                return true;
            }
        }
        String firstAfterWord = after.isEmpty() ? "" : after.split(" ")[0];
        return isAccessoryTerm(firstAfterWord);
    }

    private static boolean containsAccessoryTerm(String text) {
        String padded = " " + text + " ";
        for (String term : ACCESSORY_TERMS) {
            if (padded.contains(" " + term + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAccessoryTerm(String value) {
        for (String term : ACCESSORY_TERMS) {
            if (term.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class PriceCandidate {
        final int offset;
        final double price;
        final int priority;

        PriceCandidate(int offset, double price, int priority) {
            this.offset = offset;
            this.price = price;
            this.priority = priority;
        }
    }
}
