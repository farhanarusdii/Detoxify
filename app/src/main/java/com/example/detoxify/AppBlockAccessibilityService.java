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
    // BUG 1 FIX: reduced from 300ms to 150ms so re-lock fires faster in the last minute
    private static final long LOCK_PRESENT_DEBOUNCE_MS = 150L;
    private static final long LIMIT_POLL_MS = 1_500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService limitExecutor = Executors.newSingleThreadExecutor();

    private long lastHomeAt;
    private String lastBlockedPkg;
    private long lastPhoneLockHomeAt;
    private String lastPhoneLockPkg;
    private long lastLockScreenRequestAt;

    private final Runnable limitEnforcementPoll = new Runnable() {
        @Override
        public void run() {
            limitExecutor.execute(() -> {
                try {
                    // BUG 1 FIX: Re-open prefs each tick so we never read a stale cached
                    // value of PREFS_PHONE_LIMIT_EXCEEDED right after BlockMonitorService
                    // commits it. MODE_PRIVATE always returns the same singleton on Android
                    // but the underlying XML is flushed by commit() — re-getting the instance
                    // ensures we see the latest committed value.
                    SharedPreferences prefs = getApplicationContext()
                            .getSharedPreferences("Detoxify", MODE_PRIVATE);

                    boolean remoteLock = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false);
                    if (remoteLock) {
                        prefs.edit().putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true).commit();
                        mainHandler.post(() -> presentLockIfNeeded(
                                PhoneLockedActivity.LOCK_REASON_REMOTE, true));
                        return;
                    }
                    PhoneLimitEvaluator.Result result = PhoneLimitEvaluator.evaluate(
                            AppBlockAccessibilityService.this, prefs);
                    prefs.edit().putLong("used_today", result.usedMinutes).apply();

                    if (result.shouldLock) {
                        prefs.edit()
                                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                                .commit();
                        mainHandler.post(() -> presentLockIfNeeded(
                                PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT, true));
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

    private final BroadcastReceiver enforceLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            int reason = intent.getIntExtra(PhoneLockedActivity.EXTRA_LOCK_REASON,
                    PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
            presentLockIfNeeded(reason, true);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter filter = new IntentFilter(PhoneLockGate.ACTION_ENFORCE_PHONE_LOCK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(enforceLockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(enforceLockReceiver, filter);
        }
        mainHandler.removeCallbacks(limitEnforcementPoll);
        mainHandler.post(limitEnforcementPoll);
        BlockMonitorService.startMonitoring(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();

        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        boolean gated = PhoneLockPolicy.isPhoneGated(prefs);

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
        if (pkgSeq == null) {
            return;
        }
        String pkg = pkgSeq.toString();

        if (pkg.equals(getPackageName())) {
            if (gated) {
                handleOwnAppWhileGated(prefs);
            }
            return;
        }

        if (gated) {
            handlePhoneGatedWindow(pkg, prefs);
            return;
        }

        Set<String> blocked = prefs.getStringSet(BlockMonitorService.PREFS_BLOCKED_PACKAGES,
                Collections.emptySet());
        if (blocked == null || blocked.isEmpty()) {
            return;
        }
        HashSet<String> copy = new HashSet<>(blocked);
        if (!copy.contains(pkg)) {
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (pkg.equals(lastBlockedPkg) && now - lastHomeAt < 600L) {
            return;
        }
        lastBlockedPkg = pkg;
        lastHomeAt = now;

        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private void handleOwnAppWhileGated(SharedPreferences prefs) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) {
            return;
        }
        if (PhoneLockGate.isLockScreenResumed()
                || PhoneLockGate.isLockScreenLaunching()
                || PhoneLockGate.isChildInteractionPaused()) {
            return;
        }
        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
        long now = android.os.SystemClock.uptimeMillis();
        performGlobalAction(GLOBAL_ACTION_HOME);
        requestLockScreen(reason, now);
    }

    private void handlePhoneGatedWindow(String pkg, SharedPreferences prefs) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) {
            return;
        }
        if (PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(this, pkg)) {
            return;
        }
        if (PhoneLockPolicy.isInputMethodPackage(pkg)) {
            return;
        }

        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;

        long now = android.os.SystemClock.uptimeMillis();

        if (PhoneLockPolicy.isLauncherPackage(pkg)) {
            requestLockScreen(reason, now);
            return;
        }

        if (pkg.equals(lastPhoneLockPkg) && now - lastPhoneLockHomeAt < HOME_DEBOUNCE_MS) {
            requestLockScreen(reason, now);
            return;
        }
        lastPhoneLockPkg = pkg;
        lastPhoneLockHomeAt = now;

        performGlobalAction(GLOBAL_ACTION_HOME);
        requestLockScreen(reason, now);
    }

    private void presentLockIfNeeded(int reason, boolean force) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        if (!force && !PhoneLockPolicy.isPhoneGated(prefs)) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        requestLockScreen(reason, now);
    }

    private void requestLockScreen(int reason, long now) {
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) {
            return;
        }
        if (now - lastLockScreenRequestAt < LOCK_PRESENT_DEBOUNCE_MS) {
            return;
        }
        lastLockScreenRequestAt = now;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(limitEnforcementPoll);
        try {
            unregisterReceiver(enforceLockReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        limitExecutor.shutdown();
        super.onDestroy();
    }
}