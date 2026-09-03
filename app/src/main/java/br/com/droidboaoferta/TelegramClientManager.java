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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        default void onError(String message) {
        }

        default void onAccountChanged() {
        }

        default void onCloudSyncStatus(int messageResource) {
        }
    }

    interface MessageListener {
        void onNewMessage(long chatId, long messageId, long messageDate, String sourceTitle,
                          TelegramMessagePayload payload);

        default void onHistoricalMessage(long interestId, long chatId, long messageId,
                                         long messageDate, String sourceTitle,
                                         TelegramMessagePayload payload) {
        }

        default void onQualityHistoryMessage(long chatId, long messageId, long messageDate,
                                             String sourceTitle, TelegramMessagePayload payload) {
        }
    }

    interface LowestPriceCallback {
        default void onPriceFound(double lowestPrice) {
        }

        void onCompleted(double lowestPrice, int statusMessageResource);
    }

    interface MessageLinkCallback {
        void onResolved(String link);
    }

    interface MessageValidationCallback {
        void onResolved(JSONObject message);
    }

    private static final TelegramClientManager INSTANCE = new TelegramClientManager();
    // A burst of offers is kept local and coalesced into one small backup.
    private static final long CLOUD_BACKUP_DEBOUNCE_MS = 2_000L;
    private static final long CLOUD_BACKUP_MIN_INTERVAL_MS = 5_000L;
    private static final long CLOUD_BACKUP_PART_DELAY_MS = 1_200L;
    private static final long CLOUD_BACKUP_PART_TIMEOUT_MS = 30_000L;
    private static final long CLOUD_PULL_DEBOUNCE_MS = 1500L;
    private static final long CLOUD_PULL_TIMEOUT_MS = 45_000L;
    private static final long RUNTIME_STABLE_MS = 30_000L;
    private static final int RECOVERY_PAGE_SIZE = 50;
    private static final int RECOVERY_MAX_PAGES_PER_GROUP = 8;
    private static final long RECOVERY_REQUEST_DELAY_MS = 650L;
    static final String ACTION_CLOUD_SYNC_CHANGED =
            BuildConfig.APPLICATION_ID + ".action.CLOUD_SYNC_CHANGED";

    static TelegramClientManager getInstance() {
        return INSTANCE;
    }

    private final Map<Long, JSONObject> chats = new HashMap<>();
    private final Map<String, InterestHistorySearch> interestHistorySearches = new HashMap<>();
    private final Map<String, LowestPriceSearch> lowestPriceSearches = new HashMap<>();
    private final Map<String, QualityHistorySearch> qualityHistorySearches = new HashMap<>();
    private final Map<Long, LowestPriceBatch> lowestPriceBatches = new HashMap<>();
    private final Map<String, CachedLowestPriceResult> cachedLowestPriceResults = new HashMap<>();
    private final Map<String, MessageLinkCallback> pendingMessageLinkCallbacks = new HashMap<>();
    private final Map<String, MessageValidationCallback> pendingMessageValidations = new HashMap<>();
    private final OfferLinkRevalidationGate storedOfferLinkGate = new OfferLinkRevalidationGate();
    private boolean validationBroadcastPending;
    private final Set<Long> groupChatIds = new HashSet<>();
    private final Set<Long> pendingCloudMessageIds = new HashSet<>();
    private final Set<Long> confirmedCloudMessageIds = new HashSet<>();
    private final Set<Long> backupPruneKeepMessageIds = new HashSet<>();
    private final List<Long> recoveryChatIds = new ArrayList<>();
    private final List<String> pendingCloudChunks = new ArrayList<>();
    private final List<String> pendingConfigurationDeltas = new ArrayList<>();
    private final Handler cloudSyncHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService cloudBackupExecutor = Executors.newSingleThreadExecutor();
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
    private long nextMessageLinkRequestId;
    private volatile boolean cloudSyncRequested;
    private volatile boolean cloudHistoryFallbackRequested;
    private volatile boolean forceCloudRestore;
    private volatile boolean pendingManualBackup;
    private volatile boolean pendingManualBackupConfirmation;
    private volatile boolean pendingManualRestore;
    private volatile boolean pendingManualBackupDeletion;
    private volatile boolean pendingGroupsDelta;
    private volatile boolean pendingConfigurationRefresh;
    private volatile boolean selfChatRequested;
    private volatile boolean initialCloudRestorePending;
    private volatile boolean reconnectRequested;
    private volatile boolean cloudBackupScheduled;
    private Runnable cloudBackupWakeup;
    private long cloudBackupWakeupToken;
    private boolean cloudBackupPausedForRetry;
    private volatile boolean cloudPullScheduled;
    private volatile boolean cloudPullAgainRequested;
    private volatile boolean backupPruneRequested;
    private volatile boolean backupPreparationRunning;
    private volatile long pendingCloudBackupUpdatedAt;
    private final CloudBackupRetryGate cloudBackupRetryGate = new CloudBackupRetryGate();
    private volatile long lastCloudBackupCompletedElapsed;
    private volatile long cloudBackupGeneration;
    private volatile long cloudBackupChunkToken;
    private volatile int pendingCloudExpectedMessages;
    private volatile int pendingCloudConfirmedMessages;
    private volatile int pendingCloudNextChunkIndex;
    private volatile int cloudBackupRetryAttempt;
    private volatile boolean pendingCloudBackupFailed;
    private volatile boolean cloudBackupChunkAwaitingResult;
    private volatile boolean pendingCloudBackupIsRankingDelta;
    private boolean started;
    private volatile boolean receiverRunning;
    private volatile int clientId;
    private long interestHistoryGeneration;
    private long lowestPriceGeneration;
    private volatile long cloudPullGeneration;
    private volatile long cloudPullTimeoutToken;
    private int cloudPullRetryAttempt;
    private long cloudPullRetryNotBeforeElapsed;
    private volatile long runtimeGeneration;
    private int runtimeRestartAttempts;
    private boolean recoveryRequested;
    private boolean recoveryRunning;
    private long recoveryChatId;
    private long recoveryCheckpointId;
    private long recoveryFromMessageId;
    private int recoveryPageCount;
    private Context appContext;

    private TelegramClientManager() {
    }

    boolean isCloudSyncInProgress() {
        return cloudSyncRequested;
    }

    boolean isManualRestoreInProgress() {
        return pendingManualRestore || forceCloudRestore;
    }

    boolean isCloudBackupInProgress() {
        return backupPreparationRunning
                || pendingCloudExpectedMessages > 0
                || pendingManualBackupConfirmation;
    }

    synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        restoreCloudBackupPause();
        Log.d(TAG, "start called, started=" + started + ", state=" + state);
        if (started) {
            notifyState();
            notifyGroups();
            return;
        }
        started = true;
        receiverRunning = true;
        long generation = ++runtimeGeneration;

        if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isEmpty()) {
            changeState(State.MISSING_CREDENTIALS);
            return;
        }

        Thread receiverThread = new Thread(
                () -> runReceiver(generation),
                "boa-oferta-tdlib"
        );
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    synchronized void reconnect(Context context) {
        appContext = context.getApplicationContext();
        restoreCloudBackupPause();
        runtimeRestartAttempts = 0;
        reconnectRequested = true;
        changeState(State.STARTING);
        if (started && clientId != 0) {
            try {
                send(new JSONObject().put("@type", "close").put("@extra", "reconnect_close"));
            } catch (JSONException ignored) {
            }
            cloudSyncHandler.postDelayed(() -> {
                if (reconnectRequested) {
                    restartAfterClosedRuntime();
                }
            }, 1500L);
            return;
        }
        restartAfterClosedRuntime();
    }

    synchronized void requestMissedMessageRecovery() {
        recoveryRequested = true;
        startMissedMessageRecoveryIfReady();
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

    synchronized void refreshQualityHistorySince(long sinceMillis) {
        if (state != State.READY) {
            return;
        }
        Set<String> selectedGroups = appContext.getSharedPreferences(
                "telegram_preferences", Context.MODE_PRIVATE).getStringSet(
                "selected_groups", Collections.emptySet());
        for (String selectedGroup : selectedGroups) {
            try {
                long chatId = Long.parseLong(selectedGroup);
                String extra = "quality_history:" + chatId;
                QualityHistorySearch search = new QualityHistorySearch(chatId, sinceMillis, extra);
                qualityHistorySearches.put(extra, search);
                requestQualityHistoryPage(search, 0L);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    synchronized void findLowestObservedPrice(String term, LowestPriceCallback callback) {
        if (callback == null) {
            return;
        }
        String cleanTerm = term == null ? "" : term.trim();
        if (state != State.READY) {
            postLowestPriceResult(callback, Double.NaN, R.string.lowest_price_login_required);
            return;
        }
        Set<String> selectedGroups = appContext
                .getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", Collections.emptySet());
        if (cleanTerm.isEmpty() || selectedGroups.isEmpty()) {
            postLowestPriceResult(callback, Double.NaN, R.string.lowest_price_no_groups);
            return;
        }

        long generation = ++lowestPriceGeneration;
        LowestPriceBatch batch = new LowestPriceBatch(
                generation,
                cleanTerm,
                callback,
                selectedGroups.size()
        );
        lowestPriceBatches.put(generation, batch);
        for (String selectedGroup : selectedGroups) {
            try {
                long chatId = Long.parseLong(selectedGroup);
                String extra = "lowest_price:" + generation + ":" + chatId;
                LowestPriceSearch search = new LowestPriceSearch(batch, extra);
                lowestPriceSearches.put(extra, search);
                send(new JSONObject()
                        .put("@type", "searchChatMessages")
                        .put("chat_id", chatId)
                        .put("query", cleanTerm)
                        .put("sender_id", JSONObject.NULL)
                        .put("from_message_id", 0)
                        .put("offset", 0)
                        .put("limit", 100)
                        .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                        .put("message_thread_id", 0)
                        .put("@extra", extra));
            } catch (Exception exception) {
                completeLowestPricePart(batch);
            }
        }
    }

    synchronized void resolveMessageLink(long chatId, long messageId, MessageLinkCallback callback) {
        if (callback == null) {
            return;
        }
        if (state != State.READY || chatId == 0L || messageId <= 0L) {
            callback.onResolved("");
            return;
        }
        String extra = "message_link:" + (++nextMessageLinkRequestId);
        pendingMessageLinkCallbacks.put(extra, callback);
        cloudSyncHandler.postDelayed(() -> {
            MessageLinkCallback timedOut;
            synchronized (TelegramClientManager.this) {
                timedOut = pendingMessageLinkCallbacks.remove(extra);
            }
            if (timedOut != null) timedOut.onResolved("");
        }, 20_000L);
        try {
            send(new JSONObject()
                    .put("@type", "getMessageLink")
                    .put("chat_id", chatId)
                    .put("message_id", messageId)
                    .put("media_timestamp", 0)
                    .put("for_album", false)
                    .put("in_message_thread", false)
                    .put("@extra", extra));
        } catch (JSONException exception) {
            pendingMessageLinkCallbacks.remove(extra);
            callback.onResolved("");
        }
    }

    synchronized void validateMessageLink(String link, long chatId, long messageId,
                                         MessageValidationCallback callback) {
        if (state != State.READY || !started || link == null || link.trim().isEmpty()) {
            callback.onResolved(null);
            return;
        }
        String extra = "validate_message_link:" + (++nextMessageLinkRequestId);
        pendingMessageValidations.put(extra,
                response -> callback.onResolved(TelegramLinkValidation.readableMessage(response, chatId, messageId)));
        cloudSyncHandler.postDelayed(() -> completeMessageValidation(extra, null), 20_000L);
        try {
            send(new JSONObject().put("@type", "getMessageLinkInfo").put("url", link).put("@extra", extra));
        } catch (JSONException ignored) {
            completeMessageValidation(extra, null);
        }
    }

    private void completeMessageValidation(String extra, JSONObject response) {
        MessageValidationCallback callback;
        synchronized (this) {
            callback = pendingMessageValidations.remove(extra);
        }
        if (callback != null) callback.onResolved(response);
    }

    synchronized void revalidateStoredOfferLinks() {
        if (state != State.READY || appContext == null) return;
        OfferRepository repository = new OfferRepository(appContext);
        List<ObservedOffer> offers = new ArrayList<>(repository.getRecentForValidation());
        offers.addAll(repository.getArchived());
        OfferLinkValidationStore validation = new OfferLinkValidationStore(appContext);
        boolean visibilityChanged = false;
        for (ObservedOffer offer : offers) {
            if (!OfferLinkValidationStore.requiresValidation(offer)
                    || !storedOfferLinkGate.begin(offer.getId(), SystemClock.elapsedRealtime())) continue;
            visibilityChanged |= validation.setValidated(offer, false);
            validateMessageLink(offer.getTelegramPostLink(), 0L, 0L,
                    message -> handleStoredOfferValidation(offer, message));
        }
        if (visibilityChanged) broadcastOfferValidationChanged();
    }

    private synchronized void handleStoredOfferValidation(ObservedOffer offer, JSONObject message) {
        boolean readable = message != null;
        storedOfferLinkGate.finish(offer.getId(), readable, SystemClock.elapsedRealtime());
        boolean removedWrongEdition = false;
        if (readable) {
            String text = TelegramMessagePayload.fromMessage(message).getText();
            if (OfferTextParser.hasDifferentFlipEdition(text, offer.getInterest())) {
                new GroupSpeedRepository(appContext).invalidateOffer(offer);
                OfferRepository repository = new OfferRepository(appContext);
                repository.trash(offer.getId());
                repository.trashArchived(offer.getId());
                readable = false;
                removedWrongEdition = true;
            }
        }
        // Failed checks only hide the card; they do not erase offers or ranking history.
        boolean visibilityChanged = new OfferLinkValidationStore(appContext).setValidated(offer, readable);
        if (visibilityChanged || removedWrongEdition) broadcastOfferValidationChanged();
    }

    private synchronized void broadcastOfferValidationChanged() {
        if (validationBroadcastPending) return;
        validationBroadcastPending = true;
        cloudSyncHandler.postDelayed(() -> {
            synchronized (TelegramClientManager.this) {
                validationBroadcastPending = false;
            }
            appContext.sendBroadcast(new android.content.Intent(OfferMonitor.ACTION_OFFER_FOUND)
                    .setPackage(appContext.getPackageName()));
        }, 250L);
    }

    synchronized boolean publishCachedLowestPriceMatches(long interestId, String term,
                                                          double maximumPrice) {
        MessageListener currentListener = messageListener;
        CachedLowestPriceResult cached = cachedLowestPriceResults.get(
                OfferTextParser.normalize(term)
        );
        if (currentListener == null || cached == null
                || System.currentTimeMillis() - cached.createdAt > 10L * 60L * 1000L) {
            return false;
        }
        int publishedCount = 0;
        long now = System.currentTimeMillis();
        for (LowestPriceCandidate candidate : cached.candidates) {
            if (!OfferTextParser.isWithinValidatedRange(
                    candidate.price,
                    cached.lowestPlausiblePrice,
                    maximumPrice
            ) || !OfferEligibility.isRecent(candidate.messageDate, now)
                    || !OfferEligibility.hasUsableLink(
                    candidate.payload.findBestLink(term))) {
                continue;
            }
            JSONObject chat = chats.get(candidate.chatId);
            String sourceTitle = chat == null
                    ? appContext.getString(R.string.telegram_source_unknown)
                    : chat.optString("title", appContext.getString(R.string.telegram_source_unknown));
            currentListener.onHistoricalMessage(
                    interestId,
                    candidate.chatId,
                    candidate.messageId,
                    candidate.messageDate,
                    sourceTitle,
                    candidate.payload
            );
            publishedCount++;
        }
        Log.d(TAG, "validated price batch candidates=" + cached.candidates.size()
                + ", published=" + publishedCount
                + ", floor=" + cached.lowestPlausiblePrice
                + ", ceiling=" + maximumPrice);
        return true;
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
        // Always merge the newest shared state before a device is allowed to publish.
        scheduleCloudPull();
    }

    synchronized void sendSelectedGroupsDelta() {
        if (appContext == null || state != State.READY) return;
        if (selfChatId == 0L) {
            pendingGroupsDelta = true;
            requestSelfChat();
            return;
        }
        try {
            send(new JSONObject()
                    .put("@type", "sendMessage")
                    .put("chat_id", selfChatId)
                    .put("input_message_content", createCloudBackupInputMessage(
                            CloudSyncStore.exportSelectedGroupsDelta(appContext)))
                    .put("@extra", "groups_delta_send"));
            CloudSyncStore.rememberConfigurationSynced(appContext);
        } catch (JSONException ignored) {
        }
    }

    synchronized void sendConfigurationDelta(String deltaText) {
        if (deltaText == null || deltaText.trim().isEmpty()) return;
        if (appContext == null || state != State.READY) {
            queueConfigurationDelta(deltaText);
            return;
        }
        if (selfChatId == 0L) {
            queueConfigurationDelta(deltaText);
            requestSelfChat();
            return;
        }
        try {
            send(new JSONObject()
                    .put("@type", "sendMessage")
                    .put("chat_id", selfChatId)
                    .put("input_message_content", createCloudBackupInputMessage(deltaText))
                    .put("@extra", "config_delta_send"));
            CloudSyncStore.rememberConfigurationSynced(appContext);
        } catch (JSONException ignored) {
        }
    }

    private void queueConfigurationDelta(String deltaText) {
        if (pendingConfigurationDeltas.size() >= 20) {
            pendingConfigurationDeltas.remove(0);
        }
        pendingConfigurationDeltas.add(deltaText);
    }

    void refreshCloudBackupSoon() {
        Log.d(TAG, "refreshCloudBackupSoon state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            return;
        }
        if (!CloudSyncStore.shouldRefreshRemote(appContext)) {
            return;
        }
        refreshCloudConfigurationSoon();
    }

    void refreshCloudConfigurationSoon() {
        Log.d(TAG, "refreshCloudConfigurationSoon state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null) {
            return;
        }
        if (state != State.READY) {
            pendingConfigurationRefresh = true;
            return;
        }
        if (selfChatId == 0L) {
            pendingConfigurationRefresh = true;
            requestSelfChat();
            return;
        }
        pendingConfigurationRefresh = false;
        CloudSyncStore.rememberRemoteRefreshRequested(appContext);
        if (cloudSyncRequested) {
            cloudPullAgainRequested = true;
            return;
        }
        cloudHistoryFallbackRequested = false;
        requestCloudBackup();
    }

    void backupCloudNow() {
        Log.d(TAG, "backupCloudNow state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            notifyCloudSyncStatus(R.string.profile_manual_backup_login_required);
            return;
        }
        CloudSyncStore.markManualBackupRequested(appContext);
        pendingManualBackupConfirmation = true;
        notifyCloudSyncStatus(R.string.profile_manual_backup_preparing);
        if (selfChatId == 0L) {
            pendingManualBackup = true;
            requestSelfChat();
            return;
        }
        sendCloudBackup();
    }

    synchronized void cancelCloudBackup() {
        if (appContext == null) {
            return;
        }
        CloudSyncStore.cancelPendingBackup(appContext);
        pendingManualBackup = false;
        pendingManualBackupConfirmation = false;
        cancelCloudBackupWakeup();
        cloudBackupPausedForRetry = false;
        backupPreparationRunning = false;
        pendingCloudBackupFailed = false;
        pendingCloudExpectedMessages = 0;
        pendingCloudConfirmedMessages = 0;
        pendingCloudMessageIds.clear();
        confirmedCloudMessageIds.clear();
        pendingCloudChunks.clear();
        pendingCloudNextChunkIndex = 0;
        cloudBackupChunkAwaitingResult = false;
        pendingCloudBackupUpdatedAt = 0L;
        pendingCloudBackupIsRankingDelta = false;
        // Cancelling work does not cancel a server-imposed rate limit.
        cloudBackupGeneration++;
        cloudBackupChunkToken++;
        notifyCloudSyncStatus(R.string.profile_manual_backup_cancelled);
        appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                .setPackage(appContext.getPackageName()));
    }

    void restoreCloudBackupNow() {
        Log.d(TAG, "restoreCloudBackupNow state=" + state + ", selfChatId=" + selfChatId);
        if (appContext == null || state != State.READY) {
            notifyCloudSyncStatus(R.string.profile_manual_restore_login_required);
            return;
        }
        if (selfChatId == 0L) {
            pendingManualRestore = true;
            notifyCloudSyncStatus(R.string.profile_manual_restore_preparing);
            requestSelfChat();
            return;
        }
        forceCloudRestore = true;
        notifyCloudSyncStatus(R.string.profile_manual_restore_searching);
        if (cloudSyncRequested) {
            cloudPullAgainRequested = true;
        } else {
            requestCloudBackup();
        }
    }

    void clearCloudBackupsNow() {
        if (appContext == null || state != State.READY) {
            notifyCloudSyncStatus(R.string.profile_manual_backup_login_required);
            return;
        }
        notifyCloudSyncStatus(R.string.profile_manual_backup_clearing);
        if (selfChatId == 0L) {
            pendingManualBackupDeletion = true;
            requestSelfChat();
            return;
        }
        requestManualBackupClear();
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

    private void runReceiver(long generation) {
        try {
            JsonClient.setLogMessageHandler(1, (verbosityLevel, message) -> {
                // Logs detalhados da sessão não são persistidos para preservar a privacidade.
            });
            clientId = JsonClient.createClientId();
            JsonClient.send(clientId, new JSONObject().put("@type", "getAuthorizationState").toString());
            while (receiverRunning
                    && runtimeGeneration == generation
                    && !Thread.currentThread().isInterrupted()) {
                String result = JsonClient.receive(1.0);
                if (runtimeGeneration != generation) {
                    break;
                }
                if (result != null) {
                    try {
                        handleResult(new JSONObject(result));
                    } catch (Exception exception) {
                        Log.e(TAG, "Could not process TDLib update", exception);
                        notifyError(exception.getMessage() == null
                                ? appContext.getString(R.string.telegram_unknown_error)
                                : exception.getMessage());
                    }
                }
            }
        } catch (Throwable throwable) {
            handleReceiverFailure(throwable, generation);
        }
    }

    private void handleReceiverFailure(Throwable throwable, long generation) {
        if (runtimeGeneration != generation) {
            return;
        }
        Log.e(TAG, "TDLib receiver stopped", throwable);
        notifyError(throwable.getMessage() == null
                ? appContext.getString(R.string.telegram_native_error)
                : throwable.getMessage());
        long restartDelay;
        synchronized (this) {
            closeRuntime();
            runtimeRestartAttempts++;
            recoveryRequested = true;
            restartDelay = Math.min(60_000L, 1_500L * runtimeRestartAttempts);
            changeState(State.STARTING);
        }
        cloudSyncHandler.postDelayed(() -> {
            synchronized (TelegramClientManager.this) {
                if (!started && state == State.STARTING) {
                    start(appContext);
                }
            }
        }, restartDelay);
    }

    private void handleResult(JSONObject result) throws Exception {
        String type = result.optString("@type");
        String extra = result.optString("@extra");
        if (extra.startsWith("validate_message_link:")) {
            completeMessageValidation(extra, result);
            return;
        }
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
        } else if ("messageLink".equals(type) && extra.startsWith("message_link:")) {
            MessageLinkCallback callback = pendingMessageLinkCallbacks.remove(extra);
            if (callback != null) {
                callback.onResolved(result.optString("link", ""));
            }
        } else if ("chats".equals(type) && result.optString("@extra").startsWith("load_groups_")) {
            publishGroups(result.getJSONArray("chat_ids"));
        } else if ("foundChatMessages".equals(type)
                && result.optString("@extra").startsWith("interest_history:")) {
            handleInterestHistoryPage(result);
        } else if ("foundChatMessages".equals(type)
                && result.optString("@extra").startsWith("lowest_price:")) {
            handleLowestPriceMessages(result);
        } else if ("messages".equals(type)
                && result.optString("@extra").startsWith("quality_history:")) {
            handleQualityHistoryPage(result);
        } else if ("messages".equals(type)
                && result.optString("@extra").startsWith("monitor_recovery:")) {
            handleMissedMessageRecovery(result);
        } else if ("foundChatMessages".equals(type)
                && "backup_prune_search".equals(result.optString("@extra"))) {
            handleBackupPruneSearch(result.optJSONArray("messages"));
        } else if ("foundChatMessages".equals(type)
                && "backup_clear_search".equals(result.optString("@extra"))) {
            handleBackupClearSearch(result.optJSONArray("messages"));
        } else if ("ok".equals(type)
                && "backup_prune_delete".equals(result.optString("@extra"))) {
            backupPruneRequested = false;
            backupPruneKeepMessageIds.clear();
        } else if ("ok".equals(type)
                && "backup_clear_delete".equals(result.optString("@extra"))) {
            finishManualBackupClear(true);
        } else if ("messages".equals(type) && result.optString("@extra").startsWith("cloud_sync_")) {
            handleCloudSyncMessages(result.optJSONArray("messages"), result.optString("@extra"));
        } else if ("foundChatMessages".equals(type) && result.optString("@extra").startsWith("cloud_sync_")) {
            handleCloudSyncMessages(result.optJSONArray("messages"), result.optString("@extra"));
        } else if ("user".equals(type) && "account_me".equals(result.optString("@extra"))) {
            publishAccount(result);
        } else if ("error".equals(type)) {
            if (result.optString("@extra").startsWith("message_link:")) {
                MessageLinkCallback callback = pendingMessageLinkCallbacks.remove(result.optString("@extra"));
                if (callback != null) {
                    callback.onResolved("");
                }
                return;
            }
            if (result.optString("@extra").startsWith("lowest_price:")) {
                handleLowestPriceError(result.optString("@extra"));
                return;
            }
            if (result.optString("@extra").startsWith("interest_history:")) {
                interestHistorySearches.remove(result.optString("@extra"));
            }
            if (result.optString("@extra").startsWith("quality_history:")) {
                qualityHistorySearches.remove(result.optString("@extra"));
                return;
            }
            if (result.optString("@extra").startsWith("monitor_recovery:")) {
                cloudSyncHandler.postDelayed(this::requestNextRecoveryGroup,
                        RECOVERY_REQUEST_DELAY_MS);
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_search")) {
                requestCloudSyncHistoryFallback(readCloudPullGeneration(result.optString("@extra")));
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_send")) {
                handleCloudBackupSendError(result, result.optString("@extra"));
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_self_chat")) {
                selfChatRequested = false;
                cloudSyncHandler.postDelayed(() -> {
                    if (state == State.READY && selfChatId == 0L) {
                        requestSelfChat();
                    }
                }, CloudSyncRetryPolicy.delayForAttempt(1));
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_history")) {
                finishCloudPull(readCloudPullGeneration(result.optString("@extra")), false);
                return;
            }
            if (result.optString("@extra").startsWith("cloud_sync_")) {
                return;
            }
            if (result.optString("@extra").startsWith("backup_prune_")) {
                backupPruneRequested = false;
                backupPruneKeepMessageIds.clear();
                return;
            }
            if (result.optString("@extra").startsWith("backup_clear_")) {
                finishManualBackupClear(false);
                return;
            }
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

        long messageId = message.optLong("id");
        MonitorCheckpointStore.markProcessed(appContext, chatId, messageId);

        TelegramMessagePayload payload = TelegramMessagePayload.fromMessage(message);
        if (payload.getText().isEmpty()) {
            return;
        }

        JSONObject chat = chats.get(chatId);
        String sourceTitle = chat == null ? appContext.getString(R.string.telegram_source_unknown)
                : chat.optString("title", appContext.getString(R.string.telegram_source_unknown));
        MonitorStatusStore.markSelectedMessage(appContext);
        currentListener.onNewMessage(
                chatId,
                messageId,
                message.optLong("date") * 1000L,
                sourceTitle,
                payload
        );
    }

    private synchronized void startMissedMessageRecoveryIfReady() {
        if (!recoveryRequested || recoveryRunning || state != State.READY || groups.isEmpty()) {
            return;
        }
        Set<String> selected = appContext.getSharedPreferences("telegram_preferences", Context.MODE_PRIVATE)
                .getStringSet("selected_groups", Collections.emptySet());
        recoveryChatIds.clear();
        for (TelegramGroup group : groups) {
            if (selected.contains(Long.toString(group.getId()))
                    && MonitorCheckpointStore.getLastMessageId(appContext, group.getId()) > 0L) {
                recoveryChatIds.add(group.getId());
            }
        }
        recoveryRequested = false;
        if (recoveryChatIds.isEmpty()) return;
        recoveryRunning = true;
        requestNextRecoveryGroup();
    }

    private synchronized void requestNextRecoveryGroup() {
        if (recoveryChatIds.isEmpty()) {
            recoveryRunning = false;
            return;
        }
        recoveryChatId = recoveryChatIds.remove(0);
        recoveryCheckpointId = MonitorCheckpointStore.getLastMessageId(appContext, recoveryChatId);
        recoveryFromMessageId = 0L;
        recoveryPageCount = 0;
        requestRecoveryPage();
    }

    private void requestRecoveryPage() {
        try {
            send(new JSONObject()
                    .put("@type", "getChatHistory")
                    .put("chat_id", recoveryChatId)
                    .put("from_message_id", recoveryFromMessageId)
                    .put("offset", 0)
                    .put("limit", RECOVERY_PAGE_SIZE)
                    .put("only_local", false)
                    .put("@extra", "monitor_recovery:" + recoveryChatId));
        } catch (JSONException ignored) {
            cloudSyncHandler.postDelayed(this::requestNextRecoveryGroup, RECOVERY_REQUEST_DELAY_MS);
        }
    }

    private synchronized void handleMissedMessageRecovery(JSONObject result) {
        if (!recoveryRunning) return;
        JSONArray messages = result.optJSONArray("messages");
        long oldestId = Long.MAX_VALUE;
        int count = messages == null ? 0 : messages.length();
        List<JSONObject> missed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) continue;
            long id = message.optLong("id");
            if (id > 0L) oldestId = Math.min(oldestId, id);
            if (id > recoveryCheckpointId) missed.add(message);
        }
        missed.sort(Comparator.comparingLong(message -> message.optLong("id")));
        for (JSONObject message : missed) publishMessage(message);

        recoveryPageCount++;
        boolean reachedCheckpoint = oldestId <= recoveryCheckpointId;
        boolean hasOlderPage = count >= RECOVERY_PAGE_SIZE && oldestId != Long.MAX_VALUE
                && oldestId != recoveryFromMessageId;
        if (!reachedCheckpoint && hasOlderPage
                && recoveryPageCount < RECOVERY_MAX_PAGES_PER_GROUP) {
            recoveryFromMessageId = oldestId;
            cloudSyncHandler.postDelayed(this::requestRecoveryPage, RECOVERY_REQUEST_DELAY_MS);
            return;
        }
        cloudSyncHandler.postDelayed(this::requestNextRecoveryGroup, RECOVERY_REQUEST_DELAY_MS);
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

    private synchronized void handleLowestPriceMessages(JSONObject result) {
        String extra = result.optString("@extra");
        LowestPriceSearch search = lowestPriceSearches.remove(extra);
        if (search == null) {
            return;
        }
        JSONArray messages = result.optJSONArray("messages");
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                TelegramMessagePayload payload = TelegramMessagePayload.fromMessage(message);
                String text = payload.getText();
                long messageDate = message.optLong("date", 0L) * 1000L;
                String offerLink = payload.findBestLink(search.batch.term);
                if (text.isEmpty() || !OfferTextParser.matchesInterest(text, search.batch.term)) {
                    continue;
                }
                if (!OfferEligibility.isRecent(messageDate, System.currentTimeMillis())
                        || !OfferEligibility.hasUsableLink(offerLink)) {
                    continue;
                }
                double price = OfferTextParser.extractPriceForInterest(text, search.batch.term);
                if (!Double.isNaN(price)
                        && OfferTextParser.isPlausiblePriceForInterest(price, search.batch.term)) {
                    search.batch.observedPrices.add(price);
                    search.batch.candidates.add(new LowestPriceCandidate(
                            message.optLong("chat_id", 0L),
                            message.optLong("id", 0L),
                            messageDate,
                            payload,
                            price
                    ));
                }
            }
        }
        double plausibleLowest = OfferTextParser.selectPlausibleLowest(search.batch.observedPrices);
        if (search.batch.observedPrices.size() >= 2
                && plausibleLowest < search.batch.lastReportedPrice) {
            search.batch.lastReportedPrice = plausibleLowest;
            double currentLowest = plausibleLowest;
            cloudSyncHandler.post(() -> search.batch.callback.onPriceFound(currentLowest));
        }
        completeLowestPricePart(search.batch);
    }

    private synchronized void handleLowestPriceError(String extra) {
        LowestPriceSearch search = lowestPriceSearches.remove(extra);
        if (search != null) {
            completeLowestPricePart(search.batch);
        }
    }

    private void completeLowestPricePart(LowestPriceBatch batch) {
        batch.pendingChats--;
        if (batch.pendingChats > 0) {
            return;
        }
        lowestPriceBatches.remove(batch.generation);
        batch.lowestPrice = OfferTextParser.selectPlausibleLowest(batch.observedPrices);
        Log.d(TAG, "lowest price batch observations=" + batch.observedPrices.size()
                + ", plausible=" + batch.lowestPrice);
        if (!Double.isInfinite(batch.lowestPrice)) {
            cachedLowestPriceResults.put(
                    OfferTextParser.normalize(batch.term),
                    new CachedLowestPriceResult(
                            System.currentTimeMillis(),
                            batch.lowestPrice,
                            new ArrayList<>(batch.candidates)
                    )
            );
        }
        int status = Double.isInfinite(batch.lowestPrice)
                ? R.string.lowest_price_not_found
                : R.string.lowest_price_found;
        postLowestPriceResult(batch.callback, batch.lowestPrice, status);
    }

    private void postLowestPriceResult(LowestPriceCallback callback, double price, int status) {
        cloudSyncHandler.post(() -> callback.onCompleted(price, status));
    }

    private void publishHistoricalMessage(long interestId, JSONObject message) {
        MessageListener currentListener = messageListener;
        if (currentListener == null) {
            return;
        }
        long chatId = message.optLong("chat_id");
        TelegramMessagePayload payload = TelegramMessagePayload.fromMessage(message);
        if (payload.getText().isEmpty()) {
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
                payload
        );
    }

    private synchronized void handleQualityHistoryPage(JSONObject result) {
        String extra = result.optString("@extra");
        QualityHistorySearch search = qualityHistorySearches.get(extra);
        if (search == null) {
            return;
        }
        JSONArray messages = result.optJSONArray("messages");
        long oldestDate = Long.MAX_VALUE;
        long lastMessageId = 0L;
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                if (message == null) {
                    continue;
                }
                long messageDate = message.optLong("date", 0L) * 1000L;
                oldestDate = Math.min(oldestDate, messageDate);
                lastMessageId = message.optLong("id", lastMessageId);
                if (messageDate >= search.sinceMillis) {
                    publishQualityHistoryMessage(message);
                }
            }
        }
        search.loadedMessages += messages == null ? 0 : messages.length();
        if (messages == null || messages.length() < 100 || oldestDate < search.sinceMillis
                || search.loadedMessages >= 400 || lastMessageId == 0L) {
            qualityHistorySearches.remove(extra);
            return;
        }
        requestQualityHistoryPage(search, lastMessageId);
    }

    private void publishQualityHistoryMessage(JSONObject message) {
        MessageListener currentListener = messageListener;
        if (currentListener == null) {
            return;
        }
        long chatId = message.optLong("chat_id");
        TelegramMessagePayload payload = TelegramMessagePayload.fromMessage(message);
        if (payload.getText().isEmpty()) {
            return;
        }
        JSONObject chat = chats.get(chatId);
        String sourceTitle = chat == null ? appContext.getString(R.string.telegram_source_unknown)
                : chat.optString("title", appContext.getString(R.string.telegram_source_unknown));
        currentListener.onQualityHistoryMessage(chatId, message.optLong("id"),
                message.optLong("date", 0L) * 1000L, sourceTitle, payload);
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

    private void requestQualityHistoryPage(QualityHistorySearch search, long fromMessageId) {
        try {
            send(new JSONObject()
                    .put("@type", "getChatHistory")
                    .put("chat_id", search.chatId)
                    .put("from_message_id", fromMessageId)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("only_local", false)
                    .put("@extra", search.extra));
        } catch (JSONException exception) {
            qualityHistorySearches.remove(search.extra);
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
                initialCloudRestorePending = CloudSyncStore.shouldRestoreConfigurationOnConnect(appContext);
                changeState(State.READY);
                scheduleRuntimeStabilityReset(runtimeGeneration);
                loadAccount();
                loadGroups();
                revalidateStoredOfferLinks();
                break;
            case "authorizationStateClosing":
            case "authorizationStateLoggingOut":
                changeState(reconnectRequested ? State.STARTING : State.CLOSED);
                break;
            case "authorizationStateClosed":
                closeRuntime();
                if (reconnectRequested) {
                    restartAfterClosedRuntime();
                } else {
                    changeState(State.CLOSED);
                }
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
        File tdlibDirectory = new File(appContext.getFilesDir(), "tdlib");
        File databaseDirectory = new File(appContext.getFilesDir(), "tdlib/database");
        File filesDirectory = new File(appContext.getFilesDir(), "tdlib/files");
        if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o banco local do Telegram.");
        }
        if (!filesDirectory.exists() && !filesDirectory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta local do Telegram.");
        }
        String databaseKey;
        try {
            databaseKey = TdlibDatabaseKey.getOrCreateBase64(appContext);
        } catch (Exception exception) {
            TdlibDatabaseKey.reset(appContext);
            deleteTdlibRuntimeDirectory(tdlibDirectory);
            if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) {
                throw new IllegalStateException("Não foi possível recriar o banco local do Telegram.");
            }
            if (!filesDirectory.exists() && !filesDirectory.mkdirs()) {
                throw new IllegalStateException("Não foi possível recriar a pasta local do Telegram.");
            }
            databaseKey = TdlibDatabaseKey.getOrCreateBase64(appContext);
        }

        JSONObject request = new JSONObject()
                .put("@type", "setTdlibParameters")
                .put("use_test_dc", false)
                .put("database_directory", databaseDirectory.getAbsolutePath())
                .put("files_directory", filesDirectory.getAbsolutePath())
                .put("database_encryption_key", databaseKey)
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

    private void deleteTdlibRuntimeDirectory(File tdlibDirectory) throws Exception {
        File filesRoot = appContext.getFilesDir().getCanonicalFile();
        File target = tdlibDirectory.getCanonicalFile();
        if (!target.getPath().startsWith(filesRoot.getPath())
                || "tdlib".equals(filesRoot.getName())
                || !target.getName().equals("tdlib")) {
            throw new IllegalStateException("Pasta local do Telegram inválida.");
        }
        deleteRecursively(target);
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete TDLib runtime file: " + file.getAbsolutePath());
        }
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
        runtimeGeneration++;
        clearTelegramRuntimeData();
    }

    private void scheduleRuntimeStabilityReset(long generation) {
        cloudSyncHandler.postDelayed(() -> {
            synchronized (TelegramClientManager.this) {
                if (started && state == State.READY && runtimeGeneration == generation) {
                    runtimeRestartAttempts = 0;
                }
            }
        }, RUNTIME_STABLE_MS);
    }

    private void clearTelegramRuntimeData() {
        chats.clear();
        groupChatIds.clear();
        interestHistorySearches.clear();
        lowestPriceSearches.clear();
        lowestPriceBatches.clear();
        cachedLowestPriceResults.clear();
        List<MessageValidationCallback> interrupted = new ArrayList<>(pendingMessageValidations.values());
        pendingMessageValidations.clear();
        for (MessageValidationCallback callback : interrupted) callback.onResolved(null);
        storedOfferLinkGate.clear();
        groups = Collections.emptyList();
        accountName = "";
        accountPhone = "";
        selfUserId = 0L;
        selfChatId = 0L;
        cloudSyncRequested = false;
        cloudPullGeneration++;
        cloudPullTimeoutToken++;
        cloudHistoryFallbackRequested = false;
        forceCloudRestore = false;
        pendingManualBackup = false;
        pendingManualBackupConfirmation = false;
        pendingManualRestore = false;
        pendingConfigurationRefresh = false;
        selfChatRequested = false;
        initialCloudRestorePending = false;
        cancelCloudBackupWakeup();
        cloudBackupPausedForRetry = false;
        cloudPullScheduled = false;
        cloudPullAgainRequested = false;
        backupPreparationRunning = false;
        cloudBackupGeneration++;
        cloudBackupChunkToken++;
        pendingCloudMessageIds.clear();
        confirmedCloudMessageIds.clear();
        pendingCloudChunks.clear();
        pendingCloudNextChunkIndex = 0;
        pendingCloudExpectedMessages = 0;
        pendingCloudConfirmedMessages = 0;
        pendingCloudBackupFailed = false;
        cloudBackupChunkAwaitingResult = false;
        pendingCloudBackupUpdatedAt = 0L;
        recoveryRunning = false;
        recoveryChatIds.clear();
        notifyGroups();
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onAccountChanged();
        }
    }

    private synchronized void restartAfterClosedRuntime() {
        closeRuntime();
        reconnectRequested = false;
        changeState(State.STARTING);
        cloudSyncHandler.postDelayed(() -> {
            synchronized (TelegramClientManager.this) {
                if (!started && state == State.STARTING) {
                    start(appContext);
                }
            }
        }, 1_100L);
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
        startMissedMessageRecoveryIfReady();
    }

    private synchronized void requestCloudBackup() {
        Log.d(TAG, "requestCloudBackup requested=" + cloudSyncRequested + ", selfChatId=" + selfChatId);
        if (cloudSyncRequested || selfChatId == 0L) {
            return;
        }
        cloudSyncRequested = true;
        cloudHistoryFallbackRequested = false;
        long generation = ++cloudPullGeneration;
        try {
            send(new JSONObject()
                    .put("@type", "searchChatMessages")
                    .put("chat_id", selfChatId)
                    .put("query", "BoaOferta")
                    .put("sender_id", JSONObject.NULL)
                    .put("from_message_id", 0)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                    .put("message_thread_id", 0)
                    .put("@extra", "cloud_sync_search:" + generation));
            scheduleCloudPullTimeout(generation);
        } catch (JSONException exception) {
            requestCloudSyncHistoryFallback(generation);
        }
    }

    private synchronized void requestSelfChat() {
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

    private synchronized void handleSelfChat(JSONObject chat) {
        selfChatId = chat.optLong("id", 0L);
        Log.d(TAG, "handleSelfChat selfChatId=" + selfChatId);
        if (pendingManualRestore) {
            pendingManualRestore = false;
            restoreCloudBackupNow();
            return;
        }
        if (pendingManualBackupDeletion) {
            pendingManualBackupDeletion = false;
            requestManualBackupClear();
            return;
        }
        if (pendingGroupsDelta) {
            pendingGroupsDelta = false;
            sendSelectedGroupsDelta();
        }
        if (!pendingConfigurationDeltas.isEmpty()) {
            List<String> deltas = new ArrayList<>(pendingConfigurationDeltas);
            pendingConfigurationDeltas.clear();
            for (String delta : deltas) sendConfigurationDelta(delta);
        }
        if (pendingConfigurationRefresh) {
            pendingConfigurationRefresh = false;
            requestCloudBackup();
            return;
        }
        requestCloudBackup();
        if (initialCloudRestorePending) {
            return;
        }
        if (pendingManualBackup) {
            pendingManualBackup = false;
            sendCloudBackup();
        }
        if (CloudSyncStore.hasPendingPush(appContext)) {
            scheduleCloudBackup();
        }
    }

    private synchronized void requestCloudSyncHistoryFallback(long generation) {
        if (generation != cloudPullGeneration
                || cloudHistoryFallbackRequested
                || selfChatId == 0L) {
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
                    .put("@extra", "cloud_sync_history:" + generation));
            scheduleCloudPullTimeout(generation);
        } catch (JSONException exception) {
            finishCloudPull(generation, false);
            notifyError(exception.getMessage());
        }
    }

    private void handleCloudSyncMessages(JSONArray messages, String extra) {
        long generation = readCloudPullGeneration(extra);
        if (generation != cloudPullGeneration) {
            return;
        }
        Log.d(TAG, "handleCloudSyncMessages count=" + (messages == null ? -1 : messages.length())
                + ", force=" + forceCloudRestore + ", extra=" + extra);
        if (extra.startsWith("cloud_sync_search") && (messages == null || messages.length() == 0)) {
            requestCloudSyncHistoryFallback(generation);
            return;
        }
        boolean groupsChanged = CloudSyncStore.importSelectedGroupsDeltas(appContext, messages);
        boolean rankingChanged = CloudSyncStore.importRankingDeltas(appContext, messages);
        if (groupsChanged) {
            MonitorServiceController.update(appContext);
            loadGroups();
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
        }
        if (rankingChanged) {
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
        }
        boolean forcedRestore = forceCloudRestore;
        boolean automaticConfigurationRestore = initialCloudRestorePending;
        JSONObject remoteBackup = forcedRestore || automaticConfigurationRestore
                ? CloudSyncStore.findNewestRestorableBackup(messages)
                : CloudSyncStore.findNewestBackup(messages);
        Log.d(TAG, "remoteBackup found=" + (remoteBackup != null)
                + ", updatedAt=" + (remoteBackup == null ? 0L : remoteBackup.optLong("updated_at", 0L)));
        if (remoteBackup != null) {
            CloudSyncStore.rememberBackupMessageId(appContext, remoteBackup.optLong("_message_id", 0L));
        }
        boolean restored = forcedRestore || automaticConfigurationRestore
                ? CloudSyncStore.importBackup(appContext, remoteBackup, true)
                : CloudSyncStore.importIfNewer(appContext, remoteBackup);
        // A completed manual restore must contain a complete alert record that
        // the Alerts screen can render, not merely a backup message.
        if (forcedRestore && restored
                && !CloudSyncStore.backupHasRestorableAlerts(remoteBackup)) {
            restored = false;
        }
        // A manual restore must reproduce the selected snapshot exactly. Applying
        // historical deltas afterward can immediately remove the alerts restored
        // from that snapshot.
        boolean configurationChanged = (forcedRestore || automaticConfigurationRestore)
                ? false
                : CloudSyncStore.importConfigurationDeltas(
                        appContext,
                        messages,
                        remoteBackup == null ? 0L : remoteBackup.optLong("updated_at", 0L)
                );
        if (configurationChanged) {
            MonitorServiceController.update(appContext);
        }
        if (remoteBackup != null) {
            CloudSyncStore.rememberRemoteBackup(appContext, remoteBackup);
            if (CloudSyncStore.needsCompactBackupMigration(appContext)) {
                CloudSyncStore.requestCompactBackupMigration(appContext);
            }
            if (!forcedRestore) {
                notifyCloudSyncStatus(R.string.profile_cloud_backup_found);
            }
        }
        if (restored || configurationChanged) {
            revalidateStoredOfferLinks();
            loadGroups();
            notifyGroups();
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
            if (restored) {
                if (forcedRestore) {
                    CloudSyncStore.rememberRestoreCompleted(appContext, remoteBackup);
                }
                notifyCloudSyncStatus(forcedRestore
                        ? R.string.profile_manual_restore_done
                        : R.string.profile_cloud_restore_done);
            }
        } else if (forcedRestore) {
            notifyCloudSyncStatus(R.string.profile_manual_restore_empty);
        }
        if (forcedRestore) {
            // Keep the restore state (and its UI animation) active until the
            // imported data has been persisted and all alert screens were asked
            // to render the restored list.
            forceCloudRestore = false;
            pendingManualRestore = false;
        }
        boolean firstRestoreFinished = initialCloudRestorePending;
        initialCloudRestorePending = false;
        if (firstRestoreFinished) {
            CloudSyncStore.rememberInitialRestoreFinished(appContext);
        }
        if (pendingManualBackup) {
            pendingManualBackup = false;
            sendCloudBackup();
            finishCloudPull(generation, true);
            return;
        }
        if (!forcedRestore && CloudSyncStore.shouldPushLocalBackup(appContext, remoteBackup)) {
            scheduleCloudBackup();
        } else if (firstRestoreFinished) {
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
        }
        finishCloudPull(generation, true);
    }

    private synchronized void handleIncomingCloudSyncMessage(JSONObject message) {
        if (message.optJSONObject("sending_state") != null) {
            return;
        }
        if (confirmedCloudMessageIds.contains(message.optLong("id", 0L))) {
            return;
        }
        long chatId = message.optLong("chat_id", 0L);
        if (chatId == 0L || (chatId != selfChatId && chatId != selfUserId)) {
            return;
        }
        JSONObject content = message.optJSONObject("content");
        JSONObject text = content == null ? null : content.optJSONObject("text");
        String messageText = text == null ? "" : text.optString("text", "");
        if (messageText.contains(CloudSyncStore.GROUPS_DELTA_MARKER)) {
            if (CloudSyncStore.importSelectedGroupsDelta(appContext, messageText)) {
                CloudSyncStore.rememberConfigurationSynced(appContext);
                loadGroups();
                MonitorServiceController.update(appContext);
                appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                        .setPackage(appContext.getPackageName()));
            }
            return;
        }
        if (messageText.contains(CloudSyncStore.CONFIG_DELTA_MARKER)) {
            if (CloudSyncStore.importConfigurationDelta(appContext, messageText)) {
                appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                        .setPackage(appContext.getPackageName()));
            }
            return;
        }
        if (messageText.contains(CloudSyncStore.RANKING_DELTA_MARKER)) {
            if (CloudSyncStore.importRankingDeltaText(appContext, messageText)) {
                appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                        .setPackage(appContext.getPackageName()));
            }
            return;
        }
        if (text == null || !messageText.contains(CloudSyncStore.MARKER)) {
            return;
        }
        // One completed snapshot triggers one pull, not one pull for every uploaded part.
        if (!CloudSyncStore.shouldRefreshForBackupMessage(message)) return;
        Log.d(TAG, "incoming cloud sync message id=" + message.optLong("id", 0L));
        scheduleCloudPull();
    }

    private synchronized void scheduleCloudPull() {
        if (cloudPullScheduled) {
            cloudPullAgainRequested = true;
            return;
        }
        cloudPullScheduled = true;
        long retryDelay = Math.max(
                0L,
                cloudPullRetryNotBeforeElapsed - SystemClock.elapsedRealtime()
        );
        cloudSyncHandler.postDelayed(() -> {
            cloudPullScheduled = false;
            if (appContext == null || state != State.READY || selfChatId == 0L) {
                return;
            }
            if (cloudSyncRequested) {
                cloudPullAgainRequested = true;
                return;
            }
            cloudHistoryFallbackRequested = false;
            requestCloudBackup();
        }, Math.max(CLOUD_PULL_DEBOUNCE_MS, retryDelay));
    }

    private synchronized void scheduleDeferredCloudPullIfNeeded() {
        if (!cloudPullAgainRequested) {
            return;
        }
        cloudPullAgainRequested = false;
        scheduleCloudPull();
    }

    private synchronized void scheduleCloudPullTimeout(long generation) {
        long token = ++cloudPullTimeoutToken;
        cloudSyncHandler.postDelayed(
                () -> handleCloudPullTimeout(generation, token),
                CLOUD_PULL_TIMEOUT_MS
        );
    }

    private synchronized void handleCloudPullTimeout(long generation, long token) {
        if (!cloudSyncRequested
                || generation != cloudPullGeneration
                || token != cloudPullTimeoutToken) {
            return;
        }
        Log.w(TAG, "Cloud sync request timed out, generation=" + generation);
        cloudSyncRequested = false;
        cloudHistoryFallbackRequested = false;
        cloudPullGeneration++;
        cloudPullTimeoutToken++;
        cloudPullRetryAttempt++;
        cloudPullRetryNotBeforeElapsed = SystemClock.elapsedRealtime()
                + CloudSyncRetryPolicy.delayForAttempt(cloudPullRetryAttempt);
        cloudPullAgainRequested = true;
        scheduleDeferredCloudPullIfNeeded();
    }

    private synchronized void finishCloudPull(long generation, boolean succeeded) {
        if (generation != cloudPullGeneration) {
            return;
        }
        cloudSyncRequested = false;
        cloudHistoryFallbackRequested = false;
        cloudPullGeneration++;
        cloudPullTimeoutToken++;
        if (succeeded) {
            cloudPullRetryAttempt = 0;
            cloudPullRetryNotBeforeElapsed = 0L;
        } else {
            cloudPullRetryAttempt++;
            cloudPullRetryNotBeforeElapsed = SystemClock.elapsedRealtime()
                    + CloudSyncRetryPolicy.delayForAttempt(cloudPullRetryAttempt);
            cloudPullAgainRequested = true;
        }
        scheduleDeferredCloudPullIfNeeded();
    }

    private synchronized long readCloudPullGeneration(String extra) {
        int separator = extra == null ? -1 : extra.lastIndexOf(':');
        if (separator < 0 || separator >= extra.length() - 1) {
            return cloudPullGeneration;
        }
        try {
            return Long.parseLong(extra.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return cloudPullGeneration;
        }
    }

    private synchronized void sendCloudBackup() {
        Log.d(TAG, "sendCloudBackup selfChatId=" + selfChatId
                + ", messageId=" + CloudSyncStore.getBackupMessageId(appContext));
        if (appContext == null || state != State.READY || selfChatId == 0L
                || pendingCloudExpectedMessages > 0 || backupPreparationRunning) {
            return;
        }
        // Recheck here, not only when scheduling: a 429 may arrive after a timer was queued.
        if (cloudBackupRetryGate.remaining(SystemClock.elapsedRealtime()) > 0L) {
            if (pendingManualBackupConfirmation) {
                notifyCloudSyncStatus(R.string.profile_backup_waiting_telegram);
            }
            scheduleCloudBackup();
            return;
        }
        if (initialCloudRestorePending) {
            requestCloudBackup();
            return;
        }
        sendNewCloudBackup();
    }

    private synchronized void sendNewCloudBackup() {
        Log.d(TAG, "sendNewCloudBackup selfChatId=" + selfChatId);
        if (!CloudSyncStore.isPendingRankingOnly(appContext)
                && !CloudSyncStore.hasRestorableAlerts(appContext)) {
            CloudSyncStore.dismissEmptySnapshot(appContext);
            if (pendingManualBackupConfirmation) {
                pendingManualBackupConfirmation = false;
                notifyCloudSyncStatus(R.string.profile_manual_backup_no_alerts);
            }
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
            return;
        }
        backupPreparationRunning = true;
        long generation = ++cloudBackupGeneration;
        long backedUpChange = CloudSyncStore.getLastLocalChangeTimestamp(appContext);
        boolean rankingDelta = CloudSyncStore.isPendingRankingOnly(appContext)
                && CloudSyncStore.hasPendingRankingDelta(appContext)
                && !CloudSyncStore.shouldSendRankingCheckpoint(appContext);
        cloudBackupExecutor.execute(() -> {
            List<String> chunks = rankingDelta
                    ? Collections.singletonList(CloudSyncStore.exportRankingDeltaText(appContext))
                    : CloudSyncStore.exportBackupTextChunks(appContext);
            cloudSyncHandler.post(() -> sendPreparedCloudBackup(
                    chunks,
                    backedUpChange,
                    generation,
                    rankingDelta
            ));
        });
    }

    private synchronized void sendPreparedCloudBackup(List<String> chunks, long backedUpChange,
                                                      long generation, boolean rankingDelta) {
        if (generation != cloudBackupGeneration) {
            return;
        }
        backupPreparationRunning = false;
        if (state != State.READY || selfChatId == 0L || chunks == null || chunks.isEmpty()) {
            return;
        }
        pendingCloudBackupUpdatedAt = backedUpChange;
        pendingCloudBackupIsRankingDelta = rankingDelta;
        pendingCloudMessageIds.clear();
        confirmedCloudMessageIds.clear();
        pendingCloudChunks.clear();
        pendingCloudChunks.addAll(chunks);
        pendingCloudNextChunkIndex = 0;
        pendingCloudExpectedMessages = chunks.size();
        pendingCloudConfirmedMessages = 0;
        pendingCloudBackupFailed = false;
        cloudBackupChunkAwaitingResult = false;
        sendNextCloudBackupChunk();
    }

    private synchronized void sendNextCloudBackupChunk() {
        if (state != State.READY
                || selfChatId == 0L
                || pendingCloudBackupFailed
                || pendingCloudNextChunkIndex >= pendingCloudChunks.size()
                || cloudBackupChunkAwaitingResult
                || !pendingCloudMessageIds.isEmpty()) {
            return;
        }
        if (cloudBackupRetryGate.remaining(SystemClock.elapsedRealtime()) > 0L) {
            cloudBackupPausedForRetry = true;
            scheduleCloudBackup();
            return;
        }
        cloudBackupPausedForRetry = false;
        String chunk = pendingCloudChunks.get(pendingCloudNextChunkIndex++);
        try {
            cloudBackupChunkAwaitingResult = true;
            long generation = cloudBackupGeneration;
            long chunkToken = ++cloudBackupChunkToken;
            JSONObject inputMessage = createCloudBackupInputMessage(chunk);
            send(new JSONObject()
                    .put("@type", "sendMessage")
                    .put("chat_id", selfChatId)
                    .put("input_message_content", inputMessage)
                    .put("@extra", CloudBackupRetryGate.requestTag(generation, chunkToken)));
            cloudSyncHandler.postDelayed(
                    () -> handleCloudBackupChunkTimeout(generation, chunkToken),
                    CLOUD_BACKUP_PART_TIMEOUT_MS
            );
        } catch (JSONException exception) {
            failPendingCloudBackup(0L);
            notifyError(exception.getMessage());
        }
    }

    private JSONObject createCloudBackupInputMessage(String text) throws JSONException {
        String intro = appContext.getString(R.string.telegram_sync_message_intro);
        String messageText = intro + "\n" + text;
        JSONArray entities = new JSONArray().put(new JSONObject()
                .put("@type", "textEntity")
                .put("offset", 0)
                .put("length", messageText.length())
                .put("type", new JSONObject().put("@type", "textEntityTypeExpandableBlockQuote")));
        JSONObject formattedText = new JSONObject()
                .put("@type", "formattedText")
                .put("text", messageText)
                .put("entities", entities);
        return new JSONObject()
                .put("@type", "inputMessageText")
                .put("text", formattedText)
                .put("clear_draft", true);
    }

    private synchronized void handleCloudSyncSentMessage(JSONObject message) {
        if (pendingCloudExpectedMessages <= 0 || !cloudBackupChunkAwaitingResult
                || !CloudBackupRetryGate.isCurrentRequest(message.optString("@extra"),
                cloudBackupGeneration, cloudBackupChunkToken)) {
            return;
        }
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
        cloudBackupChunkAwaitingResult = false;
        cloudBackupChunkToken++;
        confirmCloudBackupPart(messageId);
    }

    private synchronized void handleMessageSendSucceeded(JSONObject result) {
        long oldMessageId = result.optLong("old_message_id", 0L);
        JSONObject message = result.optJSONObject("message");
        long newMessageId = message == null ? 0L : message.optLong("id", 0L);
        Log.d(TAG, "handleMessageSendSucceeded old=" + oldMessageId + ", new=" + newMessageId);
        if (!pendingCloudMessageIds.remove(oldMessageId)) {
            return;
        }
        cloudBackupChunkAwaitingResult = false;
        cloudBackupChunkToken++;
        confirmCloudBackupPart(newMessageId);
    }

    private synchronized void handleMessageSendFailed(JSONObject result) {
        long oldMessageId = result.optLong("old_message_id", 0L);
        JSONObject error = result.optJSONObject("error");
        Log.d(TAG, "handleMessageSendFailed old=" + oldMessageId
                + ", error=" + (error == null ? result.optString("error_message") : error.toString()));
        if (pendingCloudMessageIds.remove(oldMessageId)) {
            handleCloudBackupSendError(error, CloudBackupRetryGate.requestTag(
                    cloudBackupGeneration, cloudBackupChunkToken));
        }
    }

    private synchronized void handleCloudBackupSendError(JSONObject error, String tag) {
        if (!cloudBackupChunkAwaitingResult || !CloudBackupRetryGate.isCurrentRequest(
                tag, cloudBackupGeneration, cloudBackupChunkToken)) return;
        cloudBackupChunkAwaitingResult = false;
        cloudBackupChunkToken++;
        long delay = CloudBackupRetryGate.telegramDelay(error);
        if (delay <= 0L) {
            failPendingCloudBackup(0L);
            return;
        }
        cloudBackupRetryGate.defer(SystemClock.elapsedRealtime(), delay);
        CloudSyncStore.rememberBackupRetryDeadline(appContext,
                CloudBackupRetryGate.deadline(System.currentTimeMillis(), delay));
        // The failed part was not delivered. Keep the same snapshot and all confirmed parts.
        pendingCloudNextChunkIndex = CloudBackupRetryGate.resumeIndex(
                pendingCloudNextChunkIndex, pendingCloudConfirmedMessages);
        cloudBackupPausedForRetry = true;
        cancelCloudBackupWakeup();
        notifyCloudSyncStatus(R.string.profile_backup_waiting_telegram);
        scheduleCloudBackup();
    }

    private void restoreCloudBackupPause() {
        cloudBackupRetryGate.restore(CloudSyncStore.getBackupRetryDeadline(appContext),
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
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
        cloudBackupChunkAwaitingResult = false;
        cloudBackupChunkToken++;
        confirmedCloudMessageIds.add(messageId);
        pendingCloudConfirmedMessages++;
        if (!pendingCloudBackupFailed && pendingCloudConfirmedMessages >= pendingCloudExpectedMessages) {
            pendingCloudExpectedMessages = 0;
            pendingCloudConfirmedMessages = 0;
            pendingCloudChunks.clear();
            pendingCloudNextChunkIndex = 0;
            cloudBackupPausedForRetry = false;
            cloudBackupRetryAttempt = 0;
            lastCloudBackupCompletedElapsed = SystemClock.elapsedRealtime();
            cloudBackupGeneration++;
            boolean wasRankingDelta = pendingCloudBackupIsRankingDelta;
            boolean fullySynced = wasRankingDelta
                    ? CloudSyncStore.markRankingDeltaPushed(appContext, pendingCloudBackupUpdatedAt)
                    : CloudSyncStore.markFullSnapshotPushed(appContext, pendingCloudBackupUpdatedAt);
            pendingCloudBackupUpdatedAt = 0L;
            pendingCloudBackupIsRankingDelta = false;
            if (!wasRankingDelta) {
                requestBackupPrune();
            }
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
            if (!fullySynced) {
                scheduleCloudBackup();
            }
            if (fullySynced && pendingManualBackupConfirmation) {
                pendingManualBackupConfirmation = false;
                notifyCloudSyncStatus(R.string.profile_manual_backup_done);
            }
        } else if (!pendingCloudBackupFailed) {
            long generation = cloudBackupGeneration;
            cloudSyncHandler.postDelayed(
                    () -> {
                        synchronized (TelegramClientManager.this) {
                            if (generation == cloudBackupGeneration) sendNextCloudBackupChunk();
                        }
                    },
                    CLOUD_BACKUP_PART_DELAY_MS
            );
        }
    }

    private synchronized void failPendingCloudBackup(long retryDelayMs) {
        cloudBackupPausedForRetry = false;
        pendingCloudBackupFailed = true;
        pendingCloudExpectedMessages = 0;
        pendingCloudConfirmedMessages = 0;
        pendingCloudMessageIds.clear();
        confirmedCloudMessageIds.clear();
        pendingCloudChunks.clear();
        pendingCloudNextChunkIndex = 0;
        cloudBackupChunkAwaitingResult = false;
        pendingCloudBackupUpdatedAt = 0L;
        cloudBackupGeneration++;
        cloudBackupChunkToken++;
        cloudBackupRetryAttempt++;
        long effectiveRetryDelay = retryDelayMs > 0L
                ? retryDelayMs
                : CloudSyncRetryPolicy.delayForAttempt(cloudBackupRetryAttempt);
        cloudBackupRetryGate.defer(SystemClock.elapsedRealtime(), effectiveRetryDelay);
        if (pendingManualBackupConfirmation) {
            pendingManualBackupConfirmation = false;
            notifyCloudSyncStatus(R.string.profile_manual_backup_failed);
        }
        if (appContext != null && CloudSyncStore.hasPendingPush(appContext)) {
            scheduleCloudBackup();
        }
    }

    private synchronized void handleCloudBackupChunkTimeout(long generation, long chunkToken) {
        if (generation != cloudBackupGeneration
                || chunkToken != cloudBackupChunkToken
                || !cloudBackupChunkAwaitingResult
                || pendingCloudExpectedMessages <= 0) {
            return;
        }
        Log.w(TAG, "Cloud backup chunk timed out, generation=" + generation);
        failPendingCloudBackup(0L);
    }

    private synchronized void requestManualBackupClear() {
        try {
            send(new JSONObject()
                    .put("@type", "searchChatMessages")
                    .put("chat_id", selfChatId)
                    .put("query", "BoaOferta")
                    .put("sender_id", JSONObject.NULL)
                    .put("from_message_id", 0)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                    .put("message_thread_id", 0)
                    .put("@extra", "backup_clear_search"));
        } catch (JSONException exception) {
            finishManualBackupClear(false);
        }
    }

    private synchronized void handleBackupClearSearch(JSONArray messages) {
        JSONArray messageIds = new JSONArray();
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                JSONObject content = message == null ? null : message.optJSONObject("content");
                JSONObject contentText = content == null ? null : content.optJSONObject("text");
                String text = contentText == null ? null : contentText.optString("text", "");
                if (text != null && text.contains("#BoaOferta")) {
                    long id = message.optLong("id", 0L);
                    if (id > 0L) messageIds.put(id);
                }
            }
        }
        if (messageIds.length() == 0) {
            finishManualBackupClear(true);
            return;
        }
        try {
            send(new JSONObject()
                    .put("@type", "deleteMessages")
                    .put("chat_id", selfChatId)
                    .put("message_ids", messageIds)
                    .put("revoke", true)
                    .put("@extra", "backup_clear_delete"));
        } catch (JSONException exception) {
            finishManualBackupClear(false);
        }
    }

    private synchronized void finishManualBackupClear(boolean cleared) {
        if (cleared) {
            CloudSyncStore.clearBackupMetadata(appContext);
            notifyCloudSyncStatus(R.string.profile_manual_backup_cleared);
        } else {
            notifyCloudSyncStatus(R.string.profile_manual_backup_clear_failed);
        }
        if (appContext != null) {
            appContext.sendBroadcast(new android.content.Intent(ACTION_CLOUD_SYNC_CHANGED)
                    .setPackage(appContext.getPackageName()));
        }
    }

    private synchronized void requestBackupPrune() {
        if (selfChatId == 0L || backupPruneRequested || confirmedCloudMessageIds.isEmpty()) {
            return;
        }
        backupPruneKeepMessageIds.clear();
        backupPruneKeepMessageIds.addAll(confirmedCloudMessageIds);
        backupPruneRequested = true;
        try {
            send(new JSONObject()
                    .put("@type", "searchChatMessages")
                    .put("chat_id", selfChatId)
                    .put("query", "BoaOferta")
                    .put("sender_id", JSONObject.NULL)
                    .put("from_message_id", 0)
                    .put("offset", 0)
                    .put("limit", 100)
                    .put("filter", new JSONObject().put("@type", "searchMessagesFilterEmpty"))
                    .put("message_thread_id", 0)
                    .put("@extra", "backup_prune_search"));
        } catch (JSONException exception) {
            backupPruneRequested = false;
            backupPruneKeepMessageIds.clear();
        }
    }

    private synchronized void handleBackupPruneSearch(JSONArray messages) {
        if (CloudSyncStore.preservePropertyHistoryBeforePrune(appContext, messages, backupPruneKeepMessageIds)) {
            backupPruneRequested = false;
            backupPruneKeepMessageIds.clear();
            return;
        }
        JSONArray oldBackupIds = new JSONArray();
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                JSONObject content = message == null ? null : message.optJSONObject("content");
                JSONObject contentText = content == null ? null : content.optJSONObject("text");
                String text = contentText == null ? "" : contentText.optString("text", "");
                long messageId = message == null ? 0L : message.optLong("id", 0L);
                if (messageId > 0L && text.contains("#BoaOferta")
                        && !backupPruneKeepMessageIds.contains(messageId)) {
                    oldBackupIds.put(messageId);
                }
            }
        }
        if (oldBackupIds.length() == 0) {
            backupPruneRequested = false;
            backupPruneKeepMessageIds.clear();
            return;
        }
        try {
            send(new JSONObject()
                    .put("@type", "deleteMessages")
                    .put("chat_id", selfChatId)
                    .put("message_ids", oldBackupIds)
                    .put("revoke", true)
                    .put("@extra", "backup_prune_delete"));
        } catch (JSONException exception) {
            backupPruneRequested = false;
            backupPruneKeepMessageIds.clear();
        }
    }

    private synchronized void scheduleCloudBackup() {
        if (cloudBackupScheduled) {
            return;
        }
        cloudBackupScheduled = true;
        long retryDelay = cloudBackupRetryGate.remaining(SystemClock.elapsedRealtime());
        long minimumIntervalDelay = Math.max(
                0L,
                lastCloudBackupCompletedElapsed + CLOUD_BACKUP_MIN_INTERVAL_MS
                        - SystemClock.elapsedRealtime()
        );
        long wakeupToken = ++cloudBackupWakeupToken;
        cloudBackupWakeup = () -> {
            synchronized (TelegramClientManager.this) {
                if (wakeupToken != cloudBackupWakeupToken) return;
                cloudBackupScheduled = false;
                cloudBackupWakeup = null;
                if (appContext != null
                        && state == State.READY
                        && selfChatId != 0L
                        && !initialCloudRestorePending
                        && (pendingCloudExpectedMessages > 0 || CloudSyncStore.hasPendingPush(appContext))) {
                    if (cloudBackupPausedForRetry && pendingCloudExpectedMessages > 0) {
                        sendNextCloudBackupChunk();
                    } else {
                        sendCloudBackup();
                    }
                }
            }
        };
        cloudSyncHandler.postDelayed(cloudBackupWakeup, Math.max(
                CLOUD_BACKUP_DEBOUNCE_MS + deviceBackupJitterMs(),
                Math.max(retryDelay, minimumIntervalDelay)
        ));
    }

    private void cancelCloudBackupWakeup() {
        cloudBackupWakeupToken++;
        if (cloudBackupWakeup != null) cloudSyncHandler.removeCallbacks(cloudBackupWakeup);
        cloudBackupWakeup = null;
        cloudBackupScheduled = false;
    }

    private long deviceBackupJitterMs() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        return Math.floorMod(fingerprint.hashCode(), 3_000);
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
        AppErrorStore.recordSerious(appContext, "Telegram", message);
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

    private static final class QualityHistorySearch {
        final long chatId;
        final long sinceMillis;
        final String extra;
        int loadedMessages;

        QualityHistorySearch(long chatId, long sinceMillis, String extra) {
            this.chatId = chatId;
            this.sinceMillis = sinceMillis;
            this.extra = extra;
        }
    }

    private static final class LowestPriceSearch {
        final LowestPriceBatch batch;
        final String extra;

        LowestPriceSearch(LowestPriceBatch batch, String extra) {
            this.batch = batch;
            this.extra = extra;
        }
    }

    private static final class LowestPriceBatch {
        final long generation;
        final String term;
        final LowestPriceCallback callback;
        int pendingChats;
        double lowestPrice = Double.POSITIVE_INFINITY;
        double lastReportedPrice = Double.POSITIVE_INFINITY;
        final List<Double> observedPrices = new ArrayList<>();
        final List<LowestPriceCandidate> candidates = new ArrayList<>();

        LowestPriceBatch(long generation, String term, LowestPriceCallback callback,
                         int pendingChats) {
            this.generation = generation;
            this.term = term;
            this.callback = callback;
            this.pendingChats = pendingChats;
        }
    }

    private static final class LowestPriceCandidate {
        final long chatId;
        final long messageId;
        final long messageDate;
        final TelegramMessagePayload payload;
        final double price;

        LowestPriceCandidate(long chatId, long messageId, long messageDate,
                             TelegramMessagePayload payload,
                             double price) {
            this.chatId = chatId;
            this.messageId = messageId;
            this.messageDate = messageDate;
            this.payload = payload;
            this.price = price;
        }
    }

    private static final class CachedLowestPriceResult {
        final long createdAt;
        final double lowestPlausiblePrice;
        final List<LowestPriceCandidate> candidates;

        CachedLowestPriceResult(long createdAt, double lowestPlausiblePrice,
                                List<LowestPriceCandidate> candidates) {
            this.createdAt = createdAt;
            this.lowestPlausiblePrice = lowestPlausiblePrice;
            this.candidates = candidates;
        }
    }
}
