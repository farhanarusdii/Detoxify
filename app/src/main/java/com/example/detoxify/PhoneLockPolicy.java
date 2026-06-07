package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Packages that may stay on screen while the phone is gated for daily limit (best-effort).
 * Family Link routes emergency calls at the OS level; third-party apps can only approximate.
 */
final class PhoneLockPolicy {

    private PhoneLockPolicy() {
    }

    static boolean isPhoneGated(SharedPreferences prefs) {
        return prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                || prefs.getBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false);
    }

    static boolean isPackageAllowedWhenPhoneLocked(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (pkg.equals(context.getPackageName())) {
            // Only the lock screen (and its dialogs) may show — not the child dashboard or other screens.
            return PhoneLockGate.isLockScreenResumed()
                    || PhoneLockGate.isLockScreenLaunching()
                    || PhoneLockGate.isChildInteractionPaused();
        }
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.equals("com.android.phone")
                || p.contains("dialer")
                || p.contains("incallui")
                || p.contains("emergency")
                || p.contains("telecom")
                || p.contains("teleservice");
    }

    /** Soft keyboard / IME — must not trigger HOME while the child types on the lock screen. */
    static boolean isInputMethodPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("inputmethod")
                || p.contains("keyboard")
                || p.contains("ime")
                || p.contains("swiftkey")
                || p.contains("honeyboard")
                || p.contains("gboard")
                || p.contains("touchtype");
    }

    /**
     * Home/launcher and recents/task-switcher packages: when the child presses Home or
     * opens recents while locked, show the lock screen instead of staying on the launcher.
     * FIX 3: Added recents, taskview, overview to catch the task-switcher overlay,
     * which lets children switch to another app without a WINDOW_STATE_CHANGED event.
     */
    static boolean isLauncherPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("launcher")
                || p.equals("com.android.systemui")
                || p.contains("nexuslauncher")
                || p.contains("trebuchet")
                || p.contains("quickstep")
                || p.contains("miui.home")
                || p.contains("huawei.android.launcher")
                || p.contains("hihonor.android.launcher")
                || p.contains("oppo.launcher")
                || p.contains("sec.android.app.launcher")
                || p.contains("oneplus.launcher")
                || p.contains("nothing.launcher")
                || p.contains("recents")       // recents / task-switcher overlay
                || p.contains("taskview")      // some OEM task view implementations
                || p.contains("overview");     // Pixel overview / recents
    }
}