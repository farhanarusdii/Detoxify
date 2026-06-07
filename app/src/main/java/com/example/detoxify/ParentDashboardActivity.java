package com.example.detoxify;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParentDashboardActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_CHILD_CODE = "openChildCode";
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private Spinner spinnerChildren;
    private TextView tvScreenTime, tvAppsUsed, tvChildName, tvNoChildren;

    private CardView cardReviewRequests, cardBlockApps, cardBedtime, cardReports;
    private CardView cardCompareChildren, cardInsights;
    private Button btnSwitchMode, btnAddChild;
    private ImageButton btnChildOptions;
    private View layoutChildSelector;

    private SharedPreferences sharedPreferences;
    private DatabaseReference mDatabase;
    private String parentId;
    private AuthManager authManager;

    private List<ChildInfo> childrenList = new ArrayList<>();
    private Map<String, ChildInfo> childrenMap = new HashMap<>();
    private String currentChildCode;

    private DatabaseReference usageRef;
    private ValueEventListener usageListener;

    private DatabaseReference deviceLockRef;
    private ValueEventListener deviceLockListener;
    private CardView cardDeviceLock;
    private CardView cardSetTimeLimit;
    private TextView tvDeviceLockStatus;
    private TextView tvDailyLimitStatus;
    private boolean deviceRemoteLocked;

    private String pendingOpenChildCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_dashboard);

        pendingOpenChildCode = getIntent().getStringExtra(EXTRA_OPEN_CHILD_CODE);

        sharedPreferences = getSharedPreferences("Detoxify", MODE_PRIVATE);
        authManager = AuthManager.getInstance(this);
        mDatabase = FirebaseDatabase.getInstance().getReference();
        parentId = sharedPreferences.getString("userId", "");

        initViews();
        setupToolbar();
        setupClickListeners();
        loadChildrenFromFirebase();
        setupParentPassword();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (authManager.isLoggedIn() && "parent".equals(sharedPreferences.getString("userRole", ""))) {
            ParentWatchController.startWatching(this);
        }
        maybeRequestNotificationPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String code = intent.getStringExtra(EXTRA_OPEN_CHILD_CODE);
        if (code == null || code.isEmpty()) {
            return;
        }
        if (childrenList.isEmpty()) {
            pendingOpenChildCode = code;
            return;
        }
        applyChildSelectionByCode(code);
    }

    private void applyChildSelectionByCode(String code) {
        for (int i = 0; i < childrenList.size(); i++) {
            if (code.equals(childrenList.get(i).childCode)) {
                spinnerChildren.setSelection(i, false);
                return;
            }
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }

    @Override
    protected void onDestroy() {
        detachUsageListener();
        detachDeviceLockListener();
        super.onDestroy();
    }

    private void detachUsageListener() {
        if (usageRef != null && usageListener != null) {
            usageRef.removeEventListener(usageListener);
        }
        usageRef = null;
        usageListener = null;
    }

    private void attachUsageListener(String childCode) {
        detachUsageListener();
        if (childCode == null || childCode.isEmpty()) {
            return;
        }
        usageRef = mDatabase.child("children").child(childCode).child("todayUsage");
        usageListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    updateDashboardData(0, Collections.emptyMap());
                    return;
                }
                Long total = snapshot.child("totalMinutes").getValue(Long.class);
                long totalMinutes = total != null ? total : 0;

                DataSnapshot appsSnap = snapshot.child("apps");
                List<Map.Entry<String, Long>> pairs = new ArrayList<>();
                for (DataSnapshot app : appsSnap.getChildren()) {
                    String label = app.child("label").getValue(String.class);
                    String packageName = app.child("packageName").getValue(String.class);
                    Long minutes = app.child("minutes").getValue(Long.class);
                    if (minutes == null) {
                        minutes = 0L;
                    }
                    // New format: list items under apps/0, apps/1 with packageName field.
                    // Legacy (broken) format used package name as key — ignore invalid keys.
                    String name = label != null && !label.isEmpty()
                            ? label
                            : (packageName != null && !packageName.isEmpty() ? packageName : app.getKey());
                    pairs.add(new AbstractMap.SimpleEntry<>(name, minutes));
                }
                pairs.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

                Map<String, Long> sorted = new LinkedHashMap<>();
                for (Map.Entry<String, Long> e : pairs) {
                    sorted.put(e.getKey(), e.getValue());
                }
                updateDashboardData(totalMinutes, sorted);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w("ParentDashboard", "usage listener: " + error.getMessage());
            }
        };
        usageRef.addValueEventListener(usageListener);
    }

    private void detachDeviceLockListener() {
        if (deviceLockRef != null && deviceLockListener != null) {
            deviceLockRef.removeEventListener(deviceLockListener);
        }
        deviceLockRef = null;
        deviceLockListener = null;
    }

    private void attachDeviceLockListener(String childCode) {
        detachDeviceLockListener();
        if (childCode == null || childCode.isEmpty()) {
            return;
        }
        deviceLockRef = mDatabase.child("children").child(childCode).child("deviceLock").child("active");
        deviceLockListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean active = snapshot.getValue(Boolean.class);
                deviceRemoteLocked = active != null && active;
                if (tvDeviceLockStatus != null) {
                    tvDeviceLockStatus.setText(deviceRemoteLocked
                            ? R.string.parent_device_locked_status
                            : R.string.parent_device_unlocked_status);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w("ParentDashboard", "deviceLock: " + error.getMessage());
            }
        };
        deviceLockRef.addValueEventListener(deviceLockListener);
    }

    private void initViews() {
        spinnerChildren = findViewById(R.id.spinner_children);
        tvScreenTime = findViewById(R.id.tv_screen_time);
        tvAppsUsed = findViewById(R.id.tv_apps_used);
        tvChildName = findViewById(R.id.tv_child_name);
        tvNoChildren = findViewById(R.id.tv_no_children);
        cardReviewRequests = findViewById(R.id.card_review_requests);
        cardBlockApps = findViewById(R.id.card_block_apps);
        cardBedtime = findViewById(R.id.card_bedtime);
        cardReports = findViewById(R.id.card_reports);
        cardCompareChildren = findViewById(R.id.card_compare_children);
        cardInsights = findViewById(R.id.card_insights);
        cardDeviceLock = findViewById(R.id.card_device_lock);
        cardSetTimeLimit = findViewById(R.id.card_set_time_limit);
        tvDeviceLockStatus = findViewById(R.id.tv_device_lock_status);
        tvDailyLimitStatus = findViewById(R.id.tv_daily_limit_status);
        btnSwitchMode = findViewById(R.id.btn_switch_mode);
        btnAddChild = findViewById(R.id.btn_add_child);
        btnChildOptions = findViewById(R.id.btn_child_options);
        layoutChildSelector = findViewById(R.id.layout_child_selector);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Parent Dashboard");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_parent_toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_manage_account) {
            startActivity(new Intent(this, ManageAccountActivity.class));
            return true;
        }
        if (id == R.id.action_logout) {
            authManager.logout();
            Intent intent = new Intent(this, ModeSelectionActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupParentPassword() {
        String existingPassword = sharedPreferences.getString("parent_password", "");
        if (existingPassword.isEmpty()) {
            showSetPasswordDialog();
        }
    }

    private void showSetPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔐 Set Parent Password");
        builder.setMessage("Set a password that your child will need to enter when switching to Parent Mode");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter password (min 4 characters)");
        builder.setView(input);

        builder.setPositiveButton("Set Password", (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (password.length() >= 4) {
                sharedPreferences.edit().putString("parent_password", password).apply();
                Toast.makeText(this, "Parent password set successfully!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                showSetPasswordDialog();
            }
        });

        builder.setCancelable(false);
        builder.show();
    }

    private void loadChildrenFromFirebase() {
        if (parentId.isEmpty()) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Loading children...", Toast.LENGTH_SHORT).show();

        mDatabase.child("children").orderByChild("parentId").equalTo(parentId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        childrenList.clear();
                        childrenMap.clear();

                        for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                            String childCode = childSnapshot.getKey();
                            String childName = childSnapshot.child("childName").getValue(String.class);
                            String deviceName = childSnapshot.child("deviceName").getValue(String.class);
                            Boolean connected = childSnapshot.child("connected").getValue(Boolean.class);
                            Long dailyLimit = childSnapshot.child("dailyLimit").getValue(Long.class);

                            if (childName == null) childName = "Unknown Child";

                            ChildInfo child = new ChildInfo();
                            child.childCode = childCode;
                            child.childName = childName;
                            child.deviceName = deviceName != null ? deviceName : "Not connected";
                            child.isConnected = connected != null ? connected : false;
                            child.dailyLimit = dailyLimit != null ? dailyLimit : 120;

                            childrenList.add(child);
                            childrenMap.put(childCode, child);

                            // Log for debugging
                            Log.d("ParentDashboard", "Loaded child: " + childName + " (" + childCode + ")");
                        }

                        updateChildrenSpinner();
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Toast.makeText(ParentDashboardActivity.this,
                                "Error loading children: " + databaseError.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        useDemoChild();
                    }
                });
    }

    private void useDemoChild() {
        // Add a demo child for testing
        ChildInfo demoChild = new ChildInfo();
        demoChild.childCode = "DEMO123";
        demoChild.childName = "Demo Child";
        demoChild.isConnected = true;
        demoChild.dailyLimit = 120;

        childrenList.add(demoChild);
        childrenMap.put("DEMO123", demoChild);
        updateChildrenSpinner();
    }

    private void updateChildrenSpinner() {
        if (spinnerChildren == null) {
            return;
        }
        if (childrenList.isEmpty()) {
            layoutChildSelector.setVisibility(View.GONE);
            tvNoChildren.setVisibility(View.VISIBLE);
            if (btnChildOptions != null) {
                btnChildOptions.setVisibility(View.GONE);
            }
            if (cardDeviceLock != null) {
                cardDeviceLock.setVisibility(View.GONE);
            }
            if (cardSetTimeLimit != null) {
                cardSetTimeLimit.setVisibility(View.GONE);
            }
            detachDeviceLockListener();
            return;
        }

        layoutChildSelector.setVisibility(View.VISIBLE);
        tvNoChildren.setVisibility(View.GONE);
        if (btnChildOptions != null) {
            btnChildOptions.setVisibility(View.VISIBLE);
        }
        if (cardDeviceLock != null) {
            cardDeviceLock.setVisibility(View.VISIBLE);
        }
        if (cardSetTimeLimit != null) {
            cardSetTimeLimit.setVisibility(View.VISIBLE);
        }

        List<String> displayNames = new ArrayList<>();
        for (ChildInfo child : childrenList) {
            String status = child.isConnected ? "🟢" : "⚪";
            displayNames.add(status + " " + child.childName);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_text, displayNames);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_text);
        spinnerChildren.setAdapter(adapter);

        spinnerChildren.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= childrenList.size()) {
                    return;
                }
                currentChildCode = childrenList.get(position).childCode;
                ChildInfo selected = childrenList.get(position);
                if (tvChildName != null) {
                    tvChildName.setText(selected.childName);
                }
                updateDailyLimitStatus(selected);
                attachUsageListener(currentChildCode);
                attachDeviceLockListener(currentChildCode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        int selectIdx = 0;
        if (pendingOpenChildCode != null) {
            for (int i = 0; i < childrenList.size(); i++) {
                if (pendingOpenChildCode.equals(childrenList.get(i).childCode)) {
                    selectIdx = i;
                    break;
                }
            }
            pendingOpenChildCode = null;
        }
        spinnerChildren.setSelection(selectIdx, false);
    }

    private void updateDashboardData(long totalMinutes, Map<String, Long> appUsage) {
        if (tvScreenTime != null) {
            tvScreenTime.setText(DurationFormat.hoursMinutes(totalMinutes));
        }
        if (tvAppsUsed != null) {
            tvAppsUsed.setText(String.valueOf(appUsage.size()));
        }
    }


    private void openReportsForSelectedChild() {
        if (currentChildCode == null || currentChildCode.isEmpty() || childrenList.isEmpty()) {
            Toast.makeText(this, "Select a child from the list first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ReportsActivity.class);
        ChildInfo ci = childrenMap.get(currentChildCode);
        intent.putExtra("childName", ci != null ? ci.childName : "");
        intent.putExtra("childCode", currentChildCode);
        startActivity(intent);
    }

    private void setupClickListeners() {
        if (btnAddChild != null) {
            btnAddChild.setOnClickListener(v -> showAddChildDialog());
        }
        if (btnChildOptions != null) {
            btnChildOptions.setOnClickListener(this::showChildOptionsMenu);
        }
        if (cardReviewRequests != null) {
            cardReviewRequests.setOnClickListener(v ->
                    startActivity(new Intent(this, ParentReviewRequestsActivity.class)));
        }
        if (cardBlockApps != null) {
            cardBlockApps.setOnClickListener(v -> {
                if (currentChildCode == null || currentChildCode.isEmpty() || childrenList.isEmpty()) {
                    Toast.makeText(this, "Select a child from the list first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, BlockAppsActivity.class);
                intent.putExtra("childCode", currentChildCode);
                ChildInfo ci = childrenMap.get(currentChildCode);
                intent.putExtra("childName", ci != null ? ci.childName : "");
                startActivity(intent);
            });
        }
        if (cardBedtime != null) {
            cardBedtime.setOnClickListener(v -> {
                Intent i = new Intent(this, BedtimeIdeasActivity.class);
                i.putExtra(BedtimeIdeasActivity.EXTRA_AUDIENCE, BedtimeIdeasActivity.AUDIENCE_PARENT);
                startActivity(i);
            });
        }
        if (cardSetTimeLimit != null) {
            cardSetTimeLimit.setOnClickListener(v -> openSetTimeLimitForSelectedChild());
        }
        if (cardDeviceLock != null) {
            cardDeviceLock.setOnClickListener(v -> {
                if (currentChildCode == null || currentChildCode.isEmpty() || childrenList.isEmpty()) {
                    Toast.makeText(this, "Select a child from the list first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (deviceRemoteLocked) {
                    ChildInfo unlockChild = childrenMap.get(currentChildCode);
                    String unlockName = unlockChild != null
                            ? unlockChild.childName : getString(R.string.add_child_default_name);
                    long unlockLimit = unlockChild != null ? unlockChild.dailyLimit : 120;
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.parent_device_unlock_dialog_title)
                            .setMessage(R.string.parent_device_unlock_dialog_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.parent_device_unlock_confirm, (d, w) ->
                                    showSetTimeLimitDialog(currentChildCode, unlockName, unlockLimit,
                                            true, 1L))
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.parent_device_lock_dialog_title)
                            .setMessage(R.string.parent_device_lock_dialog_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.parent_device_lock_confirm, (d, w) ->
                                    mDatabase.child("children").child(currentChildCode)
                                            .child("deviceLock").child("active").setValue(true)
                                            .addOnFailureListener(e -> Toast.makeText(this,
                                                    e.getMessage() != null ? e.getMessage() : "Error",
                                                    Toast.LENGTH_SHORT).show()))
                            .show();
                }
            });

            cardReports.setOnClickListener(v -> openReportsForSelectedChild());

            if (cardCompareChildren != null) {
                cardCompareChildren.setOnClickListener(v ->
                        startActivity(new Intent(this, CompareChildrenActivity.class)));
            }

            if (cardInsights != null) {
                cardInsights.setOnClickListener(v -> openReportsForSelectedChild());
            }

            btnSwitchMode.setOnClickListener(v -> {
                sharedPreferences.edit().putString("userMode", "child").apply();
                startActivity(new Intent(this, ChildDashboardActivity.class));
                finish();
            });
        }
    }
        private void showAddChildDialog () {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.add_child_title);
            builder.setMessage(R.string.add_child_message);

            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint(R.string.add_child_name_hint);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad, pad, pad);
            builder.setView(input);

            builder.setPositiveButton(R.string.add_child_button, (dialog, which) -> {
                String entered = input.getText().toString().trim();
                final String childName = entered.isEmpty()
                        ? getString(R.string.add_child_default_name)
                        : entered;
                String code = authManager.generateChildCode();
                if (code == null) {
                    Toast.makeText(this, R.string.add_child_not_logged_in, Toast.LENGTH_LONG).show();
                    return;
                }
                authManager.saveChildCode(code, childName, new AuthManager.ChildCodeCallback() {
                    @Override
                    public void onSuccess(String savedCode) {
                        runOnUiThread(() -> {
                            loadChildrenFromFirebase();
                            showChildCodeDialog(savedCode, childName);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(ParentDashboardActivity.this,
                                getString(R.string.add_child_failed, error),
                                Toast.LENGTH_LONG).show());
                    }
                });
            });
            builder.setNegativeButton(android.R.string.cancel, null);
            builder.show();
        }

        private void showChildOptionsMenu (View anchor){
            if (childrenList.isEmpty()) {
                Toast.makeText(this, R.string.delete_child_select_first, Toast.LENGTH_SHORT).show();
                return;
            }
            PopupMenu popup = new PopupMenu(this, anchor);
            popup.getMenuInflater().inflate(R.menu.menu_child_actions, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_delete_child) {
                    confirmDeleteChild();
                    return true;
                }
                return false;
            });
            popup.show();
        }

        private void confirmDeleteChild () {
            if (currentChildCode == null || currentChildCode.isEmpty() || childrenList.isEmpty()) {
                Toast.makeText(this, R.string.delete_child_select_first, Toast.LENGTH_SHORT).show();
                return;
            }
            ChildInfo ci = childrenMap.get(currentChildCode);
            String name = ci != null ? ci.childName : currentChildCode;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_child_title)
                    .setMessage(getString(R.string.delete_child_message, name, currentChildCode))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete_child_confirm, (d, w) ->
                            authManager.deleteChild(currentChildCode, new AuthManager.ChildCodeCallback() {
                                @Override
                                public void onSuccess(String code) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(ParentDashboardActivity.this,
                                                R.string.delete_child_success, Toast.LENGTH_LONG).show();
                                        currentChildCode = null;
                                        loadChildrenFromFirebase();
                                    });
                                }

                                @Override
                                public void onError(String error) {
                                    runOnUiThread(() -> Toast.makeText(ParentDashboardActivity.this,
                                            getString(R.string.delete_child_failed, error),
                                            Toast.LENGTH_LONG).show());
                                }
                            }))
                    .show();
        }

        private void showChildCodeDialog (String code, String childName){
            new AlertDialog.Builder(this)
                    .setTitle(R.string.add_child_code_title)
                    .setMessage(getString(R.string.add_child_code_message, childName, code))
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.add_child_set_limit_button, (d, w) ->
                            showSetTimeLimitDialog(code, childName, 120))
                    .show();
        }

        private void openSetTimeLimitForSelectedChild () {
            if (currentChildCode == null || currentChildCode.isEmpty() || childrenList.isEmpty()) {
                Toast.makeText(this, "Select a child from the list first.", Toast.LENGTH_SHORT).show();
                return;
            }
            ChildInfo ci = childrenMap.get(currentChildCode);
            showSetTimeLimitDialog(currentChildCode,
                    ci != null ? ci.childName : getString(R.string.add_child_default_name),
                    ci != null ? ci.dailyLimit : 120);
        }

        private void updateDailyLimitStatus (ChildInfo child){
            if (tvDailyLimitStatus == null || child == null) {
                return;
            }
            tvDailyLimitStatus.setText(getString(R.string.set_time_limit_card_status,
                    DurationFormat.hoursMinutes(child.dailyLimit)));
        }

        private void showSetTimeLimitDialog (String childCode, String childName,
        long currentLimitMinutes){
            showSetTimeLimitDialog(childCode, childName, currentLimitMinutes, false, 1L);
        }

        private void showSetTimeLimitDialog (String childCode, String childName,
        long currentLimitMinutes,
        boolean clearDeviceLock, long minLimitMinutes){
            DailyLimitDialogHelper.show(this, childCode, currentLimitMinutes, minLimitMinutes,
                    R.string.set_time_limit_title,
                    getString(R.string.set_time_limit_message, childName),
                    clearDeviceLock,
                    new DailyLimitDialogHelper.Listener() {
                        @Override
                        public void onSaved(long newLimitMinutes) {
                            ChildInfo ci = childrenMap.get(childCode);
                            if (ci != null) {
                                ci.dailyLimit = newLimitMinutes;
                                if (childCode.equals(currentChildCode)) {
                                    updateDailyLimitStatus(ci);
                                }
                            }
                            Toast.makeText(ParentDashboardActivity.this,
                                    getString(R.string.set_time_limit_success, childName),
                                    Toast.LENGTH_LONG).show();
                        }

                        @Override
                        public void onCancelled() {
                        }
                    });
        }

        // Data class for child info
        static class ChildInfo {
            String childCode;
            String childName;
            String deviceName;
            boolean isConnected;
            long dailyLimit;
        }
    }