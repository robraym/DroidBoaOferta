package br.com.droidboaoferta;

import android.app.Application;

public class BoaOfertaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeController.applySavedTheme(this);
    }
}
