package br.com.droidboaoferta;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

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
    private static final String TAG = "BoaOfertaSync";
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

        default void onAccountChanged() {
        }

        default void onCloudSyncStatus(int messageResource) {
        }
    }

    interface MessageListener {
        void onNewMessage(long chatId, long messageId, long messageDate, String sourceTitle, String text);

        default void onHistoricalMessage(long interestId, long chatId, long messageId,
                                         long messageDate, String sourceTitle, String text) {
        }
    }

    private static final TelegramClientManager INSTANCE = new TelegramClientManager();
    private static final long CLOUD_BACKUP_DEBOUNCE_MS = 3000L;
    private static final long CLOUD_BACKUP_MIN_INTERVAL_MS = 30000L;
    private static final long CLOUD_PULL_DEBOUNCE_MS = 1500L;
    static final String ACTION_CLOUD_SYNC_CHANGED =
            BuildConfig.APPLICATION_ID + ".action.CLOUD_SYNC_CHANGED";

    static TelegramClientManager getInstance() {
        return INSTANCE;
    }

    private final Map<Long, JSONObject> chats = new HashMap<>();
    private final Map<String, InterestHistorySearch> interestHistorySearches = new HashMap<>();
    private final Set<Long> groupChatIds = new HashSet<>();
    private final Set<Long> requestedHistoryChatIds = new HashSet<>();
    private final Set<Long> pendingCloudMessageIds = new HashSet<>();
    private final Handler cloudSyncHandler = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile MessageListener messageListener;
    private volatile State state = State.STARTING;
    private volatile List<TelegramGroup> groups = Collections.emptyList();
    private volatile String accountName = "";
    private volatile String accountPhone = "";
    private volatile boolean currentCodeSentBySms;
    private volatile boolean nextCodeAvailableBySms;
    private volatile int authenticationCodeLength;
    private volatile long nextCodeAvailableAtElapsed;
    private volatile long selfUserId;
    private volatile long selfChatId;
    private volatile boolean cloudSyncRequested;
    private volatile boolean cloudHistoryFallbackRequested;
    private volatile boolean forceCloudRestore;
    private volatile boolean pendingManualBackup;
    private volatile boolean pendingManualBackupConfirmation;
    private volatile boolean pendingManualRestore;
    private volatile boolean selfChatRequested;
    private volatile boolean cloudBackupScheduled;
    private volatile boolean cloudPullScheduled;
    private volatile long lastCloudBackupSentAt;
    private volatile int pendingCloudExpectedMessages;
    private volatile int pendingCloudConfirmedMessages;
    private volatile boolean pendingCloudBackupFailed;
    private boolean started;
    private volatile boolean receiverRunning;
    private int clientId;
    private long interestHistoryGeneration;
    private Context appContext;

    private TelegramClientManager() {
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        Log.d(TAG, "start called, started=" + started + ", state=" + state);
        if (started) {
            if (state == State.READY && selfChatId != 0L) {
                scheduleCloudPull();
            }
            notifyState();
            notifyGroups();
            return;
        }
        started = true;
        receiverRunning = true;

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

    void refreshGroups() {
        if (state != State.READY) {
            return;
        }
        groupChatIds.clear();
        loadGroups();
    }

    void setMessageListener(MessageListener messageListener) {
        this.messageListener = messageListener;
        if (messageListener != null && state == State.READY) {
            loadSelectedGroupsHistory();
        }
    }

    void refreshSelectedGroupsHistory() {
        requestedHistoryChatIds.clear();
        if (state == State.READY) {
            loadSelectedGroupsHistory();
        }
    }

    synchronized void refreshInterestHistory(long interestId, String term) {
        if (state != State.READY || term == null || term.trim().isEmpty()) {
            return;
        }
        interestHistoryGeneration++;
        interestHistorySearches.clear();
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", Collections.emptySet());
        for (String selectedGroup : selectedGroups) {
            try {
                long chatId = Long.parseLong(selectedGroup);
                String extra = "interest_history:" + interestHistoryGeneration + ":" + chatId;
                InterestHistorySearch search = new InterestHistorySearch(
                        interestId,
                        chatId,
                        term.trim(),
                        extra
                );
                interestHistorySearches.put(extra, search);
                requestInterestHistoryPage(search, 0L);
            } catch (NumberFormatException exception) {
                Log.w(TAG, "Invalid selected chat id", exception);
            }
        }
    }

    void syncCloudBackupSoon() {
        Log.d(TAG, "syncCloudBackupSoon state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            return;
        }
        if (selfChatId == 0L) {
            requestSelfChat();
            return;
        }
        scheduleCloudBackup();
    }

    void backupCloudNow() {
        Log.d(TAG, "backupCloudNow state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            notifyCloudSyncStatus(R.string.profile_cloud_backup_login_required);
            return;
        }
        CloudSyncStore.markManualBackupRequested(appContext);
        pendingManualBackupConfirmation = true;
        if (selfChatId == 0L) {
            pendingManualBackup = true;
            notifyCloudSyncStatus(R.string.profile_cloud_backup_preparing);
            requestSelfChat();
            return;
        }
        sendCloudBackup();
    }

    void restoreCloudBackupNow() {
        Log.d(TAG, "restoreCloudBackupNow state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            notifyCloudSyncStatus(R.string.profile_cloud_backup_login_required);
            return;
        }
        if (selfChatId == 0L) {
            pendingManualRestore = true;
            notifyCloudSyncStatus(R.string.profile_cloud_backup_preparing);
            requestSelfChat();
            return;
        }
        forceCloudRestore = true;
        cloudSyncRequested = false;
        cloudHistoryFallbackRequested = false;
        notifyCloudSyncStatus(R.string.profile_cloud_restore_searching);
        requestCloudBackup();
    }

    State getState() {
        return state;
    }

    String getAccountName() {
        return accountName;
    }

    String getAccountPhone() {
        return accountPhone;
    }

    synchronized void logOut() {
        messageListener = null;
        clearTelegramRuntimeData();
        if (!started || clientId == 0) {
            changeState(State.CLOSED);
            closeRuntime();
            return;
        }
        try {
            send(new JSONObject().put("@type", "logOut").put("@extra", "logout"));
            changeState(State.CLOSED);
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
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

    void requestAuthenticationCodeBySms() {
        try {
            send(new JSONObject()
                    .put("@type", "resendAuthenticationCode")
                    .put("reason", new JSONObject()
                            .put("@type", "resendCodeReasonUserRequest"))
                    .put("@extra", "authentication_sms"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    boolean isCurrentCodeSentBySms() {
        return currentCodeSentBySms;
    }

    boolean isNextCodeAvailableBySms() {
        return nextCodeAvailableBySms;
    }

    int getAuthenticationCodeLength() {
        return authenticationCodeLength;
    }

    long getNextCodeDelayMillis() {
        return Math.max(0L, nextCodeAvailableAtElapsed - SystemClock.elapsedRealtime());
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
            while (receiverRunning && !Thread.currentThread().isInterrupted()) {
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
        String extra = result.optString("@extra");
        if (type.startsWith("authorization")
                || type.startsWith("updateAuthorization")
                || extra.startsWith("cloud_sync")
                || "user".equals(type)) {
            Log.d(TAG, "result type=" + type + ", extra=" + extra);
        }
        if ("updateAuthorizationState".equals(type)) {
            handleAuthorizationState(result.getJSONObject("authorization_state"));
        } else if ("updateNewChat".equals(type)) {
            JSONObject chat = result.getJSONObject("chat");
            chats.put(chat.getLong("id"), chat);
            publishAvailableGroups();
        } else if ("updateChatTitle".equals(type)) {
            JSONObject chat = chats.get(result.getLong("chat_id"));
            if (chat != null) {
                chat.put("title", result.getString("title"));
                publishAvailableGroups();
            }
        } else if ("updateNewMessage".equals(type)) {
            JSONObject message = result.getJSONObject("message");
            handleIncomingCloudSyncMessage(message);
            publishMessage(message);
        } else if ("updateMessageSendSucceeded".equals(type)) {
            handleMessageSendSucceeded(result);
        } else if ("updateMessageSendFailed".equals(type)) {
            handleMessageSendFailed(result);
        } else if ("chat".equals(type) && "cloud_sync_self_chat".equals(result.optString("@extra"))) {
            handleSelfChat(result);
        } else if ("message".equals(type) && result.optString("@extra").startsWith("cloud_sync_")) {
            handleCloudSyncSentMessage(result);
        } else if ("chats".equals(type) && result.optString("@extra").startsWith("load_groups_")) {
            publishGroups(result.getJSONArray("chat_ids"));
        } else if ("messages".equals(type) && "selected_group_history".equals(result.optString("@extra"))) {
            publishMessages(result.optJSONArray("messages"));
        } else if ("foundChatMessages".equals(type)
                && result.optString("@extra").startsWith("interest_history:")) {
            handleInterestHistoryPage(result);
        } else if ("messages".equals(type) && result.optString("@extra").startsWith("cloud_sync_")) {
            handleCloudSyncMessages(result.optJSONArray("messages"), result.optString("@extra"));
        } else if ("foundChatMessages".equals(type) && result.optString("@extra").startsWith("cloud_sync_")) {
            handleCloudSyncMessages(result.optJSONArray("messages"), result.optString("@extra"));
        } else if ("user".equals(type) && "account_me".equals(result.optString("@extra"))) {
            publishAccount(result);
        } else if ("error".equals(type)) {
            if (result.optString("@extra").startsWith("interest_history:")) {
                interestHistorySearches.remove(result.optString("@extra"));
            }
            if (result.optString("@extra").startsWith("cloud_sync_search")) {
                requestCloudSyncHistoryFallback();
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_send")) {
                failPendingCloudBackup();
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_")) {
                return;
            }
            notifyError(result.optString("message", appContext.getString(R.string.telegram_unknown_error)));
        }
    }

    private void publishMessages(JSONArray messages) {
        if (messages == null) {
            return;
        }
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message != null) {
                publishMessage(message);
            }
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
                message.optLong("date") * 1000L,
                sourceTitle,
                formattedText.optString("text")
        );
    }

    private synchronized void handleInterestHistoryPage(JSONObject result) {
        String extra = result.optString("@extra");
        InterestHistorySearch search = interestHistorySearches.get(extra);
        if (search == null) {
            return;
        }
        JSONArray messages = result.optJSONArray("messages");
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                if (message != null) {
                    publishHistoricalMessage(search.interestId, message);
                }
            }
        }
        long nextFromMessageId = result.optLong("next_from_message_id", 0L);
        if (nextFromMessageId == 0L || nextFromMessageId == search.lastFromMessageId) {
            interestHistorySearches.remove(extra);
            return;
        }
        search.lastFromMessageId = nextFromMessageId;
        requestInterestHistoryPage(search, nextFromMessageId);
    }

    private void publishHistoricalMessage(long interestId, JSONObject message) {
        MessageListener currentListener = messageListener;
        if (currentListener == null) {
            return;
        }
        long chatId = message.optLong("chat_id");
        JSONObject content = message.optJSONObject("content");
        if (content == null) {
            return;
        }
        JSONObject formattedText = "messageText".equals(content.optString("@type"))
                ? content.optJSONObject("text")
                : content.optJSONObject("caption");
        if (formattedText == null) {
            return;
        }
        JSONObject chat = chats.get(chatId);
        String sourceTitle = chat == null ? appContext.getString(R.string.telegram_source_unknown)
                : chat.optString("title", appContext.getString(R.string.telegram_source_unknown));
        currentListener.onHistoricalMessage(
                interestId,
                chatId,
                message.optLong("id"),
                message.optLong("date") * 1000L,
                sourceTitle,
                formattedText.optString("text")
        );
    }

    private void requestInterestHistoryPage(InterestHistorySearch search, long fromMessageId) {
        try {
            send(new JSONObject()
                    .put("@type", "searchChatMessages")
                    .put("chat_id", search.chatId)
                    .put("query", search.term)
                    .put("sender_id", JSONObject.NULL)
                    .put("from_message_id", fromMessageId)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                    .put("message_thread_id", 0)
                    .put("@extra", search.extra));
        } catch (JSONException exception) {
            interestHistorySearches.remove(search.extra);
            notifyError(exception.getMessage());
        }
    }

    private void handleAuthorizationState(JSONObject authorizationState) throws Exception {
        Log.d(TAG, "authorization state=" + authorizationState.optString("@type"));
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
                updateAuthenticationCodeInfo(authorizationState.optJSONObject("code_info"));
                changeState(State.WAITING_CODE);
                break;
            case "authorizationStateWaitPassword":
                changeState(State.WAITING_PASSWORD);
                break;
            case "authorizationStateReady":
                changeState(State.READY);
                loadAccount();
                loadGroups();
                loadSelectedGroupsHistory();
                break;
            case "authorizationStateClosing":
            case "authorizationStateLoggingOut":
                changeState(State.CLOSED);
                break;
            case "authorizationStateClosed":
                changeState(State.CLOSED);
                closeRuntime();
                break;
            default:
                changeState(State.UNSUPPORTED_AUTHORIZATION);
                break;
        }
    }

    private void updateAuthenticationCodeInfo(JSONObject codeInfo) {
        if (codeInfo == null) {
            currentCodeSentBySms = false;
            nextCodeAvailableBySms = false;
            authenticationCodeLength = 0;
            nextCodeAvailableAtElapsed = 0L;
            return;
        }
        JSONObject currentType = codeInfo.optJSONObject("type");
        JSONObject nextType = codeInfo.optJSONObject("next_type");
        Log.d(TAG, "authentication code current="
                + (currentType == null ? "none" : currentType.optString("@type"))
                + ", next="
                + (nextType == null ? "none" : nextType.optString("@type"))
                + ", timeout=" + codeInfo.optInt("timeout", 0));
        currentCodeSentBySms = currentType != null
                && "authenticationCodeTypeSms".equals(currentType.optString("@type"));
        nextCodeAvailableBySms = nextType != null
                && "authenticationCodeTypeSms".equals(nextType.optString("@type"));
        authenticationCodeLength = currentType == null ? 0 : currentType.optInt("length", 0);
        nextCodeAvailableAtElapsed = SystemClock.elapsedRealtime()
                + Math.max(0, codeInfo.optInt("timeout", 0)) * 1000L;
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

    private void loadAccount() {
        Log.d(TAG, "loadAccount");
        try {
            send(new JSONObject()
                    .put("@type", "getMe")
                    .put("@extra", "account_me"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private void publishAccount(JSONObject user) {
        String firstName = user.optString("first_name", "").trim();
        String lastName = user.optString("last_name", "").trim();
        String fullName = (firstName + " " + lastName).trim();
        accountName = fullName.isEmpty() ? user.optString("username", "").trim() : fullName;
        accountPhone = user.optString("phone_number", "").trim();
        selfUserId = user.optLong("id", 0L);
        Log.d(TAG, "publishAccount selfUserId=" + selfUserId + ", accountName=" + accountName);
        requestSelfChat();
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onAccountChanged();
        }
    }

    private synchronized void closeRuntime() {
        receiverRunning = false;
        started = false;
        clientId = 0;
        clearTelegramRuntimeData();
    }

    private void clearTelegramRuntimeData() {
        chats.clear();
        groupChatIds.clear();
        requestedHistoryChatIds.clear();
        groups = Collections.emptyList();
        accountName = "";
        accountPhone = "";
        selfUserId = 0L;
        selfChatId = 0L;
        cloudSyncRequested = false;
        cloudHistoryFallbackRequested = false;
        forceCloudRestore = false;
        pendingManualBackup = false;
        pendingManualBackupConfirmation = false;
        pendingManualRestore = false;
        selfChatRequested = false;
        cloudPullScheduled = false;
        pendingCloudMessageIds.clear();
        pendingCloudExpectedMessages = 0;
        pendingCloudConfirmedMessages = 0;
        pendingCloudBackupFailed = false;
        notifyGroups();
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onAccountChanged();
        }
    }

    private void publishGroups(JSONArray chatIds) {
        for (int index = 0; index < chatIds.length(); index++) {
            groupChatIds.add(chatIds.optLong(index));
        }
        publishAvailableGroups();
    }

    private void publishAvailableGroups() {
        if (state != State.READY) {
            return;
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

    private void loadSelectedGroupsHistory() {
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", Collections.emptySet());
        for (String selectedGroup : selectedGroups) {
            try {
                long chatId = Long.parseLong(selectedGroup);
                if (!requestedHistoryChatIds.add(chatId)) {
                    continue;
                }
                send(new JSONObject()
                        .put("@type", "getChatHistory")
                        .put("chat_id", chatId)
                        .put("from_message_id", 0)
                        .put("offset", 0)
                        .put("limit", 50)
                        .put("only_local", false)
                        .put("@extra", "selected_group_history"));
            } catch (Exception exception) {
                notifyError(exception.getMessage());
            }
        }
    }

    private void requestCloudBackup() {
        Log.d(TAG, "requestCloudBackup requested=" + cloudSyncRequested + ", selfChatId=" + selfChatId);
        if (cloudSyncRequested || selfChatId == 0L) {
            return;
        }
        cloudSyncRequested = true;
        try {
            send(new JSONObject()
                    .put("@type", "searchChatMessages")
                    .put("chat_id", selfChatId)
                    .put("query", CloudSyncStore.MARKER)
                    .put("sender_id", JSONObject.NULL)
                    .put("from_message_id", 0)
                    .put("offset", 0)
                    .put("limit", 20)
                    .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                    .put("message_thread_id", 0)
                    .put("@extra", "cloud_sync_search"));
        } catch (JSONException exception) {
            requestCloudSyncHistoryFallback();
        }
    }

    private void requestSelfChat() {
        Log.d(TAG, "requestSelfChat selfUserId=" + selfUserId + ", requested=" + selfChatRequested);
        if (selfUserId == 0L || selfChatRequested) {
            return;
        }
        selfChatRequested = true;
        try {
            send(new JSONObject()
                    .put("@type", "createPrivateChat")
                    .put("user_id", selfUserId)
                    .put("force", false)
                    .put("@extra", "cloud_sync_self_chat"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private void handleSelfChat(JSONObject chat) {
        selfChatId = chat.optLong("id", 0L);
        Log.d(TAG, "handleSelfChat selfChatId=" + selfChatId);
        requestCloudBackup();
        if (pendingManualBackup) {
            pendingManualBackup = false;
            sendCloudBackup();
        }
        if (pendingManualRestore) {
            pendingManualRestore = false;
            restoreCloudBackupNow();
        }
        if (CloudSyncStore.hasPendingPush(appContext)) {
            scheduleCloudBackup();
        }
    }

    private void requestCloudSyncHistoryFallback() {
        if (cloudHistoryFallbackRequested || selfChatId == 0L) {
            return;
        }
        cloudHistoryFallbackRequested = true;
        try {
            send(new JSONObject()
                    .put("@type", "getChatHistory")
                    .put("chat_id", selfChatId)
                    .put("from_message_id", 0)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("only_local", false)
                    .put("@extra", "cloud_sync_history"));
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private void handleCloudSyncMessages(JSONArray messages, String extra) {
        Log.d(TAG, "handleCloudSyncMessages count=" + (messages == null ? -1 : messages.length())
                + ", force=" + forceCloudRestore + ", extra=" + extra);
        if ("cloud_sync_search".equals(extra) && (messages == null || messages.length() == 0)) {
            requestCloudSyncHistoryFallback();
            return;
        }
        cloudSyncRequested = false;
        cloudHistoryFallbackRequested = false;
        JSONObject remoteBackup = CloudSyncStore.findNewestBackup(messages);
        Log.d(TAG, "remoteBackup found=" + (remoteBackup != null)
                + ", updatedAt=" + (remoteBackup == null ? 0L : remoteBackup.optLong("updated_at", 0L)));
        if (remoteBackup != null) {
            CloudSyncStore.rememberBackupMessageId(appContext, remoteBackup.optLong("_message_id", 0L));
        }
        boolean forcedRestore = forceCloudRestore;
        forceCloudRestore = false;
        boolean restored = forcedRestore
                ? CloudSyncStore.importBackup(appContext, remoteBackup, true)
                : CloudSyncStore.importIfNewer(appContext, remoteBackup);
        if (remoteBackup != null) {
            CloudSyncStore.rememberRemoteBackup(appContext, remoteBackup);
            notifyCloudSyncStatus(R.string.profile_cloud_backup_found);
        }
        if (restored) {
            requestedHistoryChatIds.clear();
            loadGroups();
            loadSelectedGroupsHistory();
            notifyGroups();
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
            notifyCloudSyncStatus(R.string.profile_cloud_restore_done);
        } else if (forcedRestore) {
            notifyCloudSyncStatus(R.string.profile_cloud_restore_empty);
        }
        if (!forcedRestore && CloudSyncStore.shouldPushLocalBackup(appContext, remoteBackup)) {
            scheduleCloudBackup();
        }
    }

    private void handleIncomingCloudSyncMessage(JSONObject message) {
        if (message.optJSONObject("sending_state") != null) {
            return;
        }
        long chatId = message.optLong("chat_id", 0L);
        if (chatId == 0L || (chatId != selfChatId && chatId != selfUserId)) {
            return;
        }
        JSONObject content = message.optJSONObject("content");
        JSONObject text = content == null ? null : content.optJSONObject("text");
        if (text == null || !text.optString("text", "").contains(CloudSyncStore.MARKER)) {
            return;
        }
        Log.d(TAG, "incoming cloud sync message id=" + message.optLong("id", 0L));
        scheduleCloudPull();
    }

    private synchronized void scheduleCloudPull() {
        if (cloudPullScheduled) {
            return;
        }
        cloudPullScheduled = true;
        cloudSyncHandler.postDelayed(() -> {
            cloudPullScheduled = false;
            if (appContext == null || state != State.READY || selfChatId == 0L) {
                return;
            }
            cloudSyncRequested = false;
            cloudHistoryFallbackRequested = false;
            requestCloudBackup();
        }, CLOUD_PULL_DEBOUNCE_MS);
    }

    private void sendCloudBackup() {
        Log.d(TAG, "sendCloudBackup selfChatId=" + selfChatId
                + ", messageId=" + CloudSyncStore.getBackupMessageId(appContext));
        if (selfChatId == 0L || pendingCloudExpectedMessages > 0) {
            return;
        }
        sendNewCloudBackup();
    }

    private void sendNewCloudBackup() {
        Log.d(TAG, "sendNewCloudBackup selfChatId=" + selfChatId);
        try {
            List<String> chunks = CloudSyncStore.exportBackupTextChunks(appContext);
            pendingCloudMessageIds.clear();
            pendingCloudExpectedMessages = chunks.size();
            pendingCloudConfirmedMessages = 0;
            pendingCloudBackupFailed = false;
            for (String chunk : chunks) {
                JSONObject inputMessage = createCloudBackupInputMessage(chunk);
                send(new JSONObject()
                        .put("@type", "sendMessage")
                        .put("chat_id", selfChatId)
                        .put("input_message_content", inputMessage)
                        .put("@extra", "cloud_sync_send"));
            }
            lastCloudBackupSentAt = System.currentTimeMillis();
        } catch (JSONException exception) {
            notifyError(exception.getMessage());
        }
    }

    private JSONObject createCloudBackupInputMessage(String text) throws JSONException {
        JSONObject formattedText = new JSONObject()
                .put("@type", "formattedText")
                .put("text", text)
                .put("entities", new JSONArray());
        return new JSONObject()
                .put("@type", "inputMessageText")
                .put("text", formattedText)
                .put("clear_draft", true);
    }

    private void handleCloudSyncSentMessage(JSONObject message) {
        long messageId = message.optLong("id", 0L);
        boolean pending = message.optJSONObject("sending_state") != null;
        Log.d(TAG, "handleCloudSyncSentMessage id=" + messageId + ", pending=" + pending);
        if (messageId <= 0L) {
            return;
        }
        if (pending) {
            pendingCloudMessageIds.add(messageId);
            return;
        }
        confirmCloudBackupPart(messageId);
    }

    private void handleMessageSendSucceeded(JSONObject result) {
        long oldMessageId = result.optLong("old_message_id", 0L);
        JSONObject message = result.optJSONObject("message");
        long newMessageId = message == null ? 0L : message.optLong("id", 0L);
        Log.d(TAG, "handleMessageSendSucceeded old=" + oldMessageId + ", new=" + newMessageId);
        if (!pendingCloudMessageIds.remove(oldMessageId)) {
            return;
        }
        confirmCloudBackupPart(newMessageId);
    }

    private void handleMessageSendFailed(JSONObject result) {
        long oldMessageId = result.optLong("old_message_id", 0L);
        JSONObject error = result.optJSONObject("error");
        Log.d(TAG, "handleMessageSendFailed old=" + oldMessageId
                + ", error=" + (error == null ? result.optString("error_message") : error.toString()));
        if (pendingCloudMessageIds.remove(oldMessageId)) {
            failPendingCloudBackup();
        }
    }

    private synchronized void confirmCloudBackupPart(long messageId) {
        Log.d(TAG, "confirmCloudBackupPart id=" + messageId
                + ", confirmed=" + pendingCloudConfirmedMessages
                + ", expected=" + pendingCloudExpectedMessages
                + ", failed=" + pendingCloudBackupFailed);
        CloudSyncStore.rememberBackupMessageId(appContext, messageId);
        if (pendingCloudExpectedMessages <= 0) {
            return;
        }
        pendingCloudConfirmedMessages++;
        if (!pendingCloudBackupFailed && pendingCloudConfirmedMessages >= pendingCloudExpectedMessages) {
            pendingCloudExpectedMessages = 0;
            pendingCloudConfirmedMessages = 0;
            CloudSyncStore.markPushed(appContext);
            if (pendingManualBackupConfirmation) {
                pendingManualBackupConfirmation = false;
                notifyCloudSyncStatus(R.string.profile_cloud_backup_sent);
            }
        }
    }

    private synchronized void failPendingCloudBackup() {
        pendingCloudBackupFailed = true;
        pendingCloudExpectedMessages = 0;
        pendingCloudConfirmedMessages = 0;
        pendingCloudMessageIds.clear();
        if (pendingManualBackupConfirmation) {
            pendingManualBackupConfirmation = false;
            notifyCloudSyncStatus(R.string.profile_cloud_backup_failed);
        }
        if (appContext != null && CloudSyncStore.hasPendingPush(appContext)) {
            scheduleCloudBackup();
        }
    }

    private synchronized void scheduleCloudBackup() {
        if (cloudBackupScheduled) {
            return;
        }
        cloudBackupScheduled = true;
        long elapsedSinceLastSend = System.currentTimeMillis() - lastCloudBackupSentAt;
        long intervalDelay = Math.max(0L, CLOUD_BACKUP_MIN_INTERVAL_MS - elapsedSinceLastSend);
        long delay = Math.max(CLOUD_BACKUP_DEBOUNCE_MS, intervalDelay);
        cloudSyncHandler.postDelayed(() -> {
            cloudBackupScheduled = false;
            if (appContext != null
                    && state == State.READY
                    && selfChatId != 0L
                    && CloudSyncStore.hasPendingPush(appContext)) {
                sendCloudBackup();
            }
        }, delay);
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

    private void notifyCloudSyncStatus(int messageResource) {
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onCloudSyncStatus(messageResource);
        }
    }

    private static final class InterestHistorySearch {
        final long interestId;
        final long chatId;
        final String term;
        final String extra;
        long lastFromMessageId;

        InterestHistorySearch(long interestId, long chatId, String term, String extra) {
            this.interestId = interestId;
            this.chatId = chatId;
            this.term = term;
            this.extra = extra;
        }
    }
}
