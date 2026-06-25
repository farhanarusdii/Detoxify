package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChildDashboardActivity extends AppCompatActivity {

    private TextView tvTimeRemaining, tvUsedTime, tvBedtimeStatus, tvParentInfo;
    private ProgressBar progressTime;
    private ListView listTodayUsage;
    private Button btnRequestTime, btnSwitchToParent;
    private CardView cardBedtime, cardMood;
    private TextView tvMoodStatus;

    private SharedPreferences sharedPreferences;
    private AuthManager authManager;
    private DatabaseReference mDatabase;
    private long totalLimit = 120;
    private long usedToday;
    /** Raw milliseconds — used for sub-minute-accurate remaining-time display. */
    private long usedTodayMs;
    private String parentEmail = "";

    private final ExecutorService usageExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean usageSettingsPromptShown;
    private boolean accessibilityPromptShown;

    private final Runnable usageRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            syncScreenTimeIfChildDevice();
            mainHandler.postDelayed(this, 5_000L);
        }
    };

    /**
     * Fires every second only when remaining time is under 5 minutes.
     * Recomputes remaining from the last-known usedTodayMs so the display
     * counts down smoothly without waiting for the next 5-second usage sync.
     */
    private final Runnable secondTickRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long limitMs = PhoneLimitEvaluator.effectiveLimitMinutes(sharedPreferences, now) * 60_000L;
            long remainingMs = limitMs - usedTodayMs;
            if (remainingMs < 0) remainingMs = 0;

            if (tvTimeRemaining != null) {
                tvTimeRemaining.setText(DurationFormat.fromMs(remainingMs));
            }

            if (remainingMs <= 0) {
                // Limit hit — stop ticking and let the lock enforce itself
                return;
            }
            if (remainingMs < 5 * 60_000L) {
                // Keep ticking every second while under 5 minutes
                mainHandler.postDelayed(this, 1_000L);
            }
            // Over 5 min → tick stopped; usageRefreshRunnable will restart it if needed
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        authManager = AuthManager.getInstance(this);

        if (!authManager.isChildConnected()) {
            startActivity(new Intent(this, ModeSelectionActivity.class));
            finish();
            return;
        }

        if (!sharedPreferences.getBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)) {
            startActivity(new Intent(this, ChildPermissionsActivity.class));
            finish();
            return;
        }

        if (PhoneLockRedirect.redirectIfGated(this)) {
            return;
        }

        setContentView(R.layout.activity_child_dashboard);
        mDatabase = FirebaseDatabase.getInstance().getReference();

        initViews();
        setupToolbar();
        loadUsageData();
        setupClickListeners();
        loadParentInfo();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String code = sharedPreferences.getString("connectedChildCode", "");
        if (!code.isEmpty()) {
            BlockMonitorService.startMonitoring(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PhoneLockRedirect.redirectIfGated(this)) {
            return;
        }
        loadUsageData();
        updateMoodCardStatus();
        syncScreenTimeIfChildDevice();
        scheduleAccessibilityPromptIfNeeded();
        mainHandler.removeCallbacks(usageRefreshRunnable);
        mainHandler.postDelayed(usageRefreshRunnable, 5_000L);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(usageRefreshRunnable);
        mainHandler.removeCallbacks(secondTickRunnable);
        super.onPause();
    }

    void updateMoodCardStatus() {
        if (tvMoodStatus == null) {
            return;
        }
        tvMoodStatus.setText(MoodCheckInHelper.checkedInToday(this)
                ? R.string.mood_card_done
                : R.string.mood_card_prompt);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(accessibilityPromptRunnable);
        mainHandler.removeCallbacks(usageRefreshRunnable);
        mainHandler.removeCallbacks(secondTickRunnable);
        usageExecutor.shutdown();
        super.onDestroy();
    }

    private void initViews() {
        tvTimeRemaining = findViewById(R.id.tv_time_remaining);
        tvUsedTime = findViewById(R.id.tv_used_time);
        tvBedtimeStatus = findViewById(R.id.tv_bedtime_status);
        progressTime = findViewById(R.id.progress_time);
        listTodayUsage = findViewById(R.id.list_today_usage);
        btnRequestTime = findViewById(R.id.btn_request_time);
        btnSwitchToParent = findViewById(R.id.btn_switch_to_parent);
        cardBedtime = findViewById(R.id.card_bedtime);
        cardMood = findViewById(R.id.card_mood);
        tvMoodStatus = findViewById(R.id.tv_mood_status);
        tvParentInfo = findViewById(R.id.tv_parent_info);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Child Dashboard");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_dashboard, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            BlockMonitorService.stopMonitoring(this);
            authManager.logoutChild();
            Intent intent = new Intent(this, ModeSelectionActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadUsageData() {
        usedToday = sharedPreferences.getLong("used_today", 0);
        totalLimit = sharedPreferences.getLong("phone_daily_limit",
                sharedPreferences.getLong("daily_limit", 120));
        parentEmail = sharedPreferences.getString("parentEmail", "");
        if ("parent@example.com".equals(parentEmail)) {
            parentEmail = "";
            sharedPreferences.edit().remove("parentEmail").apply();
        }
        updateTimeRemaining();
    }

    private void loadParentInfo() {
        if (tvParentInfo == null) {
            return;
        }
        if (!parentEmail.isEmpty()) {
            tvParentInfo.setText(getString(R.string.child_connected_to, parentEmail));
        } else {
            tvParentInfo.setText(R.string.child_connected_loading);
        }

        String parentId = sharedPreferences.getString("parentId", "");
        if (parentId.isEmpty()) {
            return;
        }

        mDatabase.child("users").child(parentId).child("email")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        String email = snapshot.getValue(String.class);
                        if (email == null || email.isEmpty()) {
                            return;
                        }
                        parentEmail = email;
                        sharedPreferences.edit().putString("parentEmail", email).apply();
                        tvParentInfo.setText(getString(R.string.child_connected_to, email));
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        if (parentEmail.isEmpty()) {
                            tvParentInfo.setText(R.string.child_connected_unknown);
                        }
                    }
                });
    }

    private void updateTimeRemaining() {
        long now = System.currentTimeMillis();
        boolean grantActive = PhoneLimitEvaluator.isGrantActive(sharedPreferences, now);
        updateTimeRemaining(PhoneLimitEvaluator.Result.noLock(
                usedToday,
                PhoneLimitEvaluator.effectiveLimitMinutes(sharedPreferences, now),
                30_000L,
                grantActive,
                sharedPreferences.getLong(BlockMonitorService.PREFS_TIME_GRANT_EXPIRES_MS, 0L)));
    }

    private void updateTimeRemaining(PhoneLimitEvaluator.Result result) {
        long limitMs = result.effectiveLimitMinutes * 60_000L;
        // Use raw ms for display when we have it (post-breakdown); fall back to whole minutes.
        long remainingMs = (usedTodayMs > 0)
                ? Math.max(0L, limitMs - usedTodayMs)
                : Math.max(0L, (result.effectiveLimitMinutes - usedToday)) * 60_000L;

        // fromMs shows M:SS under 5 minutes so the display never freezes at "1m".
        tvTimeRemaining.setText(DurationFormat.fromMs(remainingMs));

        String usedText = DurationFormat.hoursMinutes(usedToday);
        String limitText = DurationFormat.hoursMinutes(result.effectiveLimitMinutes);
        if (result.grantActive && result.grantExpiresAtMs > 0L) {
            long grantMinutesLeft = Math.max(0L,
                    (result.grantExpiresAtMs - System.currentTimeMillis() + 59_999L) / 60_000L);
            tvUsedTime.setText("Phone used: " + usedText + " of " + limitText
                    + " · extra time ends in " + DurationFormat.hoursMinutes(grantMinutesLeft));
        } else {
            tvUsedTime.setText("Phone used: " + usedText + " of " + limitText);
        }

        int progress = result.effectiveLimitMinutes > 0
                ? (int) ((usedToday * 100) / result.effectiveLimitMinutes) : 0;
        progressTime.setProgress(Math.min(progress, 100));

        long remainingMinutes = remainingMs / 60_000L;
        if (remainingMinutes < 15) {
            tvTimeRemaining.setTextColor(getResources().getColor(R.color.detox_rose, getTheme()));
        } else if (remainingMinutes < 30) {
            tvTimeRemaining.setTextColor(getResources().getColor(R.color.detox_peach, getTheme()));
        } else {
            tvTimeRemaining.setTextColor(getResources().getColor(R.color.detox_on_primary_container, getTheme()));
        }
    }

    private void syncScreenTimeIfChildDevice() {
        String childCode = sharedPreferences.getString("connectedChildCode", "");
        if (!UsageStatsHelper.hasUsageAccess(this)) {
            maybePromptUsageAccess();
            if (!childCode.isEmpty()) {
                usageExecutor.execute(() -> maybeUploadBlockableAppsList(childCode));
            }
            refreshTodayUsageUi();
            return;
        }

        if (childCode.isEmpty()) {
            refreshTodayUsageUi();
            return;
        }

        usageExecutor.execute(() -> {
            try {
                UsageStatsHelper.TodayBreakdown breakdown = UsageStatsHelper.computeToday(this);
                UsageStatsHelper.uploadTodayUsage(ChildDashboardActivity.this, mDatabase, childCode, breakdown);
                maybeUploadBlockableAppsList(childCode);
                runOnUiThread(() -> {
                    applyBreakdown(breakdown);
                    onUsageStoredForToday();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Could not read screen time: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * After monitor → Firebase: mood check-in, then behaviour analysis when mood is logged.
     */
    private void onUsageStoredForToday() {
        String childCode = sharedPreferences.getString("connectedChildCode", "");
        if (!childCode.isEmpty()) {
            UsageStatsHelper.refreshInsightsIfMoodReady(this, mDatabase, childCode);
        }
        if (!MoodCheckInHelper.checkedInToday(this)) {
            mainHandler.postDelayed(() ->
                    MoodCheckInHelper.promptDailyCheckInIfNeeded(ChildDashboardActivity.this), 800);
        }
    }

    /** Pushes launchable apps from this device so the parent can pick block targets in Firebase. */
    private void maybeUploadBlockableAppsList(String childCode) {
        if (childCode == null || childCode.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = sharedPreferences.getLong("last_blockable_apps_sync_ms", 0L);
        if (last > 0 && now - last < 15 * 60 * 1000L) {
            return;
        }
        List<InstalledAppsHelper.AppRow> apps = InstalledAppsHelper.listLaunchableApps(this);
        InstalledAppsHelper.uploadBlockableApps(mDatabase, childCode, apps);
        sharedPreferences.edit().putLong("last_blockable_apps_sync_ms", now).apply();
    }

    /**
     * Blocking only works reliably with Accessibility enabled. Prompt after Firebase fills blocked list.
     */
    private void scheduleAccessibilityPromptIfNeeded() {
        mainHandler.removeCallbacks(accessibilityPromptRunnable);
        mainHandler.postDelayed(accessibilityPromptRunnable, 2200);
    }

    private final Runnable accessibilityPromptRunnable = () -> {
        String code = sharedPreferences.getString("connectedChildCode", "");
        if (code.isEmpty()) {
            return;
        }
        Set<String> blocked = sharedPreferences.getStringSet(
                BlockMonitorService.PREFS_BLOCKED_PACKAGES, Collections.emptySet());
        if (blocked == null || blocked.isEmpty()) {
            return;
        }
        if (AccessibilityHelper.isBlockServiceEnabled(this)) {
            return;
        }
        if (accessibilityPromptShown) {
            return;
        }
        accessibilityPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Turn on blocking")
                .setMessage("To stop blocked apps from opening, enable the Detoxify accessibility service "
                        + "(Settings → Accessibility → Detoxify → On). "
                        + "Without this, blocking is slow and unreliable.")
                .setPositiveButton("Open Accessibility settings", (d, w) ->
                        startActivity(AccessibilityHelper.accessibilitySettingsIntent()))
                .setNegativeButton("Later", null)
                .show();
    };

    private void maybePromptUsageAccess() {
        if (sharedPreferences.getBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)) {
            return;
        }
        if (usageSettingsPromptShown || UsageStatsHelper.hasUsageAccess(this)) {
            return;
        }
        usageSettingsPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Usage access")
                .setMessage("Allow usage access for Detoxify so your parent can see today’s screen time.")
                .setPositiveButton("Open settings", (d, w) ->
                        startActivity(UsageStatsHelper.usageAccessSettingsIntent()))
                .setNegativeButton("Not now", null)
                .show();
    }

    private void applyBreakdown(UsageStatsHelper.TodayBreakdown breakdown) {
        usedToday = breakdown.totalMinutes;
        usedTodayMs = breakdown.totalMs;
        sharedPreferences.edit().putLong("used_today", usedToday).apply();
        PhoneLimitEvaluator.Result result = PhoneLimitEvaluator.evaluate(this, sharedPreferences);
        totalLimit = result.effectiveLimitMinutes;
        if (result.shouldLock) {
            PhoneLockGate.enforceDailyLimitLock(this);
        } else {
            sharedPreferences.edit()
                    .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                    .apply();
        }
        updateTimeRemaining(result);
        populateTodayList(breakdown.apps);
        tvBedtimeStatus.setText(R.string.child_bedtime_card_hint);

        // Start the per-second tick when under 5 minutes so the display counts down
        // smoothly instead of being stuck on "1m" for up to 60 seconds.
        long limitMs = result.effectiveLimitMinutes * 60_000L;
        long remainingMs = limitMs - usedTodayMs;
        mainHandler.removeCallbacks(secondTickRunnable);
        if (remainingMs > 0 && remainingMs < 5 * 60_000L) {
            mainHandler.postDelayed(secondTickRunnable, 1_000L);
        }
    }

    private void populateTodayList(List<UsageStatsHelper.AppUsageRow> apps) {
        List<String> lines = new ArrayList<>();
        int max = Math.min(apps.size(), 25);
        for (int i = 0; i < max; i++) {
            UsageStatsHelper.AppUsageRow row = apps.get(i);
            if (row.minutes <= 0) {
                continue;
            }
            lines.add(row.label + " - " + DurationFormat.hoursMinutes(row.minutes));
        }
        if (lines.isEmpty()) {
            lines.add("No app usage recorded yet today.");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_text, lines);
        listTodayUsage.setAdapter(adapter);
    }

    private void refreshTodayUsageUi() {
        String childCode = sharedPreferences.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            List<String> preview = new ArrayList<>();
            preview.add("Preview mode: connect a child device to track real usage.");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_text, preview);
            listTodayUsage.setAdapter(adapter);
            tvBedtimeStatus.setText(R.string.child_bedtime_card_hint);
            return;
        }

        if (!UsageStatsHelper.hasUsageAccess(this)) {
            List<String> lines = new ArrayList<>();
            lines.add("Allow usage access in Settings to see apps and sync to parent.");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_text, lines);
            listTodayUsage.setAdapter(adapter);
            tvBedtimeStatus.setText(R.string.child_bedtime_card_hint);
            updateTimeRemaining();
            return;
        }

        usageExecutor.execute(() -> {
            try {
                UsageStatsHelper.TodayBreakdown breakdown = UsageStatsHelper.computeToday(this);
                maybeUploadBlockableAppsList(childCode);
                runOnUiThread(() -> applyBreakdown(breakdown));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Could not read screen time",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void setupClickListeners() {
        btnRequestTime.setOnClickListener(v -> TimeRequestHelper.showRequestMoreTimeDialog(this, true));
        btnSwitchToParent.setOnClickListener(v -> showParentPasswordDialog());
        cardMood.setOnClickListener(v -> MoodCheckInHelper.showCheckInDialog(this));
        cardBedtime.setOnClickListener(v -> {
            PhoneLockGate.beginChildInteraction();
            Intent i = new Intent(this, BedtimeIdeasActivity.class);
            i.putExtra(BedtimeIdeasActivity.EXTRA_AUDIENCE, BedtimeIdeasActivity.AUDIENCE_CHILD);
            i.putExtra(BedtimeIdeasActivity.EXTRA_FROM_LOCK_SCREEN,
                    PhoneLockPolicy.isPhoneGated(getSharedPreferences("Detoxify", MODE_PRIVATE)));
            startActivity(i);
        });
    }

    private void showParentPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔐 Parent Mode");
        builder.setMessage("Enter parent password to access Parent Controls");

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter parent password");
        input.setPadding(50, 20, 50, 20);
        builder.setView(input);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPassword = input.getText().toString().trim();
            verifyParentPassword(enteredPassword);
        });

        builder.setNegativeButton("Cancel", (dialog, which) ->
                Toast.makeText(this, "Parent mode locked", Toast.LENGTH_SHORT).show());

        builder.setCancelable(false);
        builder.show();
    }

    private void verifyParentPassword(String enteredPassword) {
        String savedParentPassword = sharedPreferences.getString("parent_password", "");

        if (savedParentPassword.isEmpty()) {
            savedParentPassword = "123456";
        }

        if (enteredPassword.equals(savedParentPassword)) {
            Toast.makeText(this, "Access granted! Switching to Parent Mode...", Toast.LENGTH_LONG).show();

            BlockMonitorService.stopMonitoring(this);

            sharedPreferences.edit()
                    .putString("userMode", "parent")
                    .putBoolean("parentAccessGranted", true)
                    .putLong(BlockMonitorService.PREFS_PHONE_LIMIT_OVERRIDE_UNTIL_MS, 0L)
                    .apply();

            Intent intent = new Intent(this, ParentDashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, "❌ Incorrect password! Access denied.", Toast.LENGTH_LONG).show();
            showPasswordFailedDialog();
        }
    }

    private void showPasswordFailedDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚠️ Access Denied");
        builder.setMessage("Incorrect parent password.\n\nPlease ask your parent for the correct password.");
        builder.setPositiveButton("OK", (dialog, which) -> {
        });
        builder.show();
    }

    public static void setParentPassword(Context context, String newPassword) {
        SharedPreferences prefs = context.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        prefs.edit().putString("parent_password", newPassword).apply();
    }
}