package com.example.detoxify;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Sends the child back to the lock screen when the phone is gated. */
final class PhoneLockRedirect {

    private PhoneLockRedirect() {
    }

    static boolean redirectIfGated(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        if (!PhoneLockPolicy.isPhoneGated(prefs)) {
            return false;
        }
        int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                ? PhoneLockedActivity.LOCK_REASON_REMOTE
                : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
        PhoneLockGate.requestLockPresentation(activity, reason);
        activity.finish();
        return true;
    }

    static void finishToHome(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(home);
        } catch (Exception ignored) {
        }
    }
}
