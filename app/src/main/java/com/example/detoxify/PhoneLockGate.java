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

    private static volatile boolean lockScreenResumed;
    private static volatile int interactionPauseDepth;
    private static volatile long lastLaunchMs;
    private static volatile long lockScreenLaunchUntilMs;
    private static final long LAUNCH_DEBOUNCE_MS = 2_500L;

    private PhoneLockGate() {
    }

    static void markLockScreenResumed(boolean resumed) {
        lockScreenResumed = resumed;
    }

    static boolean isLockScreenResumed() {
        return lockScreenResumed;
    }

    static void markLockScreenLaunching() {
        lockScreenLaunchUntilMs = System.currentTimeMillis() + 6_000L;
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
        return lockScreenResumed || interactionPauseDepth > 0;
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
        if (!force && now - lastLaunchMs < LAUNCH_DEBOUNCE_MS) {
            return;
        }
        lastLaunchMs = now;
        markLockScreenLaunching();

        Intent i = new Intent(app, PhoneLockedActivity.class);
        i.putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON, lockReason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
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
