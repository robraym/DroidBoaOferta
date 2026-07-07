package br.com.droidboaoferta;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TelegramSetupActivity extends AppCompatActivity implements TelegramClientManager.Listener {
    private static final String TAG = "TelegramSetup";
    private static final String PREFS = "telegram_preferences";
    private static final String PREF_SELECTED_GROUPS = "selected_groups";
    private static final String PREF_LAST_PHONE = "last_phone";

    private TelegramClientManager clientManager;
    private TextView statusText;
    private TextView instructionsText;
    private EditText authenticationInput;
    private LinearLayout phoneInputRow;
    private TextView countryPickerButton;
    private EditText phoneNumberInput;
    private Button continueButton;
    private Button receiveSmsButton;
    private LinearLayout statusSection;
    private View loginSpacer;
    private ScrollView groupsScroll;
    private LinearLayout groupsContainer;
    private TextView groupsCountText;
    private LinearLayout groupsSearchBar;
    private EditText groupsSearchInput;
    private List<TelegramGroup> availableGroups = Collections.emptyList();
    private Set<String> selectedGroupIds;
    private ActivityResultLauncher<IntentSenderRequest> phoneNumberHintLauncher;
    private ActivityResultLauncher<Intent> smsConsentLauncher;
    private boolean automaticPhoneHintRequested;
    private final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
    private List<CountryOption> countryOptions = Collections.emptyList();
    private CountryOption selectedCountry;
    private boolean formattingPhoneNumber;
    private final Handler smsHandler = new Handler(Looper.getMainLooper());
    private boolean smsReceiverRegistered;
    private boolean smsConsentListening;
    private final Runnable smsCountdownRunnable = this::renderSmsOption;
    private final BroadcastReceiver smsVerificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())
                    || intent.getExtras() == null) {
                return;
            }
            Status status = intent.getExtras().getParcelable(SmsRetriever.EXTRA_STATUS);
            if (status == null) {
                return;
            }
            if (status.getStatusCode() == CommonStatusCodes.SUCCESS) {
                Intent consentIntent = intent.getExtras()
                        .getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT);
                if (consentIntent != null) {
                    try {
                        smsConsentLauncher.launch(consentIntent);
                    } catch (ActivityNotFoundException exception) {
                        handleSmsConsentFailure(exception);
                    }
                }
            } else if (status.getStatusCode() == CommonStatusCodes.TIMEOUT) {
                smsConsentListening = false;
                renderSmsOption();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telegram_setup);
        BottomNavigationController.setup(this, BottomNavigationController.ITEM_SOURCES);

        statusText = findViewById(R.id.text_telegram_status);
        instructionsText = findViewById(R.id.text_telegram_instructions);
        authenticationInput = findViewById(R.id.input_authentication);
        phoneInputRow = findViewById(R.id.row_phone_input);
        countryPickerButton = findViewById(R.id.button_country_picker);
        phoneNumberInput = findViewById(R.id.input_phone_number);
        continueButton = findViewById(R.id.button_continue);
        receiveSmsButton = findViewById(R.id.button_receive_sms);
        statusSection = findViewById(R.id.section_telegram_status);
        loginSpacer = findViewById(R.id.spacer_telegram_login);
        groupsScroll = findViewById(R.id.scroll_groups);
        groupsContainer = findViewById(R.id.container_groups);
        groupsCountText = findViewById(R.id.text_groups_count);
        groupsSearchBar = findViewById(R.id.search_groups_bar);
        groupsSearchInput = findViewById(R.id.input_search_groups);

        findViewById(R.id.button_profile).setOnClickListener(view -> startActivity(
                new Intent(this, ProfileActivity.class)
        ));
        continueButton.setOnClickListener(view -> submitAuthenticationValue());
        receiveSmsButton.setOnClickListener(view -> startSmsConsentListening(true));
        countryPickerButton.setOnClickListener(view -> showCountryPicker());
        phoneNumberInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                formatPhoneNumber();
            }
        });
        countryOptions = createCountryOptions();
        selectCountry(findInitialCountryRegion());
        phoneNumberHintLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        return;
                    }
                    try {
                        String phoneNumber = Identity.getSignInClient(this)
                                .getPhoneNumberFromIntent(result.getData());
                        applyInternationalPhoneNumber(phoneNumber);
                    } catch (Exception exception) {
                        Log.w(TAG, "Phone Number Hint result could not be read", exception);
                    }
                }
        );
        smsConsentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    smsConsentListening = false;
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        renderSmsOption();
                        return;
                    }
                    String message = result.getData().getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE);
                    String code = extractAuthenticationCode(message);
                    if (code == null) {
                        renderSmsOption();
                        return;
                    }
                    authenticationInput.setText(code);
                    authenticationInput.setSelection(code.length());
                    continueButton.setEnabled(false);
                    receiveSmsButton.setEnabled(false);
                    clientManager.submitCode(code);
                }
        );
        groupsSearchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                renderGroups(availableGroups);
            }
        });

        loadSelectedGroupsFromPreferences();

        clientManager = TelegramClientManager.getInstance();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerSmsReceiver();
        clientManager.setListener(this);
        clientManager.start(this);
        clientManager.refreshGroups();
        clientManager.refreshCloudBackupSoon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationController.resetInitialFocus(this);
    }

    @Override
    protected void onStop() {
        smsHandler.removeCallbacks(smsCountdownRunnable);
        unregisterSmsReceiver();
        clientManager.clearListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TelegramClientManager.State state) {
        runOnUiThread(() -> renderState(state));
    }

    @Override
    public void onGroupsLoaded(List<TelegramGroup> groups) {
        runOnUiThread(() -> {
            loadSelectedGroupsFromPreferences();
            renderGroups(groups);
        });
    }

    private void renderState(TelegramClientManager.State state) {
        boolean ready = state == TelegramClientManager.State.READY;
        authenticationInput.setFilters(new InputFilter[0]);
        statusSection.setVisibility(ready ? View.GONE : View.VISIBLE);
        loginSpacer.setVisibility(ready ? View.GONE : View.VISIBLE);
        groupsScroll.setVisibility(ready ? View.VISIBLE : View.GONE);
        groupsSearchBar.setVisibility(ready ? View.VISIBLE : View.GONE);
        receiveSmsButton.setVisibility(View.GONE);
        smsHandler.removeCallbacks(smsCountdownRunnable);

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
                showPhoneAuthenticationInput();
                enablePhoneNumberAutofill();
                prefillLastPhoneNumber();
                if (!automaticPhoneHintRequested) {
                    automaticPhoneHintRequested = true;
                    authenticationInput.post(this::requestPhoneNumberHint);
                }
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
                focusAuthenticationInputAndShowKeyboard();
                break;
            case WAITING_CODE:
                showStatus(R.string.telegram_status_verification, R.string.telegram_code_message);
                showAuthenticationInput(
                        R.string.telegram_code_hint,
                        InputType.TYPE_CLASS_NUMBER,
                        R.string.action_confirm
                );
                int codeLength = clientManager.getAuthenticationCodeLength();
                authenticationInput.setFilters(new InputFilter[]{
                        new InputFilter.LengthFilter(codeLength > 0 ? codeLength : 10)
                });
                focusAuthenticationInputAndShowKeyboard();
                renderSmsOption();
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
                showReconnectAction();
                break;
            case UNSUPPORTED_AUTHORIZATION:
                showStatus(R.string.telegram_status_attention, R.string.telegram_unsupported_auth_message);
                hideAuthenticationInput();
                break;
        }
    }

    private void renderGroups(List<TelegramGroup> groups) {
        availableGroups = groups;
        updateGroupsCountSummary();
        List<TelegramGroup> visibleGroups = filterGroups(groups, groupsSearchInput.getText().toString());
        groupsContainer.removeAllViews();
        if (visibleGroups.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.telegram_no_groups);
            emptyView.setTextColor(getColor(R.color.text_secondary));
            emptyView.setTextSize(14);
            groupsContainer.addView(emptyView);
            return;
        }

        for (int index = 0; index < visibleGroups.size(); index++) {
            TelegramGroup group = visibleGroups.get(index);
            String groupId = Long.toString(group.getId());
            CheckBox checkBox = new CheckBox(this);
            checkBox.setTag(groupId);
            checkBox.setButtonTintList(getColorStateList(R.color.selector_checkbox));
            checkBox.setGravity(android.view.Gravity.CENTER);
            checkBox.setPadding(0, 0, 0, 0);
            checkBox.setChecked(selectedGroupIds.contains(groupId));

            TextView label = new TextView(this);
            label.setText(group.getTitle());
            label.setTextColor(getColor(R.color.text_primary));
            label.setTextSize(14);
            label.setSingleLine(false);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(getColor(R.color.card));
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(6), dp(7), dp(6), dp(7));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> checkBox.setChecked(!checkBox.isChecked()));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedGroupIds.add(groupId);
                } else {
                    selectedGroupIds.remove(groupId);
                }
                persistSelectedGroups();
                updateGroupsCountSummary();
            });
            row.addView(checkBox, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            labelParams.leftMargin = dp(8);
            row.addView(label, labelParams);
            groupsContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            if (index < visibleGroups.size() - 1) {
                groupsContainer.addView(createDivider());
            }
        }
    }

    private void updateGroupsCountSummary() {
        int totalGroups = availableGroups == null ? 0 : availableGroups.size();
        int selectedGroups = countSelectedAvailableGroups();
        String selectedText = getResources().getQuantityString(
                R.plurals.telegram_groups_selected_count,
                selectedGroups,
                selectedGroups
        );
        String totalText = getResources().getQuantityString(
                R.plurals.telegram_groups_total_count,
                totalGroups,
                totalGroups
        );
        groupsCountText.setText(getString(R.string.telegram_groups_count_format,
                selectedText,
                totalText
        ));
    }

    private int countSelectedAvailableGroups() {
        if (availableGroups == null || availableGroups.isEmpty()
                || selectedGroupIds == null || selectedGroupIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TelegramGroup group : availableGroups) {
            if (selectedGroupIds.contains(Long.toString(group.getId()))) {
                count++;
            }
        }
        return count;
    }

    private List<TelegramGroup> filterGroups(List<TelegramGroup> groups, String query) {
        String normalizedQuery = OfferTextParser.normalize(query);
        if (normalizedQuery.isEmpty()) {
            return groups;
        }
        List<TelegramGroup> filtered = new java.util.ArrayList<>();
        for (TelegramGroup group : groups) {
            if (OfferTextParser.normalize(group.getTitle()).contains(normalizedQuery)) {
                filtered.add(group);
            }
        }
        return filtered;
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(46);
        params.rightMargin = dp(6);
        divider.setLayoutParams(params);
        return divider;
    }

    private void submitAuthenticationValue() {
        if (clientManager.getState() == TelegramClientManager.State.CLOSED) {
            continueButton.setEnabled(false);
            clientManager.reconnect(this);
            continueButton.postDelayed(() -> continueButton.setEnabled(true), 1200);
            return;
        }
        boolean waitingForPhone = clientManager.getState() == TelegramClientManager.State.WAITING_PHONE;
        String value = waitingForPhone
                ? phoneNumberInput.getText().toString().trim()
                : authenticationInput.getText().toString().trim();
        if (value.isEmpty()) {
            (waitingForPhone ? phoneNumberInput : authenticationInput)
                    .setError(getString(R.string.telegram_required_field));
            return;
        }

        switch (clientManager.getState()) {
            case WAITING_PHONE:
                String localNumber = value.replaceAll("[^0-9]", "");
                String phoneNumber = value.startsWith("+")
                        ? "+" + localNumber
                        : "+" + selectedCountry.callingCode + localNumber;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_LAST_PHONE, phoneNumber)
                        .apply();
                clientManager.submitPhoneNumber(phoneNumber);
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
        if (waitingForPhone) {
            phoneNumberInput.setText("");
        } else {
            authenticationInput.setText("");
        }
        continueButton.setEnabled(false);
        continueButton.postDelayed(() -> continueButton.setEnabled(true), 1200);
    }

    private void requestPhoneNumberHint() {
        if (clientManager.getState() != TelegramClientManager.State.WAITING_PHONE) {
            return;
        }
        GetPhoneNumberHintIntentRequest request = GetPhoneNumberHintIntentRequest.builder().build();
        Identity.getSignInClient(this)
                .getPhoneNumberHintIntent(request)
                .addOnSuccessListener(result -> {
                    try {
                        phoneNumberHintLauncher.launch(
                                new IntentSenderRequest.Builder(result.getIntentSender()).build()
                        );
                    } catch (Exception exception) {
                        Log.w(TAG, "Phone Number Hint is unavailable", exception);
                    }
                })
                .addOnFailureListener(exception ->
                        Log.w(TAG, "Phone Number Hint is unavailable", exception));
    }

    private void registerSmsReceiver() {
        if (smsReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION);
        ContextCompat.registerReceiver(
                this,
                smsVerificationReceiver,
                filter,
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
        );
        smsReceiverRegistered = true;
    }

    private void unregisterSmsReceiver() {
        if (!smsReceiverRegistered) {
            return;
        }
        unregisterReceiver(smsVerificationReceiver);
        smsReceiverRegistered = false;
        smsConsentListening = false;
    }

    private void renderSmsOption() {
        smsHandler.removeCallbacks(smsCountdownRunnable);
        if (clientManager == null
                || clientManager.getState() != TelegramClientManager.State.WAITING_CODE) {
            receiveSmsButton.setVisibility(View.GONE);
            return;
        }
        if (clientManager.isCurrentCodeSentBySms()) {
            receiveSmsButton.setVisibility(View.VISIBLE);
            receiveSmsButton.setEnabled(false);
            receiveSmsButton.setText(R.string.telegram_waiting_sms);
            if (!smsConsentListening) {
                startSmsConsentListening(false);
            }
            return;
        }
        if (!clientManager.isNextCodeAvailableBySms()) {
            receiveSmsButton.setVisibility(View.GONE);
            return;
        }
        receiveSmsButton.setVisibility(View.VISIBLE);
        long delayMillis = clientManager.getNextCodeDelayMillis();
        if (delayMillis > 0L) {
            long seconds = (delayMillis + 999L) / 1000L;
            receiveSmsButton.setEnabled(false);
            receiveSmsButton.setText(getString(
                    R.string.telegram_receive_sms_countdown,
                    seconds
            ));
            smsHandler.postDelayed(smsCountdownRunnable, Math.min(1000L, delayMillis));
        } else {
            receiveSmsButton.setEnabled(true);
            receiveSmsButton.setText(R.string.telegram_receive_sms);
        }
    }

    private void startSmsConsentListening(boolean requestSmsCode) {
        receiveSmsButton.setVisibility(View.VISIBLE);
        receiveSmsButton.setEnabled(false);
        receiveSmsButton.setText(R.string.telegram_waiting_sms);
        SmsRetriever.getClient(this)
                .startSmsUserConsent(null)
                .addOnSuccessListener(ignored -> {
                    smsConsentListening = true;
                    if (requestSmsCode) {
                        clientManager.requestAuthenticationCodeBySms();
                    }
                })
                .addOnFailureListener(this::handleSmsConsentFailure);
    }

    private void handleSmsConsentFailure(Exception exception) {
        smsConsentListening = false;
        Log.w(TAG, "SMS User Consent is unavailable", exception);
        renderSmsOption();
    }

    private String extractAuthenticationCode(String message) {
        if (message == null) {
            return null;
        }
        int expectedLength = clientManager.getAuthenticationCodeLength();
        String expression = expectedLength >= 4 && expectedLength <= 10
                ? "(?<!\\d)\\d{" + expectedLength + "}(?!\\d)"
                : "(?<!\\d)\\d{4,10}(?!\\d)";
        Matcher matcher = Pattern.compile(expression).matcher(message);
        return matcher.find() ? matcher.group() : null;
    }

    private void enablePhoneNumberAutofill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            phoneNumberInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            phoneNumberInput.setAutofillHints("phoneNumber");
        }
    }

    private void prefillLastPhoneNumber() {
        if (!phoneNumberInput.getText().toString().trim().isEmpty()) {
            return;
        }
        String lastPhone = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LAST_PHONE, "");
        if (lastPhone == null || lastPhone.isEmpty()) {
            return;
        }
        applyInternationalPhoneNumber(lastPhone);
    }

    private void showPhoneAuthenticationInput() {
        authenticationInput.setVisibility(View.GONE);
        phoneInputRow.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setText(R.string.action_continue);
    }

    private void applyInternationalPhoneNumber(String phoneNumber) {
        try {
            Phonenumber.PhoneNumber parsed = phoneNumberUtil.parse(phoneNumber, null);
            String region = phoneNumberUtil.getRegionCodeForNumber(parsed);
            if (region != null) {
                selectCountry(region);
            }
            String nationalNumber = phoneNumberUtil.getNationalSignificantNumber(parsed);
            phoneNumberInput.setText(nationalNumber);
            phoneNumberInput.setSelection(phoneNumberInput.length());
        } catch (NumberParseException exception) {
            String digits = phoneNumber.replaceAll("[^0-9]", "");
            phoneNumberInput.setText(digits);
            phoneNumberInput.setSelection(phoneNumberInput.length());
        }
    }

    private void formatPhoneNumber() {
        if (formattingPhoneNumber || selectedCountry == null) {
            return;
        }
        String rawValue = phoneNumberInput.getText().toString();
        String digits = rawValue.replaceAll("[^0-9]", "");
        try {
            Phonenumber.PhoneNumber internationalNumber = null;
            if (rawValue.trim().startsWith("+")) {
                internationalNumber = phoneNumberUtil.parse(rawValue, null);
            } else if (digits.startsWith(Integer.toString(selectedCountry.callingCode))) {
                Phonenumber.PhoneNumber possibleInternational = phoneNumberUtil.parse("+" + digits, null);
                if (phoneNumberUtil.isPossibleNumber(possibleInternational)) {
                    internationalNumber = possibleInternational;
                }
            }
            if (internationalNumber != null) {
                String detectedRegion = phoneNumberUtil.getRegionCodeForNumber(internationalNumber);
                if (detectedRegion != null) {
                    formattingPhoneNumber = true;
                    selectCountry(detectedRegion);
                    formattingPhoneNumber = false;
                }
                digits = phoneNumberUtil.getNationalSignificantNumber(internationalNumber);
            }
        } catch (NumberParseException ignored) {
            // Continue treating the value as a national number.
        }
        int absoluteMaximum = selectedCountry.maximumNationalDigits;
        if (digits.length() > absoluteMaximum) {
            digits = digits.substring(0, absoluteMaximum);
        }
        while (!digits.isEmpty() && isPhoneNumberTooLong(digits)) {
            digits = digits.substring(0, digits.length() - 1);
        }

        String formatted = "";
        com.google.i18n.phonenumbers.AsYouTypeFormatter formatter =
                phoneNumberUtil.getAsYouTypeFormatter(selectedCountry.region);
        for (int index = 0; index < digits.length(); index++) {
            formatted = formatter.inputDigit(digits.charAt(index));
        }
        if (!formatted.equals(phoneNumberInput.getText().toString())) {
            formattingPhoneNumber = true;
            phoneNumberInput.setText(formatted);
            phoneNumberInput.setSelection(formatted.length());
            formattingPhoneNumber = false;
        }
    }

    private boolean isPhoneNumberTooLong(String nationalDigits) {
        try {
            Phonenumber.PhoneNumber parsed = phoneNumberUtil.parse(
                    "+" + selectedCountry.callingCode + nationalDigits,
                    null
            );
            return phoneNumberUtil.isPossibleNumberWithReason(parsed)
                    == PhoneNumberUtil.ValidationResult.TOO_LONG;
        } catch (NumberParseException exception) {
            return false;
        }
    }

    private List<CountryOption> createCountryOptions() {
        List<CountryOption> options = new ArrayList<>();
        for (String region : phoneNumberUtil.getSupportedRegions()) {
            int callingCode = phoneNumberUtil.getCountryCodeForRegion(region);
            if (callingCode <= 0) {
                continue;
            }
            Locale countryLocale = new Locale("", region);
            String displayName = countryLocale.getDisplayCountry(new Locale("pt", "BR"));
            options.add(new CountryOption(
                    region,
                    callingCode,
                    displayName,
                    findMaximumNationalDigits(region, callingCode)
            ));
        }
        Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        options.sort((first, second) -> collator.compare(first.displayName, second.displayName));
        CountryOption brazil = null;
        for (CountryOption option : options) {
            if ("BR".equals(option.region)) {
                brazil = option;
                break;
            }
        }
        if (brazil != null) {
            options.remove(brazil);
            options.add(0, brazil);
        }
        return options;
    }

    private int findMaximumNationalDigits(String region, int callingCode) {
        int maximum = 0;
        PhoneNumberUtil.PhoneNumberType[] relevantTypes = {
                PhoneNumberUtil.PhoneNumberType.MOBILE,
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE,
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE
        };
        for (PhoneNumberUtil.PhoneNumberType type : relevantTypes) {
            Phonenumber.PhoneNumber example = phoneNumberUtil.getExampleNumberForType(region, type);
            if (example != null) {
                maximum = Math.max(
                        maximum,
                        phoneNumberUtil.getNationalSignificantNumber(example).length()
                );
            }
        }
        int internationalMaximum = Math.max(1, 15 - Integer.toString(callingCode).length());
        return maximum > 0 ? Math.min(maximum, internationalMaximum) : internationalMaximum;
    }

    private String findInitialCountryRegion() {
        try {
            TelephonyManager telephonyManager =
                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                String simCountry = telephonyManager.getSimCountryIso();
                if (simCountry != null && !simCountry.isEmpty()) {
                    return simCountry.toUpperCase(Locale.ROOT);
                }
                String networkCountry = telephonyManager.getNetworkCountryIso();
                if (networkCountry != null && !networkCountry.isEmpty()) {
                    return networkCountry.toUpperCase(Locale.ROOT);
                }
            }
        } catch (SecurityException | UnsupportedOperationException exception) {
            Log.w(TAG, "Device country could not be read", exception);
        }
        String localeCountry = Locale.getDefault().getCountry();
        return localeCountry.isEmpty() ? "BR" : localeCountry.toUpperCase(Locale.ROOT);
    }

    private void showCountryPicker() {
        String[] labels = new String[countryOptions.size()];
        int selectedIndex = 0;
        for (int index = 0; index < countryOptions.size(); index++) {
            CountryOption option = countryOptions.get(index);
            labels[index] = option.getFullLabel();
            if (selectedCountry != null && selectedCountry.region.equals(option.region)) {
                selectedIndex = index;
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.telegram_country_picker_title)
                .setSingleChoiceItems(labels, selectedIndex, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    selectCountry(countryOptions.get(position).region);
                    dialog.dismiss();
                }
        ));
        dialog.show();
    }

    private void selectCountry(String region) {
        for (CountryOption option : countryOptions) {
            if (option.region.equalsIgnoreCase(region)) {
                selectedCountry = option;
                countryPickerButton.setText(option.getCompactLabel());
                formatPhoneNumber();
                return;
            }
        }
        if (!countryOptions.isEmpty() && selectedCountry == null) {
            selectedCountry = countryOptions.get(0);
            countryPickerButton.setText(selectedCountry.getCompactLabel());
            formatPhoneNumber();
        }
    }

    private void persistSelectedGroups() {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> previous = new HashSet<>(preferences.getStringSet(
                PREF_SELECTED_GROUPS,
                new HashSet<>()
        ));
        Set<String> selected = new HashSet<>(selectedGroupIds);
        preferences.edit()
                .putStringSet(PREF_SELECTED_GROUPS, selected)
                .apply();
        selectedGroupIds = selected;
        CloudSyncStore.rememberSelectedGroupsChanged(this, previous, selected);
    }

    private void loadSelectedGroupsFromPreferences() {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        selectedGroupIds = new HashSet<>(preferences.getStringSet(
                PREF_SELECTED_GROUPS,
                new HashSet<>()
        ));
    }

    private void showStatus(int statusResource, int messageResource) {
        statusText.setText(statusResource);
        instructionsText.setText(messageResource);
    }

    private void showAuthenticationInput(int hintResource, int inputType, int buttonTextResource) {
        phoneInputRow.setVisibility(View.GONE);
        authenticationInput.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        authenticationInput.setHint(hintResource);
        authenticationInput.setInputType(inputType);
        continueButton.setText(buttonTextResource);
    }

    private void focusAuthenticationInputAndShowKeyboard() {
        authenticationInput.post(() -> {
            if (authenticationInput.getVisibility() != View.VISIBLE) {
                return;
            }
            authenticationInput.requestFocus();
            authenticationInput.setSelection(authenticationInput.getText().length());
            showKeyboardForAuthenticationInput();
            authenticationInput.postDelayed(this::showKeyboardForAuthenticationInput, 250L);
        });
    }

    private void showKeyboardForAuthenticationInput() {
        if (authenticationInput.getVisibility() != View.VISIBLE || !authenticationInput.hasFocus()) {
            return;
        }
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(authenticationInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideAuthenticationInput() {
        phoneInputRow.setVisibility(View.GONE);
        authenticationInput.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
    }

    private void showReconnectAction() {
        phoneInputRow.setVisibility(View.GONE);
        authenticationInput.setVisibility(View.GONE);
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setText(R.string.action_reconnect_telegram);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class CountryOption {
        final String region;
        final int callingCode;
        final String displayName;
        final int maximumNationalDigits;

        CountryOption(
                String region,
                int callingCode,
                String displayName,
                int maximumNationalDigits
        ) {
            this.region = region;
            this.callingCode = callingCode;
            this.displayName = displayName;
            this.maximumNationalDigits = maximumNationalDigits;
        }

        String getCompactLabel() {
            return getFlagEmoji() + " " + displayName + "  +" + callingCode + "  ▾";
        }

        String getFullLabel() {
            return getFlagEmoji() + "  " + displayName + "  (+" + callingCode + ")";
        }

        private String getFlagEmoji() {
            int first = Character.codePointAt(region, 0) - 'A' + 0x1F1E6;
            int second = Character.codePointAt(region, 1) - 'A' + 0x1F1E6;
            return new String(Character.toChars(first)) + new String(Character.toChars(second));
        }
    }
}
