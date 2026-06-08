package com.example.detoxify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneLockedActivity extends AppCompatActivity {

    public static final String EXTRA_LOCK_REASON      = "lockReason";
    public static final String EXTRA_SHOW_DENIED_DIALOG = "showDeniedDialog";
    public static final int    LOCK_REASON_DAILY_LIMIT = 1;
    public static final int    LOCK_REASON_REMOTE      = 2;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Periodic recheck while the lock screen is visible.
    private final Runnable recheckLimit = new Runnable() {
        @Override
        public void run() {
            if (!PhoneLockGate.isChildInteractionPaused()) {
                tryDismissIfUnderLimit();
            }
            mainHandler.postDelayed(this, 4_000L);
        }
    };

    private int lockReason = LOCK_REASON_DAILY_LIMIT;
    private boolean deniedDialogVisible;
    private final AtomicBoolean recheckRunning = new AtomicBoolean(false);

    // ── Broadcast receivers ────────────────────────────────────────────────────

    /** Time-request was denied — show the denied dialog on top of the lock screen. */
    private final BroadcastReceiver deniedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showDeniedDialog();
        }
    };

    /**
     * Parent approved / lifted remote lock → dismiss immediately.
     * We drain interactionPauseDepth first so onPause() won't re-raise the lock.
     */
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            while (PhoneLockGate.isChildInteractionPaused()) {
                PhoneLockGate.endChildInteraction();
            }
            finish();
        }
    };

    // ── Static helpers ─────────────────────────────────────────────────────────

    public static void show(Context context) {
        show(context, LOCK_REASON_DAILY_LIMIT);
    }

    public static void show(Context context, int lockReason) {
        PhoneLockGate.showLockScreen(context, lockReason);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PhoneLockGate.markLockScreenResumed(true);
        super.onCreate(savedInstanceState);

        lockReason = getIntent().getIntExtra(EXTRA_LOCK_REASON, LOCK_REASON_DAILY_LIMIT);
        if (lockReason != LOCK_REASON_REMOTE && lockReason != LOCK_REASON_DAILY_LIMIT) {
            lockReason = LOCK_REASON_DAILY_LIMIT;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(R.layout.activity_phone_locked);
        applyLockReasonUi();

        // Back press: intentionally ignored — stay on lock screen.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { /* locked */ }
        });

        Button btnUnlock  = findViewById(R.id.btn_parent_unlock);
        Button btnRequest = findViewById(R.id.btn_request_access);
        btnUnlock.setOnClickListener(v -> showParentPasswordDialog());
        btnRequest.setOnClickListener(v ->
                TimeRequestHelper.showRequestMoreTimeDialog(PhoneLockedActivity.this, false));

        if (getIntent().getBooleanExtra(EXTRA_SHOW_DENIED_DIALOG, false)) {
            mainHandler.postDelayed(this::showDeniedDialog, 400);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int newReason = intent.getIntExtra(EXTRA_LOCK_REASON, lockReason);
        if (newReason == LOCK_REASON_REMOTE || newReason == LOCK_REASON_DAILY_LIMIT) {
            lockReason = newReason;
            applyLockReasonUi();
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_DENIED_DIALOG, false)) {
            mainHandler.postDelayed(this::showDeniedDialog, 400);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter deniedFilt = new IntentFilter(PhoneLockGate.ACTION_TIME_REQUEST_DENIED);
        IntentFilter unlockFilt = new IntentFilter(PhoneLockGate.ACTION_UNLOCK_PHONE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deniedReceiver, deniedFilt, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(unlockReceiver, unlockFilt, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(deniedReceiver, deniedFilt);
            registerReceiver(unlockReceiver, unlockFilt);
        }

        mainHandler.post(recheckLimit);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PhoneLockGate.markLockScreenResumed(true);
        tryDismissIfUnderLimit();
    }

    @Override
    protected void onPause() {
        // Mark not-resumed immediately so accessibility fires the moment we leave.
        PhoneLockGate.markLockScreenResumed(false);

        // Re-read fresh prefs — unlock broadcast may have just cleared gate flags.
        // Only re-request if still locked AND no dialog is open.
        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        if (PhoneLockPolicy.isPhoneGated(prefs) && !PhoneLockGate.isChildInteractionPaused()) {
            PhoneLockGate.requestLockPresentation(this, lockReason);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(recheckLimit);
        try { unregisterReceiver(deniedReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(unlockReceiver); } catch (IllegalArgumentException ignored) {}
        super.onStop();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        if (PhoneLockPolicy.isPhoneGated(prefs) && !PhoneLockGate.isChildInteractionPaused()) {
            PhoneLockGate.requestLockPresentation(this, lockReason);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(recheckLimit);
        executor.shutdown();
        PhoneLockGate.markLockScreenResumed(false);
        super.onDestroy();
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private void applyLockReasonUi() {
        TextView tvTitle   = findViewById(R.id.tv_lock_title);
        TextView tvMessage = findViewById(R.id.tv_lock_message);
        if (tvTitle == null || tvMessage == null) return;

        if (lockReason == LOCK_REASON_REMOTE) {
            tvTitle.setText(R.string.phone_lock_remote_title);
            tvMessage.setText(R.string.phone_lock_remote_message);
        } else {
            tvTitle.setText(R.string.phone_lock_title);
            tvMessage.setText(R.string.phone_lock_message);
        }
    }

    // ── Dismiss logic ──────────────────────────────────────────────────────────

    /**
     * Checks whether the phone should still be locked.
     * Fast-path: if both gate flags are already false (post-approval), finish immediately.
     * Otherwise delegates to PhoneLimitEvaluator for the full usage check.
     */
    private void tryDismissIfUnderLimit() {
        if (!recheckRunning.compareAndSet(false, true)) return;

        Context app = getApplicationContext();
        executor.execute(() -> {
            try {
                SharedPreferences p = app.getSharedPreferences("Detoxify", MODE_PRIVATE);
                String childCode = p.getString("connectedChildCode", "");
                if (childCode.isEmpty()) {
                    runOnUiThread(this::finish);
                    return;
                }

                boolean remoteLock   = p.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false);
                boolean limitExceeded = p.getBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false);

                // Fast-path: parent approved — both flags already cleared.
                if (!remoteLock && !limitExceeded) {
                    runOnUiThread(this::finish);
                    return;
                }

                // Still remote-locked — stay and show correct reason.
                if (remoteLock) {
                    if (lockReason != LOCK_REASON_REMOTE) {
                        lockReason = LOCK_REASON_REMOTE;
                        runOnUiThread(this::applyLockReasonUi);
                    }
                    return;
                }

                // Daily-limit check.
                PhoneLimitEvaluator.Result result = PhoneLimitEvaluator.evaluate(app, p);
                p.edit().putLong("used_today", result.usedMinutes).apply();

                if (!result.shouldLock) {
                    p.edit().putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false).apply();
                    runOnUiThread(this::finish);
                } else {
                    p.edit().putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true).apply();
                    if (lockReason != LOCK_REASON_DAILY_LIMIT) {
                        lockReason = LOCK_REASON_DAILY_LIMIT;
                        runOnUiThread(this::applyLockReasonUi);
                    }
                }
            } finally {
                recheckRunning.set(false);
            }
        });
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────

    private void showDeniedDialog() {
        if (deniedDialogVisible || isFinishing()) return;
        deniedDialogVisible = true;
        PhoneLockGate.beginChildInteraction();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.time_request_denied_title)
                .setMessage(R.string.time_request_denied_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> deniedDialogVisible = false)
                .setCancelable(true)
                .create();
        dialog.setOnDismissListener(d -> {
            deniedDialogVisible = false;
            PhoneLockGate.endChildInteraction();
        });
        dialog.show();
    }

    private void showParentPasswordDialog() {
        PhoneLockGate.beginChildInteraction();

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.phone_lock_parent_hint);
        input.setPadding(50, 20, 50, 20);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.phone_lock_parent_dialog_title)
                .setMessage(R.string.phone_lock_parent_dialog_message)
                .setView(input)
                .setPositiveButton(R.string.phone_lock_parent_unlock, (d, w) ->
                        verifyParentPassword(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(true)
                .create();
        dialog.setOnDismissListener(d -> PhoneLockGate.endChildInteraction());
        dialog.show();

        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            input.postDelayed(() ->
                    imm.showSoftInput(input,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT), 200);
        }
    }

    private void verifyParentPassword(String entered) {
        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        String saved = prefs.getString("parent_password", "");
        if (saved.isEmpty()) saved = "123456";

        if (!entered.equals(saved)) {
            Toast.makeText(this, R.string.phone_lock_parent_failed, Toast.LENGTH_LONG).show();
            return;
        }

        String childCode  = prefs.getString("connectedChildCode", "");
        String childName  = prefs.getString("childName", getString(R.string.add_child_default_name));
        long   usedToday  = prefs.getLong("used_today", 0L);
        long   currLimit  = prefs.getLong("phone_daily_limit",
                prefs.getLong("daily_limit", 120));
        long   minLimit   = Math.max(1L, usedToday + 1L);

        DailyLimitDialogHelper.show(this, childCode, currLimit, minLimit,
                R.string.phone_lock_set_limit_title,
                getString(R.string.phone_lock_set_limit_message, childName,
                        DurationFormat.hoursMinutes(usedToday)),
                true,
                new DailyLimitDialogHelper.Listener() {
                    @Override
                    public void onSaved(long newLimitMinutes) {
                        Toast.makeText(PhoneLockedActivity.this,
                                R.string.phone_lock_parent_success, Toast.LENGTH_LONG).show();
                        finish();
                        PhoneLockRedirect.finishToHome(PhoneLockedActivity.this);
                    }

                    @Override
                    public void onCancelled() {}
                });
    }
}