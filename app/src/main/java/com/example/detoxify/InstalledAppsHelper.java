package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists launchable apps on this device and syncs them to
 * {@code children/{childCode}/blockableApps} so the parent can choose what to block.
 */
public final class InstalledAppsHelper {

    private static final String TAG = "InstalledAppsHelper";
    private static final int MAX_APPS_TO_SYNC = 400;

    private InstalledAppsHelper() {
    }

    public static List<AppRow> listLaunchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL);
        if (infos == null) {
            return Collections.emptyList();
        }

        String myPkg = context.getPackageName();
        Map<String, String> packageToLabel = new HashMap<>();
        for (ResolveInfo ri : infos) {
            if (ri == null || ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || pkg.equals(myPkg)) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            String labelStr = label != null && label.length() > 0 ? label.toString().trim() : pkg;
            packageToLabel.putIfAbsent(pkg, labelStr);
        }

        List<AppRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : packageToLabel.entrySet()) {
            rows.add(new AppRow(e.getKey(), e.getValue()));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        rows.sort((a, b) -> collator.compare(a.label, b.label));

        if (rows.size() > MAX_APPS_TO_SYNC) {
            return new ArrayList<>(rows.subList(0, MAX_APPS_TO_SYNC));
        }
        return rows;
    }

    public static void uploadBlockableApps(DatabaseReference databaseRoot, String childCode, List<AppRow> apps) {
        if (childCode == null || childCode.isEmpty()) {
            return;
        }
        List<Map<String, Object>> appsList = new ArrayList<>();
        int n = Math.min(apps.size(), MAX_APPS_TO_SYNC);
        for (int i = 0; i < n; i++) {
            AppRow row = apps.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("packageName", row.packageName);
            item.put("label", row.label);
            appsList.add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("lastUpdated", System.currentTimeMillis());
        payload.put("apps", appsList);

        databaseRoot.child("children").child(childCode).child("blockableApps")
                .setValue(payload)
                .addOnFailureListener(e -> Log.e(TAG, "uploadBlockableApps failed", e));
    }

    /** Display line for a row; disambiguates duplicate titles. */
    public static List<String> buildDisplayLines(List<AppRow> rows) {
        Map<String, Integer> labelCount = new HashMap<>();
        for (AppRow r : rows) {
            String key = r.label.toLowerCase(Locale.ROOT);
            labelCount.put(key, labelCount.getOrDefault(key, 0) + 1);
        }
        List<String> lines = new ArrayList<>();
        for (AppRow r : rows) {
            String key = r.label.toLowerCase(Locale.ROOT);
            if (labelCount.getOrDefault(key, 0) > 1) {
                lines.add(r.label + " (" + r.packageName + ")");
            } else {
                lines.add(r.label);
            }
        }
        return lines;
    }

    public static final class AppRow {
        public final String packageName;
        public final String label;

        public AppRow(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
