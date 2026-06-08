package com.example.detoxify;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppBlockAccessibilityService extends AccessibilityService {

    private static final long HOME_DEBOUNCE_MS = 400L;
    private static final long LOCK_PRESENT_DEBOUNCE_MS = 150L;
    private static final long LIMIT_POLL_MS = 1_500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService limitExecutor = Executors.newSingleThreadExecutor();

    private long lastHomeAt;
    private String lastBlockedPkg;
    private String lastPhoneLockPkg;
    private long lastPhoneLockHomeAt;
    private long lastLockScreenRequestAt;

    /**
     * Set to true when ACTION_UNLOCK_PHONE arrives so the poll doesn't immediately
     * re-lock while the limit recheck is still in-flight.  Cleared after 3 seconds.
     */
    private volatile boolean unlockInProgress;

    // ── Poll: runs every 1.5 s on a background thread ──────────────────────────
    private final Runnable limitEnforcementPoll = new Runnable() {
        @Override
        public void run() {
            limitExecutor.execute(() -> {
                try {
                    if (unlockInProgress) {
                        return; // parent just approved — don't re-lock yet
                    }
                    // Always re-open prefs so we see the latest commit() from BlockMonitorService.
                    SharedPreferences prefs = getApplicationContext()
                            .getSharedPreferences("Detoxify", MODE_PRIVATE);

                    boolean remoteLock = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false);
                    if (remoteLock) {
                        prefs.edit().putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true).commit();
                        mainHandler.post(() -> forceLockScreen(PhoneLockedActivity.LOCK_REASON_REMOTE));
                        return;
                    }

                    PhoneLimitEvaluator.Result result = PhoneLimitEvaluator.evaluate(
                            AppBlockAccessibilityService.this, prefs);
                    prefs.edit().putLong("used_today", result.usedMinutes).apply();

                    if (result.shouldLock) {
                        // commit() is intentional: accessibility & lock screen read this flag
                        // immediately after; apply() is async and causes a ~30 s gap.
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                                .commit();
                        mainHandler.post(() -> forceLockScreen(PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT));
                    } else {
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                                .apply();
                    }
                } catch (Exception ignored) {
                }
            });
            mainHandler.postDelayed(this, LIMIT_POLL_MS);
        }
    };

    // ── Receivers ───────────────────────────────────────────────────────────────

    /** Receives ACTION_ENFORCE_PHONE_LOCK from BlockMonitorService. */
    private final BroadcastReceiver enforceLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int reason = intent.getIntExtra(PhoneLockedActivity.EXTRA_LOCK_REASON,
                    PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
            forceLockScreen(reason);
        }
    };

    /**
     * Receives ACTION_UNLOCK_PHONE when parent approves.
     * Pauses enforcement so the poll doesn't re-lock immediately after the flags are cleared.
     */
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            unlockInProgress = true;
            // After 3 s the limit recheck in BlockMonitorService will have run and
            // set PREFS_PHONE_LIMIT_EXCEEDED=false, so resume normal enforcement.
            mainHandler.postDelayed(() -> unlockInProgress = false, 3_000L);
        }
    };

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        IntentFilter enforceFilt = new IntentFilter(PhoneLockGate.ACTION_ENFORCE_PHONE_LOCK);
        IntentFilter unlockFilt  = new IntentFilter(PhoneLockGate.ACTION_UNLOCK_PHONE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(enforceLockReceiver, enforceFilt, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(unlockReceiver,      unlockFilt,  Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(enforceLockReceiver, enforceFilt);
            registerReceiver(unlockReceiver,      unlockFilt);
        }

        mainHandler.removeCallbacks(limitEnforcementPoll);
        mainHandler.post(limitEnforcementPoll);
        BlockMonitorService.startMonitoring(this);
    }

    // ── Accessibility events ────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();

        // Re-read prefs fresh every event so we see the latest commit().
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences("Detoxify", MODE_PRIVATE);
        boolean gated = PhoneLockPolicy.isPhoneGated(prefs);

        // While unlock is in progress don't enforce anything.
        if (unlockInProgress) return;

        if (gated && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence pkgSeq = event.getPackageName();
            if (pkgSeq != null) {
                String pkg = pkgSeq.toString();
                if (!pkg.equals(getPackageName())
                        && !PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(this, pkg)
                        && !PhoneLockPolicy.isInputMethodPackage(pkg)) {
                    handlePhoneGatedWindow(pkg, prefs);
                }
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq == null) return;
        String pkg = pkgSeq.toString();

        if (pkg.equals(getPackageName())) {
            if (gated) handleOwnAppWhileGated(prefs);
            return;
        }

        if (gated) {
            handlePhoneGatedWindow(pkg, prefs);
            return;
        }

        // Not gated — enforce blocked-apps list.
        Set<String> blocked = prefs.getStringSet(BlockMonitorService.PREFS_BLOCKED_PACKAGES,
                Collections.emptySet());
        if (blocked == null || blocked.isEmpty()) return;

        HashSet<String> copy = new HashSet<>(blocked);
        if (!copy.contains(pkg)) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (pkg.equals(lastBlockedPkg) && now - lastHomeAt < 600L) return;
        lastBlockedPkg = pkg;
        lastHomeAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ── Gating helpers ──────────────────────────────────────────────────────────

    private void handleOwnAppWhileGated(SharedPreferences prefs) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) return;
        // Lock screen is already on top — nothing to do.
        if (PhoneLockGate.isLockScreenResumed()) return;
        if (PhoneLockGate.isLockScreenLaunching()) return;
        if (PhoneLockGate.isChildInteractionPaused()) return;

        // Our app is visible but it's NOT the lock screen (e.g. ChildDashboard).
        // Send Home so the launcher appears, then immediately overlay the lock screen.
        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
        performGlobalAction(GLOBAL_ACTION_HOME);
        requestLockScreen(reason);
    }

    private void handlePhoneGatedWindow(String pkg, SharedPreferences prefs) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) return;
        // If lock screen is already on top or mid-launch, don't pile on more launches —
        // that is exactly what caused the rapid flicker loop.
        if (PhoneLockGate.isLockScreenResumed()) return;
        if (PhoneLockGate.isLockScreenLaunching()) return;
        if (PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(this, pkg)) return;
        if (PhoneLockPolicy.isInputMethodPackage(pkg)) return;

        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;

        long now = android.os.SystemClock.uptimeMillis();

        if (PhoneLockPolicy.isLauncherPackage(pkg)) {
            // Home/launcher visible while gated → push lock screen immediately.
            requestLockScreen(reason);
            return;
        }

        if (pkg.equals(lastPhoneLockPkg) && now - lastPhoneLockHomeAt < HOME_DEBOUNCE_MS) {
            requestLockScreen(reason);
            return;
        }
        lastPhoneLockPkg = pkg;
        lastPhoneLockHomeAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        requestLockScreen(reason);
    }

    /**
     * Forces the lock screen regardless of gate state or debounce.
     * Used when the poll detects limit exceeded or a broadcast arrives.
     */
    private void forceLockScreen(int reason) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) return;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    private void requestLockScreen(int reason) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLockScreenRequestAt < LOCK_PRESENT_DEBOUNCE_MS) return;
        lastLockScreenRequestAt = now;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(limitEnforcementPoll);
        try { unregisterReceiver(enforceLockReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(unlockReceiver); } catch (IllegalArgumentException ignored) {}
        limitExecutor.shutdown();
        super.onDestroy();
    }
}