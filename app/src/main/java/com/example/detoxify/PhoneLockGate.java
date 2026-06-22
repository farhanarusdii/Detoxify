package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Central coordinator for lock-screen presentation.
 *
 * KEY DESIGN RULE: interactionPauseDepth / shouldDeferPhoneLockEnforcement only suppresses
 * the ACCESSIBILITY SERVICE from firing Home + re-lock. It must NEVER suppress showLockScreen()
 * itself — if the lock screen isn't visible while gated, it must always be re-raised regardless
 * of interaction state, otherwise dialogs / BedtimeIdeasActivity become an escape hatch.
 */
final class PhoneLockGate {

    static final String ACTION_TIME_REQUEST_DENIED =
            "com.example.detoxify.TIME_REQUEST_DENIED";
    static final String ACTION_ENFORCE_PHONE_LOCK =
            "com.example.detoxify.ENFORCE_PHONE_LOCK";
    static final String ACTION_UNLOCK_PHONE =
            "com.example.detoxify.UNLOCK_PHONE";

    private static volatile boolean lockScreenResumed;
    private static volatile int     interactionPauseDepth;
    private static volatile long    lastLaunchMs;
    private static volatile long    lockScreenLaunchUntilMs;

    // 800 ms — enough to survive a Home press + accessibility event arriving together,
    // short enough that a child cannot exploit the gap.
    private static final long LAUNCH_DEBOUNCE_MS = 800L;

    private PhoneLockGate() {}

    // ── Lock-screen state ────────────────────────────────────────────────────────

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

    // ── Child interaction pause (dialogs, BedtimeIdeasActivity) ─────────────────
    // This ONLY tells the accessibility service not to fire GLOBAL_ACTION_HOME
    // while a sanctioned child-facing screen is open.  It does NOT suppress
    // showLockScreen() — that must always work.

    static void beginChildInteraction() {
        interactionPauseDepth++;
    }

    static void endChildInteraction() {
        if (interactionPauseDepth > 0) interactionPauseDepth--;
    }

    static boolean isChildInteractionPaused() {
        return interactionPauseDepth > 0;
    }

    /**
     * True only while a dialog or BedtimeIdeasActivity is open on top of the lock screen.
     * The accessibility service checks this before firing GLOBAL_ACTION_HOME so it doesn't
     * dismiss the keyboard or interrupt a time-request dialog.
     *
     * IMPORTANT: This does NOT prevent the lock screen from being (re-)launched.
     */
    static boolean shouldDeferAccessibilityHomeAction() {
        return interactionPauseDepth > 0;
    }

    // ── Lock-screen presentation ─────────────────────────────────────────────────

    static boolean isPhoneGated(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        return PhoneLockPolicy.isPhoneGated(prefs);
    }

    static void showLockScreen(Context context, int lockReason) {
        showLockScreen(context, lockReason, false);
    }

    static void enforceDailyLimitLock(Context context) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences("Detoxify", Context.MODE_PRIVATE).edit()
                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, true)
                .commit();
        requestLockPresentation(context, PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
    }

    static void requestLockPresentation(Context context, int lockReason) {
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION_ENFORCE_PHONE_LOCK)
                .putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON, lockReason)
                .setPackage(app.getPackageName()));
        showLockScreen(context, lockReason, true);
    }

    static void showLockScreen(Context context, int lockReason, boolean force) {
        Context app = context.getApplicationContext();
        if (!force && !isPhoneGated(app)) return;

        // NOTE: intentionally NOT checking shouldDeferAccessibilityHomeAction() here.
        // The lock screen must always be raiseable even when a dialog is open — otherwise
        // the dialog becomes a permanent escape from the lock screen.

        long now = System.currentTimeMillis();
        if (now - lastLaunchMs < LAUNCH_DEBOUNCE_MS) return;
        lastLaunchMs = now;
        markLockScreenLaunching();

        Intent i = new Intent(app, PhoneLockedActivity.class);
        i.putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON, lockReason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
    }

    static void clearAllLockFlags(Context context) {
        context.getApplicationContext()
                .getSharedPreferences("Detoxify", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                .commit();
        lockScreenLaunchUntilMs = 0L;
        lastLaunchMs = 0L;
    }

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