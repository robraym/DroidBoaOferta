package br.com.droidboaoferta;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TelegramLinkValidationTest {
    @Test
    public void acceptsOnlyTheOriginalReadableMessage() throws Exception {
        JSONObject result = response("Z Flip7 R$ 3999");
        assertNotNull(TelegramLinkValidation.readableMessage(result, -1001, 1048576));
        assertNull(TelegramLinkValidation.readableMessage(result, -1002, 1048576));
        assertNull(TelegramLinkValidation.readableMessage(result, -1001, 2097152));
    }

    @Test
    public void rejectsTimeoutAccessErrorDeletedMessageAndEmptyPage() throws Exception {
        assertNull(TelegramLinkValidation.readableMessage(null, 0, 0));
        assertNull(TelegramLinkValidation.readableMessage(new JSONObject().put("@type", "error")
                .put("code", 403), 0, 0));
        assertNull(TelegramLinkValidation.readableMessage(new JSONObject().put("@type", "messageLinkInfo")
                .put("message", JSONObject.NULL), 0, 0));
        assertNull(TelegramLinkValidation.readableMessage(response(" "), 0, 0));
    }

    @Test
    public void acceptsReadablePhotoCaptionButNotUnrelatedResponseType() throws Exception {
        JSONObject result = response("Z Flip7 FE");
        JSONObject content = result.getJSONObject("message").getJSONObject("content");
        content.put("@type", "messagePhoto").put("caption", content.remove("text"));
        assertNotNull(TelegramLinkValidation.readableMessage(result, 0, 0));
        result.put("@type", "messageLink");
        assertNull(TelegramLinkValidation.readableMessage(result, 0, 0));
    }

    private JSONObject response(String text) throws Exception {
        return new JSONObject().put("@type", "messageLinkInfo")
                .put("message", new JSONObject().put("id", 1048576).put("chat_id", -1001)
                        .put("content", new JSONObject().put("@type", "messageText")
                                .put("text", new JSONObject().put("text", text))));
    }
}
