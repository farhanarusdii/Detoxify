package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class ModeSelectionActivity extends AppCompatActivity {

    private CardView cardParent, cardChild;
    private LinearLayout loginLayout, signupLayout, connectLayout, codeGenerationLayout;

    private EditText etLoginEmail, etLoginPassword;
    private Button btnLogin;
    private TextView tvStatus;
    private TextView tvForgotPassword;

    private EditText etSignupEmail, etSignupPassword, etSignupConfirm;
    private Button btnSignup;

    private EditText etChildCode, etChildName;
    private Button btnConnectChild;

    private TextView tvChildCode;
    private EditText etChildNameParent;
    private Button btnDone;

    private SharedPreferences sharedPreferences;
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_selection);

        sharedPreferences = getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        authManager = AuthManager.getInstance(this);

        // Child devices stay linked without Firebase Auth — restore that session first.
        if (authManager.isChildConnected()) {
            navigateToChildFlow();
            return;
        }

        if (authManager.isLoggedIn()) {
            String userRole = sharedPreferences.getString("userRole", "");
            if ("parent".equals(userRole)) {
                navigateToParentChildPicker();
                return;
            }
            if ("child".equals(userRole)) {
                navigateToChildFlow();
                return;
            }
        }

        initViews();
        setupClickListeners();
        showModeSelection();
    }

    private void initViews() {
        cardParent = findViewById(R.id.card_parent);
        cardChild = findViewById(R.id.card_child);

        loginLayout = findViewById(R.id.loginLayout);
        signupLayout = findViewById(R.id.signupLayout);
        connectLayout = findViewById(R.id.connectLayout);
        codeGenerationLayout = findViewById(R.id.codeGenerationLayout);

        etLoginEmail = findViewById(R.id.et_login_email);
        etLoginPassword = findViewById(R.id.et_login_password);
        btnLogin = findViewById(R.id.btn_login);
        tvStatus = findViewById(R.id.tv_status);
        tvForgotPassword = findViewById(R.id.tv_forgot_password);

        etSignupEmail = findViewById(R.id.et_signup_email);
        etSignupPassword = findViewById(R.id.et_signup_password);
        etSignupConfirm = findViewById(R.id.et_signup_confirm);
        btnSignup = findViewById(R.id.btn_signup);

        etChildCode = findViewById(R.id.et_child_code);
        etChildName = findViewById(R.id.et_child_name);
        btnConnectChild = findViewById(R.id.btn_connect_child);

        tvChildCode = findViewById(R.id.tv_child_code);
        etChildNameParent = findViewById(R.id.et_child_name_parent);
        btnDone = findViewById(R.id.btn_done);
    }

    private void setupClickListeners() {
        cardParent.setOnClickListener(v -> showParentLogin());
        cardChild.setOnClickListener(v -> showChildConnect());

        btnLogin.setOnClickListener(v -> handleParentLogin());
        btnSignup.setOnClickListener(v -> handleParentSignup());
        btnConnectChild.setOnClickListener(v -> handleChildConnect());
        btnDone.setOnClickListener(v -> handleParentDone());

        tvStatus.setOnClickListener(v -> showParentSignup());

        tvForgotPassword.setOnClickListener(v -> handleForgotPassword());
    }

    private void showModeSelection() {
        cardParent.setVisibility(View.VISIBLE);
        cardChild.setVisibility(View.VISIBLE);
        loginLayout.setVisibility(View.GONE);
        signupLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.GONE);
        codeGenerationLayout.setVisibility(View.GONE);
        tvStatus.setText("");
    }

    private void showParentLogin() {
        cardParent.setVisibility(View.GONE);
        cardChild.setVisibility(View.GONE);
        loginLayout.setVisibility(View.VISIBLE);
        signupLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.GONE);
        codeGenerationLayout.setVisibility(View.GONE);
        tvStatus.setText("No account yet? Tap here after trying login to create one.");
    }

    private void showParentSignup() {
        cardParent.setVisibility(View.GONE);
        cardChild.setVisibility(View.GONE);
        loginLayout.setVisibility(View.GONE);
        signupLayout.setVisibility(View.VISIBLE);
        connectLayout.setVisibility(View.GONE);
        codeGenerationLayout.setVisibility(View.GONE);
    }

    private void showChildConnect() {
        cardParent.setVisibility(View.GONE);
        cardChild.setVisibility(View.GONE);
        loginLayout.setVisibility(View.GONE);
        signupLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.VISIBLE);
        codeGenerationLayout.setVisibility(View.GONE);
    }

    private void handleParentLogin() {
        String email = etLoginEmail.getText().toString().trim();
        String password = etLoginPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            tvStatus.setText("Please enter email and password");
            return;
        }

        tvStatus.setText("Logging in...");

        authManager.login(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(String userId, String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ModeSelectionActivity.this, "Login successful!", Toast.LENGTH_SHORT).show();
                    sharedPreferences.edit().putString("userRole", "parent").apply();
                    navigateToParentChildPicker();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> tvStatus.setText("Login failed: " + error + "\nTap here to create account."));
            }
        });
    }

    private void handleForgotPassword() {
        String email = etLoginEmail.getText().toString().trim();

        // If the email field is empty, prompt the parent to fill it first.
        if (email.isEmpty()) {
            tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
            tvStatus.setText("Enter your email above, then tap \"Forgot password?\" again.");
            return;
        }

        // Disable the link while the request is in-flight so the parent can't double-tap.
        tvForgotPassword.setEnabled(false);
        tvStatus.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
        tvStatus.setText("Sending reset email…");

        authManager.sendPasswordReset(email, new AuthManager.AuthResetCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    tvForgotPassword.setEnabled(true);
                    // Switch tv_status to a success (non-error) colour so it's clearly positive.
                    tvStatus.setTextColor(getResources().getColor(
                            android.R.color.holo_green_dark, null));
                    tvStatus.setText("Reset email sent to " + email
                            + ". Check your inbox (and spam folder).");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvForgotPassword.setEnabled(true);
                    tvStatus.setTextColor(
                            getResources().getColor(android.R.color.holo_red_dark, null));
                    tvStatus.setText("Could not send reset email: " + error);
                });
            }
        });
    }

    private void handleParentSignup() {
        String email = etSignupEmail.getText().toString().trim();
        String password = etSignupPassword.getText().toString().trim();
        String confirm = etSignupConfirm.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirm)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        if (password.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Creating account...", Toast.LENGTH_SHORT).show();

        authManager.signUp(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(String userId, String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ModeSelectionActivity.this, "Account created successfully!", Toast.LENGTH_LONG).show();
                    sharedPreferences.edit().putString("userRole", "parent").apply();
                    navigateToParentChildPicker();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(ModeSelectionActivity.this, "Signup failed: " + error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showParentCodeGeneration() {
        String childCode = authManager.generateChildCode();

        if (childCode != null) {
            tvChildCode.setText(childCode);
            sharedPreferences.edit().putString("generatedChildCode", childCode).apply();
        }

        loginLayout.setVisibility(View.GONE);
        signupLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.GONE);
        codeGenerationLayout.setVisibility(View.VISIBLE);
    }

    private void handleParentDone() {
        String childName = etChildNameParent.getText().toString().trim();
        if (childName.isEmpty()) {
            childName = "My Child";
        }

        String childCode = sharedPreferences.getString("generatedChildCode", "");

        if (childCode.isEmpty()) {
            Toast.makeText(this, "Error: No child code generated", Toast.LENGTH_SHORT).show();
            return;
        }

        authManager.saveChildCode(childCode, childName, new AuthManager.ChildCodeCallback() {
            @Override
            public void onSuccess(String code) {
                runOnUiThread(() -> {
                    Toast.makeText(ModeSelectionActivity.this, "Child profile created!", Toast.LENGTH_LONG).show();
                    navigateToMode("parent");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(ModeSelectionActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show();
                    navigateToMode("parent");
                });
            }
        });
    }

    private void handleChildConnect() {
        String childCode = etChildCode.getText().toString().trim().toUpperCase();
        String childName = etChildName.getText().toString().trim();

        if (childCode.isEmpty() || childName.isEmpty()) {
            Toast.makeText(this, "Please enter child code and your name", Toast.LENGTH_SHORT).show();
            return;
        }

        String deviceName = android.os.Build.MODEL;

        authManager.connectChildDevice(childCode, deviceName, new AuthManager.ConnectCallback() {
            @Override
            public void onSuccess(String name, String parentId) {
                runOnUiThread(() -> {
                    Toast.makeText(ModeSelectionActivity.this, "Connected to parent " + name + "!", Toast.LENGTH_LONG).show();
                    sharedPreferences.edit()
                            .putString("userRole", "child")
                            .putString("childName", childName)
                            .putBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)
                            .apply();
                    navigateToChildFlow();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(ModeSelectionActivity.this, "Connection failed: " + error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void navigateToParentChildPicker() {
        startActivity(new Intent(this, ParentChildPickerActivity.class));
        finish();
    }

    private void navigateToMode(String mode) {
        if ("parent".equals(mode)) {
            navigateToParentChildPicker();
            return;
        }
        navigateToChildFlow();
    }

    private void navigateToChildFlow() {
        if (sharedPreferences.getBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)
                && PhoneLockRedirect.redirectIfGated(this)) {
            return;
        }
        Intent intent;
        if (sharedPreferences.getBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)) {
            BlockMonitorService.startMonitoring(this);
            intent = new Intent(this, ChildDashboardActivity.class);
        } else {
            intent = new Intent(this, ChildPermissionsActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}