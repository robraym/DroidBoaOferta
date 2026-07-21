package br.com.droidboaoferta;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

abstract class AlertouActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AccentColorController.apply(this);
        super.onCreate(savedInstanceState);
    }
}
