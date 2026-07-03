package br.com.droidboaoferta;

import android.content.Context;
import android.os.Build;

import org.drinkless.tdlib.JsonClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TelegramClientManager {
    enum State {
        STARTING,
        MISSING_CREDENTIALS,
        WAITING_PHONE,
        WAITING_EMAIL,
        WAITING_EMAIL_CODE,
        WAITING_CODE,
        WAITING_PASSWORD,
        READY,
        CLOSED,
        UNSUPPORTED_AUTHORIZATION
    }

    interface Listener {
        void onStateChanged(State state);

        void onGroupsLoaded(List<TelegramGroup> groups);

        void onError(String message);
    }

    interface MessageListener {
        void onNewMessage(long chatId, long messageId, String sourceTitle, String text);
    }

    private static final TelegramClientManager INSTANCE = new TelegramClientManager();

    static TelegramClientManager getInstance() {
        return INSTANCE;
    }

    private final Map<Long, JSONObject> chats = new HashMap<>();
    private final Set<Long> groupChatIds = new HashSet<>();
    private volatile Listener listener;
    private volatile MessageListener messageListener;
    private volatile State state = State.STARTING;
    private volatile List<TelegramGroup> groups = Collections.emptyList();
    private boolean started;
    private int clientId;
    private Context appContext;

    private TelegramClientManager() {
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (started) {
            notifyState();
            notifyGroups();
            return;
        }
        started = true;

        if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isEmpty()) {
            changeState(State.MISSING_CREDENTIALS);
            return;
        }

        Thread receiverThread = new Thread(this::runReceiver, "boa-oferta-tdlib");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    void setListener(Listener listener) {
        this.listener = listener;
        notifyState();
        notifyGroups();
    }

    void clearListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    void setMessageListener(MessageListener messageListener) {
        this.messageListener = messageListener;
    }

    State getState() {
        return state;
    }

    void submitPhoneNumber(String phoneNumber) {
        try {
            JSONObject settings = new JSONObject()
                    .put("@type", "phoneNumberAuthenticationSettings")
                    .put("allow_flash_call", false)
                    .put("allow_missed_call", false)
                    .put("is_current_phone_number", false)
                    .put("has_unknown_phone_number", false)
                    .put("allow_sms_retriever_api", false)
                    .put("firebase_authentication_settings", JSONObject.NULL)
                    .put("authentication_tokens", new JSONArray());
            send(new JSONObject()
                    .put("@type", "setAuthenticationPhoneNumber")
                    .put("phone_number", phoneNumber)
                    .put("settings", settings)
                    .put("@extra", "authentication"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    void submitEmail(String email) {
        sendAuthenticationValue("setAuthenticationEmailAddress", "email_address", email);
    }

    void submitEmailCode(String code) {
        try {
            send(new JSONObject()
                    .put("@type", "checkAuthenticationEmailCode")
                    .put("code", new JSONObject()
                            .put("@type", "emailAddressAuthenticationCode")
                            .put("code", code))
                    .put("@extra", "authentication"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    void submitCode(String code) {
        sendAuthenticationValue("checkAuthenticationCode", "code", code);
    }

    void submitPassword(String password) {
        sendAuthenticationValue("checkAuthenticationPassword", "password", password);
    }

    void loadGroups() {
        try {
            send(new JSONObject()
                    .put("@type", "getChats")
                    .put("chat_list", new JSONObject().put("@type", "chatListMain"))
                    .put("limit", 200)
                    .put("@extra", "load_groups_main"));
            send(new JSONObject()
                    .put("@type", "getChats")
                    .put("chat_list", new JSONObject().put("@type", "chatListArchive"))
                    .put("limit", 200)
                    .put("@extra", "load_groups_archive"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private void runReceiver() {
        try {
            JsonClient.setLogMessageHandler(1, (verbosityLevel, message) -> {
                // Logs detalhados da sessão não são persistidos para preservar a privacidade.
            });
            clientId = JsonClient.createClientId();
            JsonClient.send(clientId, new JSONObject().put("@type", "getAuthorizationState").toString());
            while (!Thread.currentThread().isInterrupted()) {
                String result = JsonClient.receive(1.0);
                if (result != null) {
                    handleResult(new JSONObject(result));
                }
            }
        } catch (Throwable throwable) {
            changeState(State.CLOSED);
            notifyError(throwable.getMessage() == null
                    ? appContext.getString(R.string.telegram_native_error)
                    : throwable.getMessage());
        }
    }

    private void handleResult(JSONObject result) throws Exception {
        String type = result.optString("@type");
        if ("updateAuthorizationState".equals(type)) {
            handleAuthorizationState(result.getJSONObject("authorization_state"));
        } else if ("updateNewChat".equals(type)) {
            JSONObject chat = result.getJSONObject("chat");
            chats.put(chat.getLong("id"), chat);
        } else if ("updateChatTitle".equals(type)) {
            JSONObject chat = chats.get(result.getLong("chat_id"));
            if (chat != null) {
                chat.put("title", result.getString("title"));
            }
        } else if ("updateNewMessage".equals(type)) {
            publishMessage(result.getJSONObject("message"));
        } else if ("chats".equals(type) && result.optString("@extra").startsWith("load_groups_")) {
            publishGroups(result.getJSONArray("chat_ids"));
        } else if ("error".equals(type)) {
            notifyError(result.optString("message", appContext.getString(R.string.telegram_unknown_error)));
        }
    }

    private void publishMessage(JSONObject message) {
        MessageListener currentListener = messageListener;
        if (currentListener == null) {
            return;
        }

        long chatId = message.optLong("chat_id");
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", Collections.emptySet());
        if (!selectedGroups.contains(Long.toString(chatId))) {
            return;
        }

        JSONObject content = message.optJSONObject("content");
        if (content == null) {
            return;
        }
        JSONObject formattedText;
        if ("messageText".equals(content.optString("@type"))) {
            formattedText = content.optJSONObject("text");
        } else {
            formattedText = content.optJSONObject("caption");
        }
        if (formattedText == null) {
            return;
        }

        JSONObject chat = chats.get(chatId);
        String sourceTitle = chat == null ? appContext.getString(R.string.telegram_source_unknown)
                : chat.optString("title", appContext.getString(R.string.telegram_source_unknown));
        MonitorStatusStore.markSelectedMessage(appContext);
        currentListener.onNewMessage(
                chatId,
                message.optLong("id"),
                sourceTitle,
                formattedText.optString("text")
        );
    }

    private void handleAuthorizationState(JSONObject authorizationState) throws Exception {
        switch (authorizationState.getString("@type")) {
            case "authorizationStateWaitTdlibParameters":
                sendTdlibParameters();
                break;
            case "authorizationStateWaitPhoneNumber":
                changeState(State.WAITING_PHONE);
                break;
            case "authorizationStateWaitEmailAddress":
                changeState(State.WAITING_EMAIL);
                break;
            case "authorizationStateWaitEmailCode":
                changeState(State.WAITING_EMAIL_CODE);
                break;
            case "authorizationStateWaitCode":
                changeState(State.WAITING_CODE);
                break;
            case "authorizationStateWaitPassword":
                changeState(State.WAITING_PASSWORD);
                break;
            case "authorizationStateReady":
                changeState(State.READY);
                loadGroups();
                break;
            case "authorizationStateClosing":
            case "authorizationStateLoggingOut":
            case "authorizationStateClosed":
                changeState(State.CLOSED);
                break;
            default:
                changeState(State.UNSUPPORTED_AUTHORIZATION);
                break;
        }
    }

    private void sendTdlibParameters() throws Exception {
        File databaseDirectory = new File(appContext.getFilesDir(), "tdlib/database");
        File filesDirectory = new File(appContext.getFilesDir(), "tdlib/files");
        if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o banco local do Telegram.");
        }
        if (!filesDirectory.exists() && !filesDirectory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta local do Telegram.");
        }

        JSONObject request = new JSONObject()
                .put("@type", "setTdlibParameters")
                .put("use_test_dc", false)
                .put("database_directory", databaseDirectory.getAbsolutePath())
                .put("files_directory", filesDirectory.getAbsolutePath())
                .put("database_encryption_key", TdlibDatabaseKey.getOrCreateBase64(appContext))
                .put("use_file_database", true)
                .put("use_chat_info_database", true)
                .put("use_message_database", false)
                .put("use_secret_chats", false)
                .put("api_id", BuildConfig.TELEGRAM_API_ID)
                .put("api_hash", BuildConfig.TELEGRAM_API_HASH)
                .put("system_language_code", Locale.getDefault().toLanguageTag())
                .put("device_model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("system_version", Build.VERSION.RELEASE)
                .put("application_version", BuildConfig.VERSION_NAME);
        send(request);
    }

    private void publishGroups(JSONArray chatIds) {
        for (int index = 0; index < chatIds.length(); index++) {
            groupChatIds.add(chatIds.optLong(index));
        }

        List<TelegramGroup> availableGroups = new ArrayList<>();
        for (long chatId : groupChatIds) {
            JSONObject chat = chats.get(chatId);
            if (chat == null) {
                continue;
            }
            String chatType = chat.optJSONObject("type") == null
                    ? ""
                    : chat.optJSONObject("type").optString("@type");
            if ("chatTypeBasicGroup".equals(chatType) || "chatTypeSupergroup".equals(chatType)) {
                availableGroups.add(new TelegramGroup(chatId, chat.optString("title")));
            }
        }
        availableGroups.sort(Comparator.comparing(
                TelegramGroup::getTitle,
                String.CASE_INSENSITIVE_ORDER
        ));
        groups = Collections.unmodifiableList(availableGroups);
        notifyGroups();
    }

    private void sendAuthenticationValue(String type, String key, String value) {
        try {
            send(new JSONObject()
                    .put("@type", type)
                    .put(key, value)
                    .put("@extra", "authentication"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private void send(JSONObject request) {
        if (clientId != 0) {
            JsonClient.send(clientId, request.toString());
        }
    }

    private void changeState(State newState) {
        state = newState;
        MonitorStatusStore.setTelegramState(appContext, newState);
        notifyState();
    }

    private void notifyState() {
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onStateChanged(state);
        }
    }

    private void notifyGroups() {
        Listener currentListener = listener;
        if (currentListener != null && state == State.READY) {
            currentListener.onGroupsLoaded(groups);
        }
    }

    private void notifyError(String message) {
        MonitorStatusStore.setLastError(appContext, message);
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onError(message);
        }
    }
}
