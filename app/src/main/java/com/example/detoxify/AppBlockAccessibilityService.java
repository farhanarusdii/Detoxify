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

    private static final long HOME_DEBOUNCE_MS         = 300L;
    private static final long LOCK_PRESENT_DEBOUNCE_MS = 100L;
    private static final long LIMIT_POLL_MS            = 1_000L;

    private final Handler         mainHandler   = new Handler(Looper.getMainLooper());
    private final ExecutorService limitExecutor = Executors.newSingleThreadExecutor();

    private long   lastHomeAt;
    private String lastBlockedPkg;
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
        if (gated && type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            return;
        }

        // ── Scroll events ────────────────────────────────────────────────────────
        if (gated && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence pkgSeq = event.getPackageName();
            if (pkgSeq != null) {
                String pkg = pkgSeq.toString();
                if (!isOwnOrAllowedPackage(pkg, prefs)) {
                    kickToLockScreen(prefs);
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

        if (!gated) {
            // ── Not gated: enforce blocked-apps list ─────────────────────────────
            Set<String> blocked = prefs.getStringSet(
                    BlockMonitorService.PREFS_BLOCKED_PACKAGES, Collections.emptySet());
            if (blocked == null || blocked.isEmpty()) return;
            if (!new HashSet<>(blocked).contains(pkg)) return;

            long now = android.os.SystemClock.uptimeMillis();
            if (pkg.equals(lastBlockedPkg) && now - lastHomeAt < 600L) return;
            lastBlockedPkg = pkg;
            lastHomeAt = now;
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }

        // ── Gated: ANY window that isn't ours or allowed → lock screen now ───────
        if (isOwnOrAllowedPackage(pkg, prefs)) return;

        kickToLockScreen(prefs);
    }

    // ── Core enforcement ─────────────────────────────────────────────────────────

    /**
     * The single method that handles all gated navigation.
     *
     * Strategy: show the lock screen FIRST (fast), then send Home AFTER a short delay.
     * This means the lock screen appears immediately on top of whatever the child opened,
     * instead of briefly showing the home screen first. The Home press is still sent to
     * clean up the back-stack, but the child never sees the launcher.
     */
    private void kickToLockScreen(SharedPreferences prefs) {
        if (PhoneLockGate.isLockScreenResumed()) return;
        if (PhoneLockGate.isLockScreenLaunching()) return;
        if (PhoneLockGate.isChildInteractionPaused()) return;

        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;

        // 1. Show lock screen immediately — child sees this, not the launcher.
        requestLockScreen(reason);

        // 2. After 250 ms send Home to collapse the back-stack cleanly (silent cleanup).
        //    By this point the lock screen is already on top so Home is invisible to the child.
        mainHandler.postDelayed(() -> {
            if (!unlockInProgress) {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }, 250L);
    }

    private void forceLockScreen(int reason) {
        // Do NOT force the lock screen while BedtimeIdeasActivity (or a dialog) is
        // deliberately open on top of the lock screen.  The poll and enforceLockReceiver
        // both call this every ~1 s; without this guard they close BedtimeIdeas immediately
        // after it opens.  kickToLockScreen() already has this guard for window events;
        // we mirror it here for the broadcast / poll path.
        if (PhoneLockGate.isChildInteractionPaused()) return;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    private void requestLockScreen(int reason) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLockScreenRequestAt < LOCK_PRESENT_DEBOUNCE_MS) return;
        lastLockScreenRequestAt = now;
        PhoneLockGate.showLockScreen(this, reason, true);
    }

    /**
     * Returns true if the given package is allowed to be on screen while gated.
     * Our own package is allowed only when the lock screen or BedtimeIdeasActivity is on top.
     * Input method packages are always allowed (keyboard).
     * Emergency calling packages are always allowed.
     */
    private boolean isOwnOrAllowedPackage(String pkg, SharedPreferences prefs) {
        return PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(
                AppBlockAccessibilityService.this, pkg)
                || PhoneLockPolicy.isInputMethodPackage(pkg);
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