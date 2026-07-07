package br.com.droidboaoferta;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AlertSoundController {
    static final String DEFAULT_SOUND = "alertou_assinatura";
    static final String CUSTOM_SOUND = "custom";

    private static final String PREFS = "app_preferences";
    private static final String ALERT_SOUND = "alert_sound";
    private static final String CLOUD_ALERT_SOUND = "alert_sound_cloud";
    private static final String CUSTOM_SOUND_LIST = "alert_sound_custom_list";
    private static final String LEGACY_CUSTOM_SOUND_NAME = "alert_sound_custom_name";
    private static final String LEGACY_CUSTOM_SOUND_VERSION = "alert_sound_custom_version";
    private static final String CUSTOM_SOUND_DIR = "alert_sounds";
    private static final String LEGACY_CUSTOM_SOUND_FILE = "custom_alert_sound";
    private static final String CUSTOM_SOUND_FILE_PREFIX = "custom_alert_sound_";
    private static final String CHANNEL_PREFIX = "good_offers_sound_";
    private static final String LEGACY_CHANNEL = "good_offers";

    private static final String[] KEYS = {
            DEFAULT_SOUND,
            "oferta_encontrada",
            "brisa_verde",
            "pingo_de_preco",
            "valeu_a_pena",
            "cupom_suave",
            "preco_caiu",
            "toque_verde",
            "oferta_flash",
            "achado_bom",
            "sinal_de_preco",
            "ping_promocao",
            "desconto_leve",
            "alerta_macio",
            "boa_compra"
    };
    private static final int[] LABELS = {
            R.string.alert_sound_signature,
            R.string.alert_sound_offer_found,
            R.string.alert_sound_green_breeze,
            R.string.alert_sound_price_drop,
            R.string.alert_sound_worth_it,
            R.string.alert_sound_soft_coupon,
            R.string.alert_sound_price_dropped,
            R.string.alert_sound_green_tone,
            R.string.alert_sound_flash_offer,
            R.string.alert_sound_good_find,
            R.string.alert_sound_price_signal,
            R.string.alert_sound_promo_ping,
            R.string.alert_sound_light_discount,
            R.string.alert_sound_soft_alert,
            R.string.alert_sound_good_buy
    };
    private static final int[] RAW_RESOURCES = {
            R.raw.alertou_assinatura,
            R.raw.oferta_encontrada,
            R.raw.brisa_verde,
            R.raw.pingo_de_preco,
            R.raw.valeu_a_pena,
            R.raw.cupom_suave,
            R.raw.preco_caiu,
            R.raw.toque_verde,
            R.raw.oferta_flash,
            R.raw.achado_bom,
            R.raw.sinal_de_preco,
            R.raw.ping_promocao,
            R.raw.desconto_leve,
            R.raw.alerta_macio,
            R.raw.boa_compra
    };
    private static final Set<String> VALID_KEYS = new HashSet<>(Arrays.asList(KEYS));

    private AlertSoundController() {
    }

    static String[] getKeys() {
        return KEYS.clone();
    }

    static String getSavedSound(Context context) {
        String saved = prefs(context).getString(ALERT_SOUND, DEFAULT_SOUND);
        if (isCustomSound(saved) && hasCustomSound(context, saved)) {
            return saved;
        }
        if (CUSTOM_SOUND.equals(saved)) {
            List<CustomSound> customSounds = getCustomSounds(context);
            if (!customSounds.isEmpty()) {
                return customSounds.get(0).getKey();
            }
        }
        return normalizeBuiltIn(saved);
    }

    static String getBackupSound(Context context) {
        SharedPreferences preferences = prefs(context);
        String cloudSound = preferences.getString(CLOUD_ALERT_SOUND, "");
        if (isBuiltInSound(cloudSound)) {
            return cloudSound;
        }
        String savedSound = getSavedSound(context);
        return isBuiltInSound(savedSound) ? savedSound : DEFAULT_SOUND;
    }

    static boolean isBuiltInSound(String sound) {
        return VALID_KEYS.contains(sound);
    }

    static boolean isCustomSound(String sound) {
        return sound != null && sound.startsWith(CUSTOM_SOUND + ":");
    }

    static void saveSound(Context context, String sound) {
        String normalized = normalizeBuiltIn(sound);
        long changedAt = System.currentTimeMillis();
        prefs(context).edit()
                .putString(ALERT_SOUND, normalized)
                .putString(CLOUD_ALERT_SOUND, normalized)
                .apply();
        CloudSyncStore.rememberAlertSoundChanged(context, changedAt);
        CloudSyncStore.markLocalChanged(context);
        configureNotificationChannel(context);
    }

    static String saveCustomSound(Context context, Uri sourceUri) throws IOException {
        Context appContext = context.getApplicationContext();
        if (sourceUri == null) {
            throw new IOException("Som personalizado inválido.");
        }
        long changedAt = System.currentTimeMillis();
        String id = createCustomSoundId(appContext, changedAt);
        File target = getCustomSoundFileById(appContext, id);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Não foi possível preparar a pasta de sons.");
        }
        try (InputStream input = appContext.getContentResolver().openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(target, false)) {
            if (input == null) {
                throw new IOException("Não foi possível abrir o som selecionado.");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }

        String key = customKey(id);
        List<CustomSound> customSounds = getCustomSounds(appContext);
        customSounds.add(new CustomSound(id, readDisplayName(appContext, sourceUri), changedAt));
        saveCustomSounds(appContext, customSounds);
        prefs(appContext).edit()
                .putString(ALERT_SOUND, key)
                .apply();
        configureNotificationChannel(appContext);
        return key;
    }

    static void saveExistingCustomSound(Context context, String sound) {
        if (!hasCustomSound(context, sound)) {
            return;
        }
        prefs(context).edit().putString(ALERT_SOUND, sound).apply();
        configureNotificationChannel(context);
    }

    static void removeCustomSound(Context context, String sound) {
        Context appContext = context.getApplicationContext();
        if (!isCustomSound(sound)) {
            return;
        }
        String id = customId(sound);
        File customSound = getCustomSoundFileById(appContext, id);
        if (customSound.exists()) {
            //noinspection ResultOfMethodCallIgnored
            customSound.delete();
        }
        if ("legacy".equals(id)) {
            prefs(appContext).edit()
                    .remove(LEGACY_CUSTOM_SOUND_NAME)
                    .remove(LEGACY_CUSTOM_SOUND_VERSION)
                    .apply();
        }
        List<CustomSound> customSounds = getCustomSounds(appContext);
        for (int index = customSounds.size() - 1; index >= 0; index--) {
            if (customSounds.get(index).id.equals(id)) {
                customSounds.remove(index);
            }
        }
        saveCustomSounds(appContext, customSounds);
        SharedPreferences preferences = prefs(appContext);
        SharedPreferences.Editor editor = preferences.edit();
        if (sound.equals(preferences.getString(ALERT_SOUND, DEFAULT_SOUND))) {
            editor.putString(ALERT_SOUND, DEFAULT_SOUND);
        }
        editor.apply();
        configureNotificationChannel(appContext);
    }

    static void applyImportedSound(Context context, String sound) {
        if (!isBuiltInSound(sound)) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(CLOUD_ALERT_SOUND, sound);
        if (!isCustomSound(getSavedSound(context))) {
            editor.putString(ALERT_SOUND, sound);
        }
        editor.apply();
        if (!isCustomSound(getSavedSound(context))) {
            configureNotificationChannel(context);
        }
    }

    static String getCurrentLabel(Context context) {
        return getLabel(context, getSavedSound(context));
    }

    static String getProfileSummary(Context context) {
        return getLabel(context, getSavedSound(context));
    }

    static String getLabel(Context context, String sound) {
        if (isCustomSound(sound) && hasCustomSound(context, sound)) {
            return getCustomDisplayName(context, sound);
        }
        return context.getString(getLabelResource(sound));
    }

    static String getCustomOptionLabel(Context context) {
        return context.getString(R.string.alert_sound_custom_add);
    }

    static String getCustomDisplayName(Context context, String sound) {
        CustomSound customSound = findCustomSound(context, sound);
        if (customSound == null) {
            return context.getString(R.string.alert_sound_custom_empty);
        }
        return customSound.name;
    }

    static boolean hasCustomSound(Context context) {
        return !getCustomSounds(context).isEmpty();
    }

    static boolean hasCustomSound(Context context, String sound) {
        return findCustomSound(context, sound) != null;
    }

    static List<CustomSound> getCustomSounds(Context context) {
        Context appContext = context.getApplicationContext();
        List<CustomSound> customSounds = readCustomSounds(appContext);
        File legacySound = getLegacyCustomSoundFile(appContext);
        if (legacySound.exists() && legacySound.length() > 0L && !containsCustomId(customSounds, "legacy")) {
            customSounds.add(0, new CustomSound(
                    "legacy",
                    prefs(appContext).getString(
                            LEGACY_CUSTOM_SOUND_NAME,
                            appContext.getString(R.string.alert_sound_custom_default_name)
                    ),
                    prefs(appContext).getLong(LEGACY_CUSTOM_SOUND_VERSION, legacySound.lastModified())
            ));
        }
        for (int index = customSounds.size() - 1; index >= 0; index--) {
            CustomSound sound = customSounds.get(index);
            File file = getCustomSoundFileById(appContext, sound.id);
            if (!file.exists() || file.length() <= 0L) {
                customSounds.remove(index);
            }
        }
        return customSounds;
    }

    static int getLabelResource(String sound) {
        int index = findIndex(normalizeBuiltIn(sound));
        return LABELS[index];
    }

    static int getRawResource(String sound) {
        int index = findIndex(normalizeBuiltIn(sound));
        return RAW_RESOURCES[index];
    }

    static Uri getSoundUri(Context context) {
        return getSoundUri(context, getSavedSound(context));
    }

    static Uri getSoundUri(Context context, String sound) {
        if (isCustomSound(sound)) {
            return AlertSoundProvider.getUri(context, sound);
        }
        return new Uri.Builder()
                .scheme("android.resource")
                .authority(context.getPackageName())
                .appendPath("raw")
                .appendPath(normalizeBuiltIn(sound))
                .build();
    }

    static String getChannelId(Context context) {
        String savedSound = getSavedSound(context);
        if (isCustomSound(savedSound)) {
            CustomSound customSound = findCustomSound(context, savedSound);
            long version = customSound == null ? 0L : customSound.version;
            return CHANNEL_PREFIX + customId(savedSound) + "_" + version;
        }
        return CHANNEL_PREFIX + savedSound;
    }

    static File getCustomSoundFile(Context context, String sound) {
        return getCustomSoundFileById(context.getApplicationContext(), customId(sound));
    }

    static File getCustomSoundFile(Context context) {
        return getLegacyCustomSoundFile(context.getApplicationContext());
    }

    static void configureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationManager manager = (NotificationManager) appContext.getSystemService(
                Context.NOTIFICATION_SERVICE
        );
        if (manager == null) {
            return;
        }
        String selectedChannel = getChannelId(appContext);
        for (NotificationChannel existing : manager.getNotificationChannels()) {
            String id = existing.getId();
            if ((LEGACY_CHANNEL.equals(id) || id.startsWith(CHANNEL_PREFIX))
                    && !selectedChannel.equals(id)) {
                manager.deleteNotificationChannel(id);
            }
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel channel = new NotificationChannel(
                selectedChannel,
                appContext.getString(R.string.offer_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(appContext.getString(R.string.offer_channel_description));
        channel.setSound(getSoundUri(appContext), attributes);
        manager.createNotificationChannel(channel);
    }

    private static List<CustomSound> readCustomSounds(Context context) {
        List<CustomSound> customSounds = new ArrayList<>();
        String json = prefs(context).getString(CUSTOM_SOUND_LIST, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "").trim();
                String name = item.optString(
                        "name",
                        context.getString(R.string.alert_sound_custom_default_name)
                ).trim();
                long version = item.optLong("version", 0L);
                if (!id.isEmpty()) {
                    customSounds.add(new CustomSound(
                            id,
                            name.isEmpty()
                                    ? context.getString(R.string.alert_sound_custom_default_name)
                                    : name,
                            version
                    ));
                }
            }
        } catch (JSONException ignored) {
        }
        return customSounds;
    }

    private static void saveCustomSounds(Context context, List<CustomSound> customSounds) {
        JSONArray array = new JSONArray();
        for (CustomSound sound : customSounds) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", sound.id);
                item.put("name", sound.name);
                item.put("version", sound.version);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(CUSTOM_SOUND_LIST, array.toString()).apply();
    }

    private static boolean containsCustomId(List<CustomSound> customSounds, String id) {
        for (CustomSound sound : customSounds) {
            if (sound.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static CustomSound findCustomSound(Context context, String sound) {
        if (!isCustomSound(sound)) {
            return null;
        }
        String id = customId(sound);
        for (CustomSound customSound : getCustomSounds(context)) {
            if (customSound.id.equals(id)) {
                return customSound;
            }
        }
        return null;
    }

    private static String createCustomSoundId(Context context, long timestamp) {
        long candidate = timestamp;
        while (getCustomSoundFileById(context, Long.toString(candidate)).exists()) {
            candidate++;
        }
        return Long.toString(candidate);
    }

    private static String customKey(String id) {
        return CUSTOM_SOUND + ":" + id;
    }

    private static String customId(String sound) {
        if (!isCustomSound(sound)) {
            return "legacy";
        }
        String id = sound.substring((CUSTOM_SOUND + ":").length()).trim();
        if (id.isEmpty()) {
            return "legacy";
        }
        for (int index = 0; index < id.length(); index++) {
            if (!Character.isDigit(id.charAt(index))) {
                return "invalid";
            }
        }
        return id;
    }

    private static File getCustomSoundFileById(Context context, String id) {
        if ("legacy".equals(id)) {
            return getLegacyCustomSoundFile(context);
        }
        return new File(
                new File(context.getApplicationContext().getFilesDir(), CUSTOM_SOUND_DIR),
                CUSTOM_SOUND_FILE_PREFIX + id
        );
    }

    private static File getLegacyCustomSoundFile(Context context) {
        return new File(
                new File(context.getApplicationContext().getFilesDir(), CUSTOM_SOUND_DIR),
                LEGACY_CUSTOM_SOUND_FILE
        );
    }

    private static String normalizeBuiltIn(String sound) {
        return VALID_KEYS.contains(sound) ? sound : DEFAULT_SOUND;
    }

    private static int findIndex(String sound) {
        for (int index = 0; index < KEYS.length; index++) {
            if (KEYS[index].equals(sound)) {
                return index;
            }
        }
        return 0;
    }

    private static String readDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return context.getString(R.string.alert_sound_custom_default_name);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class CustomSound {
        private final String id;
        private final String name;
        private final long version;

        CustomSound(String id, String name, long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        String getKey() {
            return customKey(id);
        }

        String getName() {
            return name;
        }
    }
}
