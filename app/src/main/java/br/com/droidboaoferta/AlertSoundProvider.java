package br.com.droidboaoferta;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

public final class AlertSoundProvider extends ContentProvider {
    private static final String PATH_SOUND = "sound";

    static Uri getUri(Context context) {
        return getUri(context, AlertSoundController.getSavedSound(context));
    }

    static Uri getUri(Context context, String sound) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.getPackageName() + ".alertsound")
                .appendPath(PATH_SOUND)
                .appendPath(sound)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/*";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.myUid() && callingUid != Process.SYSTEM_UID) {
            throw new FileNotFoundException("Acesso ao som não permitido.");
        }
        List<String> pathSegments = uri.getPathSegments();
        if (pathSegments.isEmpty() || !PATH_SOUND.equals(pathSegments.get(0))) {
            throw new FileNotFoundException("Som não encontrado.");
        }
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Contexto indisponível.");
        }
        String soundKey = pathSegments.size() > 1
                ? pathSegments.get(1)
                : AlertSoundController.getSavedSound(context);
        if (!AlertSoundController.hasCustomSound(context, soundKey)) {
            throw new FileNotFoundException("Som personalizado não encontrado.");
        }
        File sound = AlertSoundController.getCustomSoundFile(context, soundKey);
        if (!sound.exists() || sound.length() <= 0L) {
            throw new FileNotFoundException("Som personalizado não encontrado.");
        }
        return ParcelFileDescriptor.open(sound, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
