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

    private static final long HOME_DEBOUNCE_MS         = 400L;
    private static final long LOCK_PRESENT_DEBOUNCE_MS = 150L;
    private static final long LIMIT_POLL_MS            = 1_000L; // poll every 1 s for tighter enforcement

    private final Handler         mainHandler   = new Handler(Looper.getMainLooper());
    private final ExecutorService limitExecutor = Executors.newSingleThreadExecutor();

    private long   lastHomeAt;
    private String lastBlockedPkg;
    private String lastPhoneLockPkg;
    private long   lastPhoneLockHomeAt;
    private long   lastLockScreenRequestAt;

    /** True while parent-approval unlock is in flight (3 s grace). */
    private volatile boolean unlockInProgress;

    // ── Poll ────────────────────────────────────────────────────────────────────

    private final Runnable limitEnforcementPoll = new Runnable() {
        @Override
        public void run() {
            limitExecutor.execute(() -> {
                try {
                    if (unlockInProgress) return;

                    SharedPreferences prefs = getApplicationContext()
                            .getSharedPreferences("Detoxify", MODE_PRIVATE);

                    boolean remoteLock = prefs.getBoolean(
                            BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false);
                    if (remoteLock) {
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                                .commit();
                        mainHandler.post(() -> forceLockScreen(
                                PhoneLockedActivity.LOCK_REASON_REMOTE));
                        return;
                    }

                    PhoneLimitEvaluator.Result result =
                            PhoneLimitEvaluator.evaluate(AppBlockAccessibilityService.this, prefs);
                    prefs.edit().putLong("used_today", result.usedMinutes).apply();

                    if (result.shouldLock) {
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                                .commit();
                        mainHandler.post(() -> forceLockScreen(
                                PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT));
                    } else {
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                                .apply();
                    }
                } catch (Exception ignored) {}
            });
            mainHandler.postDelayed(this, LIMIT_POLL_MS);
        }
    };

    // ── Broadcast receivers ──────────────────────────────────────────────────────

    private final BroadcastReceiver enforceLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int reason = intent.getIntExtra(PhoneLockedActivity.EXTRA_LOCK_REASON,
                    PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
            forceLockScreen(reason);
        }
    };

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            unlockInProgress = true;
            mainHandler.postDelayed(() -> unlockInProgress = false, 3_000L);
        }
    };

    // ── Lifecycle ────────────────────────────────────────────────────────────────

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

    // ── Accessibility events ─────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (unlockInProgress) return;

        int type = event.getEventType();

        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences("Detoxify", MODE_PRIVATE);
        boolean gated = PhoneLockPolicy.isPhoneGated(prefs);

        // ── Notification shade pulled down while locked ──────────────────────────
        // Collapse it immediately; a tap on a notification would open another app.
        if (gated && type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            return;
        }

        // ── Scroll events (some OEM escape patterns) ─────────────────────────────
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

        // ── Not gated: enforce blocked-apps list ─────────────────────────────────
        Set<String> blocked = prefs.getStringSet(
                BlockMonitorService.PREFS_BLOCKED_PACKAGES, Collections.emptySet());
        if (blocked == null || blocked.isEmpty()) return;

        if (!new HashSet<>(blocked).contains(pkg)) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (pkg.equals(lastBlockedPkg) && now - lastHomeAt < 600L) return;
        lastBlockedPkg = pkg;
        lastHomeAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ── Gating helpers ───────────────────────────────────────────────────────────

    private void handleOwnAppWhileGated(SharedPreferences prefs) {
        if (PhoneLockGate.isLockScreenResumed()) return; // lock screen on top — nothing to do
        if (PhoneLockGate.isLockScreenLaunching()) return;
        if (PhoneLockGate.isChildInteractionPaused()) return; // BedtimeIdeasActivity is open — allowed

        if (!PhoneLockGate.shouldDeferAccessibilityHomeAction()) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
        requestLockScreen(reason);
    }

    private void handlePhoneGatedWindow(String pkg, SharedPreferences prefs) {
        if (PhoneLockGate.isLockScreenResumed()) return; // lock screen on top — nothing to do
        if (PhoneLockGate.isLockScreenLaunching()) return;
        if (PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(this, pkg)) return;
        if (PhoneLockPolicy.isInputMethodPackage(pkg)) return;

        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;

        long now = android.os.SystemClock.uptimeMillis();

        // Launcher / home / recents — push lock screen directly without going Home first.
        if (PhoneLockPolicy.isLauncherPackage(pkg)) {
            requestLockScreen(reason);
            return;
        }

        if (pkg.equals(lastPhoneLockPkg) && now - lastPhoneLockHomeAt < HOME_DEBOUNCE_MS) {
            requestLockScreen(reason);
            return;
        }
        lastPhoneLockPkg    = pkg;
        lastPhoneLockHomeAt = now;
        if (!PhoneLockGate.shouldDeferAccessibilityHomeAction()) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
        requestLockScreen(reason);
    }

    private void forceLockScreen(int reason) {
        // forceLockScreen bypasses ALL deferral — used by poll and broadcast receiver.
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    private void requestLockScreen(int reason) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLockScreenRequestAt < LOCK_PRESENT_DEBOUNCE_MS) return;
        lastLockScreenRequestAt = now;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(limitEnforcementPoll);
        try { unregisterReceiver(enforceLockReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(unlockReceiver); } catch (IllegalArgumentException ignored) {}
        limitExecutor.shutdown();
        super.onDestroy();
    }
}