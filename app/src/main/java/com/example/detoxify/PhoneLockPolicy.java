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
     * ONLY two screens are permitted:
     *   1. PhoneLockedActivity itself.
     *   2. BedtimeIdeasActivity — opened deliberately via btn_what_can_i_do.
     *      Signalled by isChildInteractionPaused() == true for the duration it is open.
     *
     * Everything else — home launcher, third-party apps, other Detoxify screens — is blocked.
     */
    static boolean isPackageAllowedWhenPhoneLocked(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;

        if (pkg.equals(context.getPackageName())) {
            if (PhoneLockGate.isLockScreenResumed() || PhoneLockGate.isLockScreenLaunching()) {
                return true;
            }
            // BedtimeIdeasActivity is the only other permitted screen while gated.
            if (PhoneLockGate.isChildInteractionPaused()) {
                return true;
            }
        }

        return false;
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