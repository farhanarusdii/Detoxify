package com.example.detoxify;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Checks whether {@link AppBlockAccessibilityService} is enabled (system Settings).
 */
public final class AccessibilityHelper {

    private AccessibilityHelper() {
    }

    public static boolean isBlockServiceEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (list == null || list.isEmpty()) {
            return false;
        }
        String pkg = context.getPackageName();
        String canonical = AppBlockAccessibilityService.class.getCanonicalName();
        String simple = AppBlockAccessibilityService.class.getSimpleName();
        for (AccessibilityServiceInfo info : list) {
            ResolveInfo ri = info.getResolveInfo();
            if (ri == null || ri.serviceInfo == null || !pkg.equals(ri.serviceInfo.packageName)) {
                continue;
            }
            String name = ri.serviceInfo.name;
            if (canonical != null && canonical.equals(name)) {
                return true;
            }
            if (name != null && name.endsWith("." + simple)) {
                return true;
            }
        }
        return false;
    }

    public static Intent accessibilitySettingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
}
