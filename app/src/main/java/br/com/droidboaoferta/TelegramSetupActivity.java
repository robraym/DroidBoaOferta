package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TelegramSetupActivity extends AppCompatActivity implements TelegramClientManager.Listener {
    private static final String PREFS = "telegram_preferences";
    private static final String PREF_SELECTED_GROUPS = "selected_groups";

    private TelegramClientManager clientManager;
    private TextView statusText;
    private TextView instructionsText;
    private EditText authenticationInput;
    private Button continueButton;
    private LinearLayout statusSection;
    private ScrollView groupsScroll;
    private LinearLayout groupsContainer;
    private Button saveGroupsButton;
    private Set<String> selectedGroupIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telegram_setup);

        statusText = findViewById(R.id.text_telegram_status);
        instructionsText = findViewById(R.id.text_telegram_instructions);
        authenticationInput = findViewById(R.id.input_authentication);
        continueButton = findViewById(R.id.button_continue);
        statusSection = findViewById(R.id.section_telegram_status);
        groupsScroll = findViewById(R.id.scroll_groups);
        groupsContainer = findViewById(R.id.container_groups);
        saveGroupsButton = findViewById(R.id.button_save_groups);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        saveGroupsButton.setOnClickListener(view -> saveSelectedGroups());
        continueButton.setOnClickListener(view -> submitAuthenticationValue());

        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        selectedGroupIds = new HashSet<>(preferences.getStringSet(
                PREF_SELECTED_GROUPS,
                new HashSet<>()
        ));

        clientManager = TelegramClientManager.getInstance();
    }

    @Override
    protected void onStart() {
        super.onStart();
        clientManager.setListener(this);
        clientManager.start(this);
        clientManager.refreshGroups();
    }

    @Override
    protected void onStop() {
        clientManager.clearListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TelegramClientManager.State state) {
        runOnUiThread(() -> renderState(state));
    }

    @Override
    public void onGroupsLoaded(List<TelegramGroup> groups) {
        runOnUiThread(() -> renderGroups(groups));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(
                this,
                getString(R.string.telegram_error_format, message),
                Toast.LENGTH_LONG
        ).show());
    }

    private void renderState(TelegramClientManager.State state) {
        boolean ready = state == TelegramClientManager.State.READY;
        statusSection.setVisibility(ready ? View.GONE : View.VISIBLE);
        groupsScroll.setVisibility(ready ? View.VISIBLE : View.GONE);
        saveGroupsButton.setVisibility(ready ? View.VISIBLE : View.GONE);

        switch (state) {
            case STARTING:
                showStatus(R.string.telegram_status_connecting, R.string.telegram_wait_message);
                hideAuthenticationInput();
                break;
            case MISSING_CREDENTIALS:
                showStatus(R.string.telegram_status_credentials, R.string.telegram_credentials_message);
                hideAuthenticationInput();
                break;
            case WAITING_PHONE:
                showStatus(R.string.telegram_status_login, R.string.telegram_phone_message);
                showAuthenticationInput(
                        R.string.telegram_phone_hint,
                        InputType.TYPE_CLASS_PHONE,
                        R.string.action_continue
                );
                break;
            case WAITING_EMAIL:
                showStatus(R.string.telegram_status_login, R.string.telegram_email_message);
                showAuthenticationInput(
                        R.string.telegram_email_hint,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        R.string.action_continue
                );
                break;
            case WAITING_EMAIL_CODE:
                showStatus(R.string.telegram_status_verification, R.string.telegram_email_code_message);
                showAuthenticationInput(
                        R.string.telegram_code_hint,
                        InputType.TYPE_CLASS_NUMBER,
                        R.string.action_confirm
                );
                break;
            case WAITING_CODE:
                showStatus(R.string.telegram_status_verification, R.string.telegram_code_message);
                showAuthenticationInput(
                        R.string.telegram_code_hint,
                        InputType.TYPE_CLASS_NUMBER,
                        R.string.action_confirm
                );
                break;
            case WAITING_PASSWORD:
                showStatus(R.string.telegram_status_password, R.string.telegram_password_message);
                showAuthenticationInput(
                        R.string.telegram_password_hint,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        R.string.action_enter
                );
                break;
            case READY:
                showStatus(R.string.telegram_status_connected, R.string.telegram_groups_message);
                hideAuthenticationInput();
                break;
            case CLOSED:
                showStatus(R.string.telegram_status_disconnected, R.string.telegram_disconnected_message);
                hideAuthenticationInput();
                break;
            case UNSUPPORTED_AUTHORIZATION:
                showStatus(R.string.telegram_status_attention, R.string.telegram_unsupported_auth_message);
                hideAuthenticationInput();
                break;
        }
    }

    private void renderGroups(List<TelegramGroup> groups) {
        groupsContainer.removeAllViews();
        if (groups.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.telegram_no_groups);
            emptyView.setTextColor(getColor(R.color.text_secondary));
            emptyView.setTextSize(14);
            groupsContainer.addView(emptyView);
            return;
        }

        for (int index = 0; index < groups.size(); index++) {
            TelegramGroup group = groups.get(index);
            CheckBox checkBox = new CheckBox(this);
            String groupId = Long.toString(group.getId());
            checkBox.setTag(groupId);
            checkBox.setText(group.getTitle());
            checkBox.setTextColor(getColor(R.color.text_primary));
            checkBox.setTextSize(14.5f);
            checkBox.setButtonTintList(getColorStateList(R.color.selector_checkbox));
            checkBox.setMinHeight(dp(44));
            checkBox.setPadding(dp(6), 0, dp(6), 0);
            checkBox.setChecked(selectedGroupIds.contains(groupId));
            groupsContainer.addView(checkBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            if (index < groups.size() - 1) {
                groupsContainer.addView(createDivider());
            }
        }
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(42);
        params.rightMargin = dp(6);
        divider.setLayoutParams(params);
        return divider;
    }

    private void submitAuthenticationValue() {
        String value = authenticationInput.getText().toString().trim();
        if (value.isEmpty()) {
            authenticationInput.setError(getString(R.string.telegram_required_field));
            return;
        }

        switch (clientManager.getState()) {
            case WAITING_PHONE:
                clientManager.submitPhoneNumber(value.replaceAll("[^+0-9]", ""));
                break;
            case WAITING_EMAIL:
                clientManager.submitEmail(value);
                break;
            case WAITING_EMAIL_CODE:
                clientManager.submitEmailCode(value);
                break;
            case WAITING_CODE:
                clientManager.submitCode(value);
                break;
            case WAITING_PASSWORD:
                clientManager.submitPassword(value);
                break;
            default:
                return;
        }
        authenticationInput.setText("");
        continueButton.setEnabled(false);
        continueButton.postDelayed(() -> continueButton.setEnabled(true), 1200);
    }

    private void saveSelectedGroups() {
        Set<String> selected = new HashSet<>();
        for (int index = 0; index < groupsContainer.getChildCount(); index++) {
            View child = groupsContainer.getChildAt(index);
            if (child instanceof CheckBox && ((CheckBox) child).isChecked()) {
                selected.add((String) child.getTag());
            }
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_SELECTED_GROUPS, selected)
                .apply();
        selectedGroupIds = selected;
        TelegramClientManager.getInstance().refreshSelectedGroupsHistory();
        Toast.makeText(this, R.string.telegram_groups_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showStatus(int statusResource, int messageResource) {
        statusText.setText(statusResource);
        instructionsText.setText(messageResource);
    }

    private void showAuthenticationInput(int hintResource, int inputType, int buttonTextResource) {
        authenticationInput.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        authenticationInput.setHint(hintResource);
        authenticationInput.setInputType(inputType);
        continueButton.setText(buttonTextResource);
    }

    private void hideAuthenticationInput() {
        authenticationInput.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
