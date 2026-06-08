package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Debounces {@link PhoneLockedActivity} launches so accessibility + background polling
 * do not spam {@code startActivity} and freeze the child device.
 */
final class PhoneLockGate {

    static final String ACTION_TIME_REQUEST_DENIED =
            "com.example.detoxify.TIME_REQUEST_DENIED";
    /** Fired when {@link BlockMonitorService} detects the phone should lock (accessibility shows UI). */
    static final String ACTION_ENFORCE_PHONE_LOCK =
            "com.example.detoxify.ENFORCE_PHONE_LOCK";
    /** Fired when parent approves a request or lifts remote lock — tells lock screen to finish. */
    static final String ACTION_UNLOCK_PHONE =
            "com.example.detoxify.UNLOCK_PHONE";

    private static volatile boolean lockScreenResumed;
    private static volatile int interactionPauseDepth;
    private static volatile long lastLaunchMs;
    private static volatile long lockScreenLaunchUntilMs;
    private static final long LAUNCH_DEBOUNCE_MS = 800L;  // was 2500 — shorter so Home press re-locks fast

    private PhoneLockGate() {
    }

    static void markLockScreenResumed(boolean resumed) {
        lockScreenResumed = resumed;
    }

    static boolean isLockScreenResumed() {
        return lockScreenResumed;
    }

    static void markLockScreenLaunching() {
        lockScreenLaunchUntilMs = System.currentTimeMillis() + 1_500L;
    }

    static boolean isLockScreenLaunching() {
        return System.currentTimeMillis() < lockScreenLaunchUntilMs;
    }

    /** Call while a dialog on the lock screen is open (password, time request, etc.). */
    static void beginChildInteraction() {
        interactionPauseDepth++;
    }

    static void endChildInteraction() {
        if (interactionPauseDepth > 0) {
            interactionPauseDepth--;
        }
    }

    static boolean isChildInteractionPaused() {
        return interactionPauseDepth > 0;
    }

    /**
     * When true, accessibility must not send HOME or relaunch the lock screen
     * (keyboard, dialogs, typing on {@link PhoneLockedActivity}).
     */
    static boolean shouldDeferPhoneLockEnforcement() {
        return interactionPauseDepth > 0;
    }

    static boolean isPhoneGated(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        return PhoneLockPolicy.isPhoneGated(prefs);
    }

    static void showLockScreen(Context context, int lockReason) {
        showLockScreen(context, lockReason, false);
    }

    /**
     * Persists the daily-limit gate and presents the lock screen. Uses {@code commit()} so
     * accessibility and {@link #showLockScreen} see the flag immediately (async {@code apply()}
     * caused the lock UI to be skipped when time ran out).
     */
    static void enforceDailyLimitLock(Context context) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences("Detoxify", Context.MODE_PRIVATE).edit()
                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                .commit();
        requestLockPresentation(context, PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
    }

    /** Sets gate flag, broadcasts to accessibility, and tries to open the lock screen. */
    static void requestLockPresentation(Context context, int lockReason) {
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION_ENFORCE_PHONE_LOCK)
                .putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON, lockReason)
                .setPackage(app.getPackageName()));
        showLockScreen(context, lockReason, true);
    }

    static void showLockScreen(Context context, int lockReason, boolean force) {
        Context app = context.getApplicationContext();
        if (!force && !isPhoneGated(app)) {
            return;
        }
        if (shouldDeferPhoneLockEnforcement()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Debounce ALL calls — forced or not — to prevent the onPause→launch→onPause loop.
        // 800 ms is enough to survive a Home press + accessibility event arriving together.
        if (now - lastLaunchMs < LAUNCH_DEBOUNCE_MS) {
            return;
        }
        lastLaunchMs = now;
        markLockScreenLaunching();

        Intent i = new Intent(app, PhoneLockedActivity.class);
        i.putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON, lockReason);
        // SINGLE_TOP: if PhoneLockedActivity is already on top, route through onNewIntent
        // instead of creating a new instance.  CLEAR_TASK was the flicker root-cause —
        // it destroyed the running instance then immediately created a new one, which
        // triggered onPause → requestLockPresentation → another CLEAR_TASK in a tight loop.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
    }

    /**
     * Clears both gate flags atomically using commit() so every reader (accessibility,
     * lock screen, BlockMonitorService) sees the cleared state immediately.
     * Call this whenever a parent approves extra time or lifts remote lock.
     */
    static void clearAllLockFlags(Context context) {
        context.getApplicationContext()
                .getSharedPreferences("Detoxify", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                .commit();
        // Also reset the launching window so the lock screen won't re-appear.
        lockScreenLaunchUntilMs = 0L;
        lastLaunchMs = 0L;
    }

    /** Broadcasts ACTION_UNLOCK_PHONE so PhoneLockedActivity can finish() itself instantly. */
    static void broadcastUnlock(Context context) {
        context.getApplicationContext().sendBroadcast(
                new Intent(ACTION_UNLOCK_PHONE).setPackage(context.getPackageName()));
    }

    static void notifyTimeRequestDenied(Context context) {
        context.getApplicationContext().sendBroadcast(
                new Intent(ACTION_TIME_REQUEST_DENIED).setPackage(context.getPackageName()));
    }

    static void showDeniedNoticeOnLockScreen(Context context) {
        Context app = context.getApplicationContext();
        if (!isPhoneGated(app)) {
            notifyTimeRequestDenied(context);
            return;
        }
        SharedPreferences prefs = app.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
        showLockScreen(context, reason);
        notifyTimeRequestDenied(context);
    }
}