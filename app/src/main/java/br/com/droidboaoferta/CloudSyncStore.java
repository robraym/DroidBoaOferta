package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class CloudSyncStore {
    static final String MARKER = "#BoaOfertaSyncV1";

    private static final String SYNC_PREFS = "cloud_sync_preferences";
    private static final String LAST_LOCAL_CHANGE = "last_local_change";
    private static final String PENDING_PUSH = "pending_push";
    private static final String BACKUP_MESSAGE_ID = "backup_message_id";
    private static final String LAST_BACKUP_AT = "last_confirmed_backup_at";
    private static final String LAST_REMOTE_BACKUP_AT = "last_confirmed_remote_backup_at";
    private static final String COMPACT_BACKUP_MIGRATED = "compact_backup_migrated";

    private static final String TELEGRAM_PREFS = "telegram_preferences";
    private static final String SELECTED_GROUPS = "selected_groups";
    private static final String OFFER_PREFS = "offer_preferences";
    private static final String KEY_INTERESTS = "interests";
    private static final String KEY_RECENT_OFFERS = "recent_offers";
    private static final String KEY_ARCHIVED_OFFERS = "archived_offers";
    private static final String KEY_TRASHED_OFFERS = "trashed_offers";
    private static final String KEY_PROCESSED_MESSAGES = "processed_messages";
    private static final String KEY_INTEREST_UPDATED_AT = "interest_updated_at";
    private static final String KEY_DELETED_INTERESTS = "deleted_interests";
    private static final String KEY_GROUP_SELECTED_AT = "group_selected_at";
    private static final String KEY_REMOVED_GROUPS = "removed_groups";
    private static final String KEY_THEME_UPDATED_AT = "theme_updated_at";
    private static final String KEY_RECENT_UPDATED_AT = "recent_updated_at";
    private static final String KEY_ARCHIVED_UPDATED_AT = "archived_updated_at";
    private static final String KEY_TRASH_UPDATED_AT = "trash_updated_at";
    private static final String KEY_MONITOR_UPDATED_AT = "monitor_updated_at";
    private static final String MONITOR_ENABLED = "monitor_enabled";
    private static final String APP_PREFS = "app_preferences";
    private static final String THEME_MODE = "theme_mode";

    private CloudSyncStore() {
    }

    static void markLocalChanged(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences preferences = syncPrefs(context);
        long changedAt = Math.max(
                System.currentTimeMillis(),
                preferences.getLong(LAST_LOCAL_CHANGE, 0L) + 1L
        );
        preferences.edit()
                .putLong(LAST_LOCAL_CHANGE, changedAt)
                .putBoolean(PENDING_PUSH, true)
                .apply();
        TelegramClientManager.getInstance().syncCloudBackupSoon();
    }

    static void markManualBackupRequested(Context context) {
        if (context == null) {
            return;
        }
        syncPrefs(context).edit()
                .putLong(LAST_LOCAL_CHANGE, System.currentTimeMillis())
                .putBoolean(PENDING_PUSH, true)
                .apply();
    }

    static void rememberInterestChanged(Context context, long interestId, long changedAt) {
        if (context == null || interestId <= 0L) {
            return;
        }
        SharedPreferences preferences = syncPrefs(context);
        JSONObject updatedAt = readObject(preferences.getString(KEY_INTEREST_UPDATED_AT, "{}"));
        JSONObject deleted = readObject(preferences.getString(KEY_DELETED_INTERESTS, "{}"));
        String key = Long.toString(interestId);
        try {
            updatedAt.put(key, changedAt);
            if (deleted.optLong(key, 0L) <= changedAt) {
                deleted.remove(key);
            }
        } catch (Exception ignored) {
        }
        preferences.edit()
                .putString(KEY_INTEREST_UPDATED_AT, updatedAt.toString())
                .putString(KEY_DELETED_INTERESTS, deleted.toString())
                .apply();
    }

    static void rememberInterestDeleted(Context context, long interestId, long deletedAt) {
        if (context == null || interestId <= 0L) {
            return;
        }
        SharedPreferences preferences = syncPrefs(context);
        JSONObject deleted = readObject(preferences.getString(KEY_DELETED_INTERESTS, "{}"));
        try {
            deleted.put(Long.toString(interestId), deletedAt);
        } catch (Exception ignored) {
        }
        preferences.edit()
                .putString(KEY_DELETED_INTERESTS, deleted.toString())
                .apply();
    }

    static void rememberThemeChanged(Context context, long changedAt) {
        if (context == null) {
            return;
        }
        syncPrefs(context).edit()
                .putLong(KEY_THEME_UPDATED_AT, changedAt)
                .apply();
    }

    static void rememberTrashChanged(Context context, long changedAt) {
        if (context == null) {
            return;
        }
        syncPrefs(context).edit()
                .putLong(KEY_TRASH_UPDATED_AT, changedAt)
                .apply();
    }

    static void rememberRecentChanged(Context context, long changedAt) {
        rememberCollectionChanged(context, KEY_RECENT_UPDATED_AT, changedAt);
    }

    static void rememberArchivedChanged(Context context, long changedAt) {
        rememberCollectionChanged(context, KEY_ARCHIVED_UPDATED_AT, changedAt);
    }

    static void rememberMonitorChanged(Context context, long changedAt) {
        rememberCollectionChanged(context, KEY_MONITOR_UPDATED_AT, changedAt);
    }

    private static void rememberCollectionChanged(Context context, String key, long changedAt) {
        if (context == null) {
            return;
        }
        syncPrefs(context).edit().putLong(key, changedAt).apply();
    }

    static void rememberSelectedGroupsChanged(Context context, Set<String> previousGroups,
                                              Set<String> selectedGroups) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> previous = previousGroups == null ? Collections.emptySet() : previousGroups;
        Set<String> selected = selectedGroups == null ? Collections.emptySet() : selectedGroups;
        SharedPreferences preferences = syncPrefs(context);
        JSONObject selectedAt = readObject(preferences.getString(KEY_GROUP_SELECTED_AT, "{}"));
        JSONObject removed = readObject(preferences.getString(KEY_REMOVED_GROUPS, "{}"));
        try {
            for (String groupId : selected) {
                if (!previous.contains(groupId)) {
                    selectedAt.put(groupId, now);
                    if (removed.optLong(groupId, 0L) <= now) {
                        removed.remove(groupId);
                    }
                } else if (selectedAt.optLong(groupId, 0L) <= 0L) {
                    selectedAt.put(groupId, now);
                }
            }
            for (String groupId : previous) {
                if (!selected.contains(groupId)) {
                    removed.put(groupId, now);
                }
            }
        } catch (Exception ignored) {
        }
        preferences.edit()
                .putString(KEY_GROUP_SELECTED_AT, selectedAt.toString())
                .putString(KEY_REMOVED_GROUPS, removed.toString())
                .apply();
        markLocalChanged(context);
    }

    static boolean hasPendingPush(Context context) {
        return syncPrefs(context).getBoolean(PENDING_PUSH, false);
    }

    static boolean markPushed(Context context, long backedUpChange) {
        long now = System.currentTimeMillis();
        boolean newerChangePending = getLastLocalChange(context) > backedUpChange;
        syncPrefs(context).edit()
                .putBoolean(PENDING_PUSH, newerChangePending)
                .putBoolean(COMPACT_BACKUP_MIGRATED, true)
                .putLong(LAST_BACKUP_AT, now)
                .putLong(LAST_REMOTE_BACKUP_AT, now)
                .apply();
        return !newerChangePending;
    }

    static boolean needsCompactBackupMigration(Context context) {
        return !syncPrefs(context).getBoolean(COMPACT_BACKUP_MIGRATED, false);
    }

    static void requestCompactBackupMigration(Context context) {
        syncPrefs(context).edit().putBoolean(PENDING_PUSH, true).apply();
    }

    static long getLastBackupAt(Context context) {
        return syncPrefs(context).getLong(LAST_BACKUP_AT, 0L);
    }

    static long getLastRemoteBackupAt(Context context) {
        return syncPrefs(context).getLong(LAST_REMOTE_BACKUP_AT, 0L);
    }

    static long getBackupMessageId(Context context) {
        return syncPrefs(context).getLong(BACKUP_MESSAGE_ID, 0L);
    }

    static void rememberBackupMessageId(Context context, long messageId) {
        if (messageId <= 0L) {
            return;
        }
        syncPrefs(context).edit()
                .putLong(BACKUP_MESSAGE_ID, messageId)
                .apply();
    }

    static void rememberRemoteBackup(Context context, JSONObject backup) {
        if (backup == null) {
            return;
        }
        long updatedAt = backup.optLong("updated_at", 0L);
        if (updatedAt <= 0L) {
            return;
        }
        syncPrefs(context).edit()
                .putLong(LAST_REMOTE_BACKUP_AT, updatedAt)
                .apply();
    }

    static JSONObject exportBackup(Context context) {
        Context appContext = context.getApplicationContext();
        long updatedAt = getLastLocalChange(appContext);
        if (updatedAt == 0L && hasUsefulData(appContext)) {
            updatedAt = System.currentTimeMillis();
            syncPrefs(appContext).edit().putLong(LAST_LOCAL_CHANGE, updatedAt).apply();
        }

        SharedPreferences telegram = appContext.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE);
        SharedPreferences offers = appContext.getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE);
        SharedPreferences app = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);

        JSONObject backup = new JSONObject();
        JSONObject data = new JSONObject();
        try {
            data.put(SELECTED_GROUPS, stringSetToArray(telegram.getStringSet(
                    SELECTED_GROUPS,
                    Collections.emptySet()
            )));
            data.put(KEY_INTERESTS, offers.getString(KEY_INTERESTS, "[]"));
            data.put(KEY_RECENT_OFFERS, offers.getString(KEY_RECENT_OFFERS, "[]"));
            data.put(KEY_ARCHIVED_OFFERS, offers.getString(KEY_ARCHIVED_OFFERS, "[]"));
            data.put(KEY_TRASHED_OFFERS, offers.getString(KEY_TRASHED_OFFERS, "[]"));
            data.put(KEY_RECENT_UPDATED_AT, ensureCollectionUpdatedAt(
                    appContext,
                    KEY_RECENT_UPDATED_AT,
                    updatedAt
            ));
            data.put(KEY_ARCHIVED_UPDATED_AT, ensureCollectionUpdatedAt(
                    appContext,
                    KEY_ARCHIVED_UPDATED_AT,
                    updatedAt
            ));
            data.put(KEY_TRASH_UPDATED_AT, ensureTrashUpdatedAt(appContext, updatedAt));
            data.put(KEY_INTEREST_UPDATED_AT, ensureInterestUpdatedAt(appContext, updatedAt));
            data.put(KEY_DELETED_INTERESTS, syncPrefs(appContext).getString(KEY_DELETED_INTERESTS, "{}"));
            data.put(KEY_GROUP_SELECTED_AT, ensureGroupSelectedAt(appContext, updatedAt));
            data.put(KEY_REMOVED_GROUPS, syncPrefs(appContext).getString(KEY_REMOVED_GROUPS, "{}"));
            data.put(MONITOR_ENABLED, offers.getBoolean(MONITOR_ENABLED, true));
            data.put(KEY_MONITOR_UPDATED_AT, ensureCollectionUpdatedAt(
                    appContext,
                    KEY_MONITOR_UPDATED_AT,
                    updatedAt
            ));
            data.put(THEME_MODE, app.getString(THEME_MODE, ThemeController.MODE_DARK));
            data.put(KEY_THEME_UPDATED_AT, ensureThemeUpdatedAt(appContext, updatedAt));

            backup.put("version", 1);
            backup.put("updated_at", updatedAt);
            backup.put("data", data);
        } catch (Exception ignored) {
        }
        return backup;
    }

    static String exportBackupText(Context context) {
        return MARKER + "\n" + exportBackup(context).toString();
    }

    static List<String> exportBackupTextChunks(Context context) {
        JSONObject backup = exportBackup(context);
        String payload = backup.toString();
        String compressedPayload = compressPayload(payload);
        int formatVersion = compressedPayload == null ? 2 : 3;
        String encodedPayload = compressedPayload == null ? payload : compressedPayload;
        long updatedAt = backup.optLong("updated_at", System.currentTimeMillis());
        String backupId = Long.toString(updatedAt);
        int chunkSize = 3500;
        int total = Math.max(1, (encodedPayload.length() + chunkSize - 1) / chunkSize);
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            int start = index * chunkSize;
            int end = Math.min(encodedPayload.length(), start + chunkSize);
            JSONObject header = new JSONObject();
            try {
                header.put("version", formatVersion);
                header.put("backup_id", backupId);
                header.put("updated_at", updatedAt);
                header.put("chunk", index + 1);
                header.put("total", total);
                if (formatVersion == 3) {
                    header.put("encoding", "gzip-base64");
                }
            } catch (Exception ignored) {
            }
            chunks.add(MARKER + "\n" + header + "\n" + encodedPayload.substring(start, end));
        }
        return chunks;
    }

    static JSONObject findNewestBackup(JSONArray messages) {
        JSONObject newest = null;
        long newestUpdatedAt = 0L;
        Map<String, List<JSONObject>> chunkGroups = new HashMap<>();
        if (messages == null) {
            return null;
        }
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            JSONObject chunk = parseBackupChunkFromMessage(message);
            if (chunk != null) {
                String backupId = chunk.optString("backup_id", "");
                if (!backupId.isEmpty()) {
                    List<JSONObject> chunks = chunkGroups.get(backupId);
                    if (chunks == null) {
                        chunks = new ArrayList<>();
                        chunkGroups.put(backupId, chunks);
                    }
                    chunks.add(chunk);
                }
                continue;
            }

            JSONObject backup = parseBackupFromMessage(message);
            if (backup == null) {
                continue;
            }
            try {
                backup.put("_message_id", message.optLong("id", 0L));
            } catch (Exception ignored) {
            }
            long updatedAt = backup.optLong("updated_at", 0L);
            if (updatedAt > newestUpdatedAt) {
                newest = backup;
                newestUpdatedAt = updatedAt;
            }
        }
        for (List<JSONObject> chunks : chunkGroups.values()) {
            JSONObject backup = buildBackupFromChunks(chunks);
            if (backup == null) {
                continue;
            }
            long updatedAt = backup.optLong("updated_at", 0L);
            if (updatedAt > newestUpdatedAt) {
                newest = backup;
                newestUpdatedAt = updatedAt;
            }
        }
        return newest;
    }

    static boolean importIfNewer(Context context, JSONObject backup) {
        return importBackup(context, backup, false);
    }

    static boolean importBackup(Context context, JSONObject backup, boolean force) {
        if (backup == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        long remoteUpdatedAt = backup.optLong("updated_at", 0L);
        long localUpdatedAt = getLastLocalChange(appContext);
        long lastSeenRemoteAt = getLastRemoteBackupAt(appContext);
        if (remoteUpdatedAt <= 0L
                || (!force && remoteUpdatedAt <= lastSeenRemoteAt)) {
            return false;
        }

        JSONObject data = backup.optJSONObject("data");
        if (data == null) {
            return false;
        }

        SharedPreferences.Editor telegram = appContext
                .getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE)
                .edit();
        Set<String> mergedGroups = mergeSelectedGroups(appContext, data, remoteUpdatedAt);
        telegram.putStringSet(SELECTED_GROUPS, mergedGroups);
        telegram.apply();

        SharedPreferences offersPreferences = appContext.getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE);
        JSONObject localInterestUpdatedAt = readObject(syncPrefs(appContext).getString(KEY_INTEREST_UPDATED_AT, "{}"));
        JSONObject remoteInterestUpdatedAt = readObject(data.optString(KEY_INTEREST_UPDATED_AT, "{}"));
        JSONObject localDeletedInterests = readObject(syncPrefs(appContext).getString(KEY_DELETED_INTERESTS, "{}"));
        JSONObject remoteDeletedInterests = readObject(data.optString(KEY_DELETED_INTERESTS, "{}"));
        JSONObject mergedDeletedInterests = mergeMaxObjects(localDeletedInterests, remoteDeletedInterests);
        JSONArray mergedInterests = mergeInterests(
                offersPreferences.getString(KEY_INTERESTS, "[]"),
                data.optString(KEY_INTERESTS, "[]"),
                localInterestUpdatedAt,
                remoteInterestUpdatedAt,
                mergedDeletedInterests,
                localUpdatedAt,
                remoteUpdatedAt
        );
        JSONObject mergedInterestUpdatedAt = buildInterestUpdatedAt(
                mergedInterests,
                localInterestUpdatedAt,
                remoteInterestUpdatedAt,
                localUpdatedAt,
                remoteUpdatedAt
        );
        SharedPreferences.Editor offers = appContext
                .getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE)
                .edit();
        offers.putString(KEY_INTERESTS, mergedInterests.toString());
        long localRecentUpdatedAt = syncPrefs(appContext).getLong(KEY_RECENT_UPDATED_AT, localUpdatedAt);
        long remoteRecentUpdatedAt = data.optLong(KEY_RECENT_UPDATED_AT, remoteUpdatedAt);
        long localArchivedUpdatedAt = syncPrefs(appContext).getLong(KEY_ARCHIVED_UPDATED_AT, localUpdatedAt);
        long remoteArchivedUpdatedAt = data.optLong(KEY_ARCHIVED_UPDATED_AT, remoteUpdatedAt);
        long localTrashUpdatedAt = syncPrefs(appContext).getLong(KEY_TRASH_UPDATED_AT, localUpdatedAt);
        long remoteTrashUpdatedAt = data.optLong(KEY_TRASH_UPDATED_AT, remoteUpdatedAt);
        putMergedOffers(
                offersPreferences,
                offers,
                data,
                localRecentUpdatedAt,
                remoteRecentUpdatedAt,
                localArchivedUpdatedAt,
                remoteArchivedUpdatedAt,
                localTrashUpdatedAt,
                remoteTrashUpdatedAt
        );
        offers.putString(KEY_PROCESSED_MESSAGES, mergeStringArrays(
                offersPreferences.getString(KEY_PROCESSED_MESSAGES, "[]"),
                data.optString(KEY_PROCESSED_MESSAGES, "[]")
        ).toString());
        long localMonitorUpdatedAt = syncPrefs(appContext).getLong(KEY_MONITOR_UPDATED_AT, localUpdatedAt);
        long remoteMonitorUpdatedAt = data.optLong(KEY_MONITOR_UPDATED_AT, remoteUpdatedAt);
        offers.putBoolean(MONITOR_ENABLED, remoteMonitorUpdatedAt >= localMonitorUpdatedAt
                ? data.optBoolean(MONITOR_ENABLED, true)
                : offersPreferences.getBoolean(MONITOR_ENABLED, true));
        offers.apply();

        SharedPreferences appPreferences = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String localThemeMode = appPreferences.getString(THEME_MODE, ThemeController.MODE_DARK);
        long localThemeUpdatedAt = syncPrefs(appContext).getLong(KEY_THEME_UPDATED_AT, localUpdatedAt);
        long remoteThemeUpdatedAt = data.optLong(KEY_THEME_UPDATED_AT, remoteUpdatedAt);
        boolean useRemoteTheme = remoteThemeUpdatedAt >= localThemeUpdatedAt;
        String themeMode = useRemoteTheme
                ? data.optString(THEME_MODE, ThemeController.MODE_DARK)
                : localThemeMode;
        if (!themeMode.equals(localThemeMode)) {
            appPreferences.edit().putString(THEME_MODE, themeMode).apply();
            ThemeController.applySavedTheme(appContext);
        }

        boolean shouldPushMergedBackup = hasPendingPush(appContext) || localUpdatedAt > remoteUpdatedAt;
        syncPrefs(appContext).edit()
                .putLong(LAST_LOCAL_CHANGE, Math.max(localUpdatedAt, remoteUpdatedAt))
                .putBoolean(PENDING_PUSH, shouldPushMergedBackup)
                .putString(KEY_INTEREST_UPDATED_AT, mergedInterestUpdatedAt.toString())
                .putString(KEY_DELETED_INTERESTS, mergedDeletedInterests.toString())
                .putLong(KEY_THEME_UPDATED_AT, Math.max(localThemeUpdatedAt, remoteThemeUpdatedAt))
                .putLong(KEY_RECENT_UPDATED_AT, Math.max(localRecentUpdatedAt, remoteRecentUpdatedAt))
                .putLong(KEY_ARCHIVED_UPDATED_AT, Math.max(localArchivedUpdatedAt, remoteArchivedUpdatedAt))
                .putLong(KEY_TRASH_UPDATED_AT, Math.max(localTrashUpdatedAt, remoteTrashUpdatedAt))
                .putLong(KEY_MONITOR_UPDATED_AT, Math.max(localMonitorUpdatedAt, remoteMonitorUpdatedAt))
                .apply();
        return true;
    }

    static boolean shouldPushLocalBackup(Context context, JSONObject remoteBackup) {
        if (!hasUsefulData(context)) {
            return false;
        }
        if (hasPendingPush(context)) {
            return true;
        }
        long localUpdatedAt = getLastLocalChange(context);
        long remoteUpdatedAt = remoteBackup == null ? 0L : remoteBackup.optLong("updated_at", 0L);
        return localUpdatedAt == 0L || localUpdatedAt > remoteUpdatedAt;
    }

    private static JSONObject parseBackupFromMessage(JSONObject message) {
        if (message == null) {
            return null;
        }
        JSONObject content = message.optJSONObject("content");
        if (content == null || !"messageText".equals(content.optString("@type"))) {
            return null;
        }
        JSONObject text = content.optJSONObject("text");
        if (text == null) {
            return null;
        }
        String value = text.optString("text", "");
        int markerIndex = value.indexOf(MARKER);
        if (markerIndex < 0) {
            return null;
        }
        int jsonStart = value.indexOf('{', markerIndex);
        if (jsonStart < 0) {
            return null;
        }
        try {
            return new JSONObject(value.substring(jsonStart));
        } catch (Exception exception) {
            return null;
        }
    }

    private static JSONObject parseBackupChunkFromMessage(JSONObject message) {
        String value = getMessageText(message);
        if (value == null) {
            return null;
        }
        int markerIndex = value.indexOf(MARKER);
        if (markerIndex < 0) {
            return null;
        }
        int headerStart = value.indexOf('{', markerIndex);
        if (headerStart < 0) {
            return null;
        }
        int headerEnd = value.indexOf('\n', headerStart);
        if (headerEnd < 0) {
            return null;
        }
        try {
            JSONObject header = new JSONObject(value.substring(headerStart, headerEnd));
            int version = header.optInt("version", 1);
            if (version != 2 && version != 3) {
                return null;
            }
            header.put("_payload", value.substring(headerEnd + 1));
            header.put("_message_id", message.optLong("id", 0L));
            return header;
        } catch (Exception exception) {
            return null;
        }
    }

    private static JSONObject buildBackupFromChunks(List<JSONObject> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        int total = chunks.get(0).optInt("total", 0);
        if (total <= 0 || chunks.size() < total) {
            return null;
        }
        JSONObject[] ordered = new JSONObject[total];
        long firstMessageId = 0L;
        for (JSONObject chunk : chunks) {
            int chunkIndex = chunk.optInt("chunk", 0) - 1;
            if (chunkIndex < 0 || chunkIndex >= total || ordered[chunkIndex] != null) {
                continue;
            }
            ordered[chunkIndex] = chunk;
            if (firstMessageId == 0L) {
                firstMessageId = chunk.optLong("_message_id", 0L);
            }
        }
        StringBuilder payload = new StringBuilder();
        for (JSONObject chunk : ordered) {
            if (chunk == null) {
                return null;
            }
            payload.append(chunk.optString("_payload", ""));
        }
        try {
            String serializedBackup = payload.toString();
            if (chunks.get(0).optInt("version", 2) == 3
                    && "gzip-base64".equals(chunks.get(0).optString("encoding"))) {
                serializedBackup = decompressPayload(serializedBackup);
            }
            JSONObject backup = new JSONObject(serializedBackup);
            backup.put("_message_id", firstMessageId);
            return backup;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String compressPayload(String payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String decompressPayload(String payload) throws Exception {
        byte[] compressed = Base64.decode(payload, Base64.NO_WRAP);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String getMessageText(JSONObject message) {
        if (message == null) {
            return null;
        }
        JSONObject content = message.optJSONObject("content");
        if (content == null || !"messageText".equals(content.optString("@type"))) {
            return null;
        }
        JSONObject text = content.optJSONObject("text");
        return text == null ? null : text.optString("text", "");
    }

    private static Set<String> mergeSelectedGroups(Context context, JSONObject remoteData, long remoteUpdatedAt) {
        Context appContext = context.getApplicationContext();
        SharedPreferences telegram = appContext.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE);
        SharedPreferences sync = syncPrefs(appContext);
        long localUpdatedAt = getLastLocalChange(appContext);
        Set<String> localGroups = new HashSet<>(telegram.getStringSet(SELECTED_GROUPS, Collections.emptySet()));
        Set<String> remoteGroups = jsonArrayToStringSet(remoteData.optJSONArray(SELECTED_GROUPS));
        JSONObject localSelectedAt = readObject(sync.getString(KEY_GROUP_SELECTED_AT, "{}"));
        JSONObject remoteSelectedAt = readObject(remoteData.optString(KEY_GROUP_SELECTED_AT, "{}"));
        JSONObject localRemoved = readObject(sync.getString(KEY_REMOVED_GROUPS, "{}"));
        JSONObject remoteRemoved = readObject(remoteData.optString(KEY_REMOVED_GROUPS, "{}"));
        JSONObject mergedSelectedAt = new JSONObject();
        JSONObject mergedRemoved = mergeMaxObjects(localRemoved, remoteRemoved);
        Set<String> allGroups = new HashSet<>();
        allGroups.addAll(localGroups);
        allGroups.addAll(remoteGroups);
        Set<String> mergedGroups = new HashSet<>();
        for (String groupId : allGroups) {
            long selectedAt = Math.max(
                    timestampFor(localSelectedAt, groupId, localGroups.contains(groupId) ? localUpdatedAt : 0L),
                    timestampFor(remoteSelectedAt, groupId, remoteGroups.contains(groupId) ? remoteUpdatedAt : 0L)
            );
            long removedAt = mergedRemoved.optLong(groupId, 0L);
            if (selectedAt > removedAt) {
                mergedGroups.add(groupId);
                putLong(mergedSelectedAt, groupId, selectedAt);
            }
        }
        sync.edit()
                .putString(KEY_GROUP_SELECTED_AT, mergedSelectedAt.toString())
                .putString(KEY_REMOVED_GROUPS, mergedRemoved.toString())
                .apply();
        return mergedGroups;
    }

    private static JSONArray mergeInterests(String localText, String remoteText,
                                            JSONObject localUpdatedAt, JSONObject remoteUpdatedAt,
                                            JSONObject mergedDeleted,
                                            long localBackupAt, long remoteBackupAt) {
        JSONArray localArray = readArray(localText);
        JSONArray remoteArray = readArray(remoteText);
        Map<String, JSONObject> localItems = mapById(localArray);
        Map<String, JSONObject> remoteItems = mapById(remoteArray);
        List<String> ids = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        JSONArray primaryOrder = remoteBackupAt >= localBackupAt ? remoteArray : localArray;
        JSONArray secondaryOrder = remoteBackupAt >= localBackupAt ? localArray : remoteArray;
        appendInterestIds(primaryOrder, ids, addedIds);
        appendInterestIds(secondaryOrder, ids, addedIds);
        JSONArray merged = new JSONArray();
        for (String id : ids) {
            JSONObject localItem = localItems.get(id);
            JSONObject remoteItem = remoteItems.get(id);
            long localItemAt = timestampFor(localUpdatedAt, id, localItem == null ? 0L : localBackupAt);
            long remoteItemAt = timestampFor(remoteUpdatedAt, id, remoteItem == null ? 0L : remoteBackupAt);
            long itemAt = Math.max(localItemAt, remoteItemAt);
            long deletedAt = mergedDeleted.optLong(id, 0L);
            if (deletedAt >= itemAt) {
                continue;
            }
            JSONObject chosen = remoteItemAt > localItemAt && remoteItem != null ? remoteItem : localItem;
            if (chosen == null) {
                chosen = remoteItem;
            }
            if (chosen != null) {
                merged.put(chosen);
            }
        }
        return merged;
    }

    private static void appendInterestIds(JSONArray source, List<String> ids, Set<String> addedIds) {
        for (int index = 0; index < source.length(); index++) {
            JSONObject interest = source.optJSONObject(index);
            if (interest == null) {
                continue;
            }
            String id = Long.toString(interest.optLong("id", 0L));
            if (!"0".equals(id) && addedIds.add(id)) {
                ids.add(id);
            }
        }
    }

    private static JSONObject buildInterestUpdatedAt(JSONArray interests,
                                                     JSONObject localUpdatedAt,
                                                     JSONObject remoteUpdatedAt,
                                                     long localBackupAt,
                                                     long remoteBackupAt) {
        JSONObject merged = new JSONObject();
        for (int index = 0; index < interests.length(); index++) {
            JSONObject interest = interests.optJSONObject(index);
            if (interest == null) {
                continue;
            }
            String id = Long.toString(interest.optLong("id", 0L));
            long updatedAt = Math.max(
                    timestampFor(localUpdatedAt, id, localBackupAt),
                    timestampFor(remoteUpdatedAt, id, remoteBackupAt)
            );
            putLong(merged, id, updatedAt);
        }
        return merged;
    }

    private static void putMergedOffers(SharedPreferences preferences, SharedPreferences.Editor editor,
                                        JSONObject remoteData, long localRecentUpdatedAt,
                                        long remoteRecentUpdatedAt, long localArchivedUpdatedAt,
                                        long remoteArchivedUpdatedAt, long localTrashUpdatedAt,
                                        long remoteTrashUpdatedAt) {
        JSONArray localRecent = readArray(preferences.getString(KEY_RECENT_OFFERS, "[]"));
        JSONArray localArchived = readArray(preferences.getString(KEY_ARCHIVED_OFFERS, "[]"));
        JSONArray localTrashed = readArray(preferences.getString(KEY_TRASHED_OFFERS, "[]"));
        JSONArray remoteRecent = readArray(remoteData.optString(KEY_RECENT_OFFERS, "[]"));
        JSONArray remoteArchived = readArray(remoteData.optString(KEY_ARCHIVED_OFFERS, "[]"));
        JSONArray remoteTrashed = readArray(remoteData.optString(KEY_TRASHED_OFFERS, "[]"));

        Map<String, JSONObject> recent = mapByOfferId(
                remoteRecentUpdatedAt >= localRecentUpdatedAt ? remoteRecent : localRecent
        );
        Map<String, JSONObject> archived = mapByOfferId(
                remoteArchivedUpdatedAt >= localArchivedUpdatedAt ? remoteArchived : localArchived
        );
        Map<String, JSONObject> trashed = mapByOfferId(
                remoteTrashUpdatedAt >= localTrashUpdatedAt ? remoteTrashed : localTrashed
        );
        for (String id : trashed.keySet()) {
            recent.remove(id);
            archived.remove(id);
        }
        for (String id : archived.keySet()) {
            recent.remove(id);
        }
        editor.putString(KEY_RECENT_OFFERS, offersToArray(recent).toString());
        editor.putString(KEY_ARCHIVED_OFFERS, offersToArray(archived).toString());
        editor.putString(KEY_TRASHED_OFFERS, offersToArray(trashed).toString());
    }

    private static String ensureInterestUpdatedAt(Context context, long fallbackUpdatedAt) {
        SharedPreferences preferences = syncPrefs(context);
        JSONObject updatedAt = readObject(preferences.getString(KEY_INTEREST_UPDATED_AT, "{}"));
        JSONArray interests = readArray(context.getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_INTERESTS, "[]"));
        boolean changed = false;
        for (int index = 0; index < interests.length(); index++) {
            String id = interests.optJSONObject(index) == null
                    ? ""
                    : Long.toString(interests.optJSONObject(index).optLong("id", 0L));
            if (!id.isEmpty() && updatedAt.optLong(id, 0L) <= 0L) {
                putLong(updatedAt, id, fallbackUpdatedAt);
                changed = true;
            }
        }
        if (changed) {
            preferences.edit().putString(KEY_INTEREST_UPDATED_AT, updatedAt.toString()).apply();
        }
        return updatedAt.toString();
    }

    private static String ensureGroupSelectedAt(Context context, long fallbackUpdatedAt) {
        SharedPreferences preferences = syncPrefs(context);
        JSONObject selectedAt = readObject(preferences.getString(KEY_GROUP_SELECTED_AT, "{}"));
        Set<String> groups = context.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE)
                .getStringSet(SELECTED_GROUPS, Collections.emptySet());
        boolean changed = false;
        for (String groupId : groups) {
            if (selectedAt.optLong(groupId, 0L) <= 0L) {
                putLong(selectedAt, groupId, fallbackUpdatedAt);
                changed = true;
            }
        }
        if (changed) {
            preferences.edit().putString(KEY_GROUP_SELECTED_AT, selectedAt.toString()).apply();
        }
        return selectedAt.toString();
    }

    private static long ensureThemeUpdatedAt(Context context, long fallbackUpdatedAt) {
        SharedPreferences preferences = syncPrefs(context);
        long updatedAt = preferences.getLong(KEY_THEME_UPDATED_AT, 0L);
        if (updatedAt <= 0L) {
            updatedAt = fallbackUpdatedAt;
            preferences.edit().putLong(KEY_THEME_UPDATED_AT, updatedAt).apply();
        }
        return updatedAt;
    }

    private static long ensureTrashUpdatedAt(Context context, long fallbackUpdatedAt) {
        return ensureCollectionUpdatedAt(context, KEY_TRASH_UPDATED_AT, fallbackUpdatedAt);
    }

    private static long ensureCollectionUpdatedAt(Context context, String key, long fallbackUpdatedAt) {
        SharedPreferences preferences = syncPrefs(context);
        long updatedAt = preferences.getLong(key, 0L);
        if (updatedAt <= 0L) {
            updatedAt = fallbackUpdatedAt;
            preferences.edit().putLong(key, updatedAt).apply();
        }
        return updatedAt;
    }

    private static boolean hasUsefulData(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences telegram = appContext.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE);
        SharedPreferences offers = appContext.getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE);
        SharedPreferences app = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        return !telegram.getStringSet(SELECTED_GROUPS, Collections.emptySet()).isEmpty()
                || !"[]".equals(offers.getString(KEY_INTERESTS, "[]"))
                || !"[]".equals(offers.getString(KEY_RECENT_OFFERS, "[]"))
                || !"[]".equals(offers.getString(KEY_ARCHIVED_OFFERS, "[]"))
                || !"[]".equals(offers.getString(KEY_TRASHED_OFFERS, "[]"))
                || !offers.getBoolean(MONITOR_ENABLED, true)
                || ThemeController.MODE_LIGHT.equals(app.getString(THEME_MODE, ThemeController.MODE_DARK));
    }

    private static long getLastLocalChange(Context context) {
        return syncPrefs(context).getLong(LAST_LOCAL_CHANGE, 0L);
    }

    static long getLastLocalChangeTimestamp(Context context) {
        return getLastLocalChange(context);
    }

    private static SharedPreferences syncPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray readArray(String text) {
        try {
            return new JSONArray(text == null ? "[]" : text);
        } catch (Exception exception) {
            return new JSONArray();
        }
    }

    private static JSONObject readObject(String text) {
        try {
            return new JSONObject(text == null ? "{}" : text);
        } catch (Exception exception) {
            return new JSONObject();
        }
    }

    private static Map<String, JSONObject> mapById(JSONArray array) {
        Map<String, JSONObject> values = new HashMap<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String id = Long.toString(item.optLong("id", 0L));
            if (!"0".equals(id)) {
                values.put(id, item);
            }
        }
        return values;
    }

    private static Map<String, JSONObject> mapByOfferId(JSONArray array) {
        Map<String, JSONObject> values = new HashMap<>();
        appendOffers(values, array);
        return values;
    }

    private static void appendOffers(Map<String, JSONObject> values, JSONArray array) {
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            JSONObject previous = values.get(id);
            if (previous == null || item.optLong("observed_at", 0L) >= previous.optLong("observed_at", 0L)) {
                values.put(id, item);
            }
        }
    }

    private static JSONArray offersToArray(Map<String, JSONObject> values) {
        List<JSONObject> offers = new ArrayList<>(values.values());
        offers.sort((first, second) -> Long.compare(
                second.optLong("observed_at", 0L),
                first.optLong("observed_at", 0L)
        ));
        JSONArray array = new JSONArray();
        int limit = Math.min(offers.size(), 60);
        for (int index = 0; index < limit; index++) {
            array.put(offers.get(index));
        }
        return array;
    }

    private static JSONArray mergeStringArrays(String localText, String remoteText) {
        JSONArray local = readArray(localText);
        JSONArray remote = readArray(remoteText);
        Set<String> values = new HashSet<>();
        for (int index = 0; index < local.length(); index++) {
            String value = local.optString(index, "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        for (int index = 0; index < remote.length(); index++) {
            String value = remote.optString(index, "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONObject mergeMaxObjects(JSONObject first, JSONObject second) {
        JSONObject merged = new JSONObject();
        copyMaxValues(merged, first);
        copyMaxValues(merged, second);
        return merged;
    }

    private static void copyMaxValues(JSONObject target, JSONObject source) {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            long value = source.optLong(key, 0L);
            if (value > target.optLong(key, 0L)) {
                putLong(target, key, value);
            }
        }
    }

    private static long timestampFor(JSONObject values, String key, long fallback) {
        long value = values.optLong(key, 0L);
        return value > 0L ? value : fallback;
    }

    private static void putLong(JSONObject object, String key, long value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static JSONArray stringSetToArray(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : new HashSet<>(values)) {
            array.put(value);
        }
        return array;
    }

    private static Set<String> jsonArrayToStringSet(JSONArray array) {
        Set<String> values = new HashSet<>();
        if (array == null) {
            return values;
        }
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
