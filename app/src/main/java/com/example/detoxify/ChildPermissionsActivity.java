package com.example.detoxify;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/**
 * Flow step after child device connect: grant usage (required) and accessibility (recommended)
 * before monitoring screen time on the main child dashboard.
 */
public class ChildPermissionsActivity extends AppCompatActivity {

    private TextView tvUsageStatus;
    private TextView tvAccessibilityStatus;
    private MaterialButton btnContinue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_permissions);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(R.string.flow_child_permissions_toolbar);
        }

        tvUsageStatus = findViewById(R.id.tv_usage_status);
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        btnContinue = findViewById(R.id.btn_continue_monitoring);

        findViewById(R.id.btn_grant_usage).setOnClickListener(v ->
                startActivity(UsageStatsHelper.usageAccessSettingsIntent()));
        findViewById(R.id.btn_grant_accessibility).setOnClickListener(v ->
                startActivity(AccessibilityHelper.accessibilitySettingsIntent()));

        btnContinue.setOnClickListener(v -> {
            if (!UsageStatsHelper.hasUsageAccess(this)) {
                tvUsageStatus.setText(R.string.flow_usage_required);
                return;
            }
            getSharedPreferences(DetoxifyPrefs.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, true)
                    .apply();
            BlockMonitorService.startMonitoring(this);
            Intent intent = new Intent(this, ChildDashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                startActivity(new Intent(ChildPermissionsActivity.this, ModeSelectionActivity.class));
                finish();
            }
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean usage = UsageStatsHelper.hasUsageAccess(this);
        boolean a11y = AccessibilityHelper.isBlockServiceEnabled(this);

        tvUsageStatus.setText(usage
                ? R.string.flow_permission_granted
                : R.string.flow_permission_needed);
        tvAccessibilityStatus.setText(a11y
                ? R.string.flow_permission_granted
                : R.string.flow_permission_recommended);

        btnContinue.setEnabled(usage);
    }
}
