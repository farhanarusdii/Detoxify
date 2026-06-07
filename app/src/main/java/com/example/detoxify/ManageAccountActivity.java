package com.example.detoxify;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Parent account settings: view email, change Firebase password, set local parent PIN for child mode.
 */
public class ManageAccountActivity extends AppCompatActivity {

    private FirebaseAuth firebaseAuth;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_account);

        firebaseAuth = FirebaseAuth.getInstance();
        prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.manage_account_title);
        }

        TextView tvEmail = findViewById(R.id.tv_account_email);
        FirebaseUser user = firebaseAuth.getCurrentUser();
        String email = prefs.getString("userEmail", "");
        if (user != null && user.getEmail() != null && !user.getEmail().isEmpty()) {
            email = user.getEmail();
        }
        tvEmail.setText(email.isEmpty() ? getString(R.string.manage_account_email_unknown) : email);

        MaterialButton btnChangePassword = findViewById(R.id.btn_change_password);
        MaterialButton btnParentPin = findViewById(R.id.btn_parent_pin);

        btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        btnParentPin.setOnClickListener(v -> showParentPinDialog());
    }

    private void showChangePasswordDialog() {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null || user.getEmail() == null) {
            Toast.makeText(this, R.string.manage_account_not_logged_in, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final EditText current = newPasswordField(getString(R.string.manage_account_current_password));
        final EditText next = newPasswordField(getString(R.string.manage_account_new_password));
        final EditText confirm = newPasswordField(getString(R.string.manage_account_confirm_password));

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);
        layout.addView(current);
        layout.addView(next);
        layout.addView(confirm);

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_account_change_password)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.manage_account_save, (d, w) -> {
                    String cur = current.getText().toString();
                    String neu = next.getText().toString();
                    String conf = confirm.getText().toString();
                    if (cur.isEmpty() || neu.isEmpty() || conf.isEmpty()) {
                        Toast.makeText(this, R.string.manage_account_fill_all, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (neu.length() < 6) {
                        Toast.makeText(this, R.string.manage_account_password_short, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!neu.equals(conf)) {
                        Toast.makeText(this, R.string.manage_account_password_mismatch, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), cur);
                    user.reauthenticate(credential)
                            .addOnSuccessListener(aVoid -> user.updatePassword(neu)
                                    .addOnSuccessListener(ok -> Toast.makeText(this,
                                            R.string.manage_account_password_updated, Toast.LENGTH_LONG).show())
                                    .addOnFailureListener(e -> Toast.makeText(this,
                                            getString(R.string.manage_account_password_failed,
                                                    e.getMessage() != null ? e.getMessage() : ""),
                                            Toast.LENGTH_LONG).show()))
                            .addOnFailureListener(e -> Toast.makeText(this,
                                    getString(R.string.manage_account_reauth_failed,
                                            e.getMessage() != null ? e.getMessage() : ""),
                                    Toast.LENGTH_LONG).show());
                })
                .show();
    }

    private void showParentPinDialog() {
        final EditText input = newPasswordField(getString(R.string.manage_account_parent_pin_hint));
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_account_parent_pin)
                .setMessage(R.string.manage_account_parent_pin_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.manage_account_save, (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 4) {
                        Toast.makeText(this, R.string.manage_account_pin_short, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("parent_password", pin).apply();
                    ChildDashboardActivity.setParentPassword(this, pin);
                    Toast.makeText(this, R.string.manage_account_pin_saved, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private EditText newPasswordField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        input.setPadding(0, pad, 0, pad);
        return input;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
