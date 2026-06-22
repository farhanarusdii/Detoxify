package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;

final class PhoneLockPolicy {

    private PhoneLockPolicy() {}

    static boolean isPhoneGated(SharedPreferences prefs) {
        return prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                || prefs.getBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false);
    }

    /**
     * Returns true for packages that are allowed to remain on screen while the phone is gated.
     *
     * Our own package (com.example.detoxify) is allowed ONLY in two states:
     *   1. The lock screen itself is on top (isLockScreenResumed / isLockScreenLaunching).
     *   2. BedtimeIdeasActivity is open — signalled by isChildInteractionPaused() == true,
     *      which PhoneLockedActivity sets via PhoneLockGate.beginChildInteraction() before
     *      launching BedtimeIdeasActivity, and BedtimeIdeasActivity.onDestroy() clears via
     *      PhoneLockGate.endChildInteraction().
     *
     * Every other Detoxify screen (ChildDashboard, etc.) is NOT allowed while gated.
     * External packages are allowed only for emergency calling.
     */
    static boolean isPackageAllowedWhenPhoneLocked(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;

        if (pkg.equals(context.getPackageName())) {
            // Lock screen itself
            if (PhoneLockGate.isLockScreenResumed() || PhoneLockGate.isLockScreenLaunching()) {
                return true;
            }
            // BedtimeIdeasActivity — only sanctioned non-lock-screen screen while gated
            if (PhoneLockGate.isChildInteractionPaused()) {
                return true;
            }
            return false;
        }

        // Emergency / calling packages — always allowed
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.equals("com.android.phone")
                || p.contains("dialer")
                || p.contains("incallui")
                || p.contains("emergency")
                || p.contains("telecom")
                || p.contains("teleservice");
    }

    static boolean isInputMethodPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("inputmethod")
                || p.contains("keyboard")
                || p.contains("ime")
                || p.contains("swiftkey")
                || p.contains("honeyboard")
                || p.contains("gboard")
                || p.contains("touchtype");
    }

    static boolean isLauncherPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
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
                || p.contains("recents")
                || p.contains("taskview")
                || p.contains("overview");
    }
}