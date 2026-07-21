package br.com.droidboaoferta;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

abstract class AlertouActivity extends AppCompatActivity {
    private String appliedAccentMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        appliedAccentMode = AccentColorController.getSavedMode(this);
        AccentColorController.apply(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String savedAccentMode = AccentColorController.getSavedMode(this);
        if (!savedAccentMode.equals(appliedAccentMode)) {
            recreate();
        }
    }
}
