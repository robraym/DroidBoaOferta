package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TelegramMessagePayload {
    private static final Pattern PLAIN_URL = Pattern.compile(
            "https?://[^\\s<>]+",
            Pattern.CASE_INSENSITIVE
    );

    private final String text;
    private final List<LinkCandidate> links;

    private TelegramMessagePayload(String text, List<LinkCandidate> links) {
        this.text = text == null ? "" : text;
        this.links = links;
    }

    static TelegramMessagePayload fromCandidates(String text, String[] urls,
                                                 int[] offsets, String[] labels) {
        List<LinkCandidate> candidates = new ArrayList<>();
        int count = urls == null ? 0 : urls.length;
        for (int index = 0; index < count; index++) {
            int offset = offsets != null && index < offsets.length ? offsets[index] : -1;
            String label = labels != null && index < labels.length ? labels[index] : "";
            candidates.add(new LinkCandidate(urls[index], offset, label));
        }
        return new TelegramMessagePayload(text, candidates);
    }

    static TelegramMessagePayload fromMessage(JSONObject message) {
        JSONObject content = message == null ? null : message.optJSONObject("content");
        JSONObject formattedText = content == null ? null
                : ("messageText".equals(content.optString("@type"))
                ? content.optJSONObject("text")
                : content.optJSONObject("caption"));
        String text = formattedText == null ? "" : formattedText.optString("text", "");
        Map<String, LinkCandidate> candidates = new LinkedHashMap<>();
        collectEntityLinks(formattedText, text, candidates);
        collectPlainLinks(text, candidates);
        collectButtonLinks(message, text.length(), candidates);
        return new TelegramMessagePayload(text, new ArrayList<>(candidates.values()));
    }

    String getText() {
        return text;
    }

    String findBestLink(String interest) {
        if (links.isEmpty()) {
            return OfferTextParser.extractLink(text);
        }
        if (links.size() == 1) {
            return links.get(0).url;
        }
        String normalizedInterest = OfferTextParser.normalize(interest);
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerInterest = interest == null ? "" : interest.toLowerCase(Locale.ROOT).trim();
        int interestOffset = lowerInterest.isEmpty() ? -1 : lowerText.indexOf(lowerInterest);
        LinkCandidate best = links.get(0);
        long bestScore = Long.MAX_VALUE;
        for (LinkCandidate candidate : links) {
            long score = candidate.offset < 0 || interestOffset < 0
                    ? 100000L
                    : Math.abs((long) candidate.offset - interestOffset);
            String normalizedLabel = OfferTextParser.normalize(candidate.label);
            if (!normalizedInterest.isEmpty() && normalizedLabel.contains(normalizedInterest)) {
                score -= 100000L;
            } else {
                score -= 1000L * countMatchingWords(normalizedLabel, normalizedInterest);
            }
            if (candidate.offset >= interestOffset && interestOffset >= 0) {
                score -= 100L;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best.url;
    }

    private static void collectEntityLinks(JSONObject formattedText, String text,
                                           Map<String, LinkCandidate> candidates) {
        JSONArray entities = formattedText == null ? null : formattedText.optJSONArray("entities");
        if (entities == null) {
            return;
        }
        for (int index = 0; index < entities.length(); index++) {
            JSONObject entity = entities.optJSONObject(index);
            JSONObject type = entity == null ? null : entity.optJSONObject("type");
            if (type == null) {
                continue;
            }
            int offset = Math.max(0, entity.optInt("offset", 0));
            int end = Math.min(text.length(), offset + Math.max(0, entity.optInt("length", 0)));
            String label = offset < end ? text.substring(offset, end) : "";
            String url = "";
            if ("textEntityTypeTextUrl".equals(type.optString("@type"))) {
                url = type.optString("url", "");
            } else if ("textEntityTypeUrl".equals(type.optString("@type"))) {
                url = label;
            }
            addCandidate(candidates, url, offset, label);
        }
    }

    private static void collectPlainLinks(String text, Map<String, LinkCandidate> candidates) {
        Matcher matcher = PLAIN_URL.matcher(text);
        while (matcher.find()) {
            addCandidate(
                    candidates,
                    matcher.group().replaceAll("[),.;]+$", ""),
                    matcher.start(),
                    matcher.group()
            );
        }
    }

    private static void collectButtonLinks(JSONObject message, int textLength,
                                           Map<String, LinkCandidate> candidates) {
        JSONObject replyMarkup = message == null ? null : message.optJSONObject("reply_markup");
        JSONArray rows = replyMarkup == null ? null : replyMarkup.optJSONArray("rows");
        if (rows == null) {
            return;
        }
        List<JSONObject> buttons = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.length(); rowIndex++) {
            JSONArray row = rows.optJSONArray(rowIndex);
            if (row == null) {
                continue;
            }
            for (int buttonIndex = 0; buttonIndex < row.length(); buttonIndex++) {
                JSONObject button = row.optJSONObject(buttonIndex);
                if (button != null) {
                    buttons.add(button);
                }
            }
        }
        for (int index = 0; index < buttons.size(); index++) {
            JSONObject button = buttons.get(index);
            JSONObject type = button.optJSONObject("type");
            String url = type == null ? "" : type.optString("url", "");
            int approximateOffset = buttons.isEmpty()
                    ? -1
                    : ((index + 1) * textLength) / (buttons.size() + 1);
            addCandidate(candidates, url, approximateOffset, button.optString("text", ""));
        }
    }

    private static void addCandidate(Map<String, LinkCandidate> candidates, String url,
                                     int offset, String label) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return;
        }
        candidates.putIfAbsent(url, new LinkCandidate(url, offset, label == null ? "" : label));
    }

    private static int countMatchingWords(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return 0;
        }
        int count = 0;
        String paddedFirst = " " + first + " ";
        for (String word : second.split(" ")) {
            if (word.length() >= 3 && paddedFirst.contains(" " + word + " ")) {
                count++;
            }
        }
        return count;
    }

    private static final class LinkCandidate {
        final String url;
        final int offset;
        final String label;

        LinkCandidate(String url, int offset, String label) {
            this.url = url;
            this.offset = offset;
            this.label = label;
        }
    }
}
