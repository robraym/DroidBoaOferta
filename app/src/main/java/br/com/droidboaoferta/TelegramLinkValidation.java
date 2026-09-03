package br.com.droidboaoferta;

import org.json.JSONObject;

final class TelegramLinkValidation {
    private TelegramLinkValidation() { }

    static JSONObject readableMessage(JSONObject response, long expectedChatId, long expectedMessageId) {
        if (response == null || !"messageLinkInfo".equals(response.optString("@type"))) return null;
        JSONObject message = response.optJSONObject("message");
        if (message == null || message.optLong("id") <= 0L || message.optLong("chat_id") == 0L
                || (expectedChatId != 0L && message.optLong("chat_id") != expectedChatId)
                || (expectedMessageId != 0L && message.optLong("id") != expectedMessageId)
                || TelegramMessagePayload.fromMessage(message).getText().trim().isEmpty()) return null;
        return message;
    }
}
