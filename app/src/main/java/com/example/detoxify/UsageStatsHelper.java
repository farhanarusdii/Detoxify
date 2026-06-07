package com.example.detoxify;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Collects foreground usage and syncs to {@code children/{childCode}/todayUsage}. */
public final class UsageStatsHelper {

    private static final String TAG = "UsageStatsHelper";
    private static final int MAX_APPS_TO_SYNC = 40;

    private UsageStatsHelper() {
    }

    public static boolean hasUsageAccess(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
            AppOpsManager aom = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    info.uid,
                    context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static Intent usageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    public static TodayBreakdown computeToday(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        long end = System.currentTimeMillis();

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end);
        if (stats == null) {
            stats = Collections.emptyList();
        }

        Map<String, Long> millisByPackage = new HashMap<>();
        for (UsageStats stat : stats) {
            if (stat == null) {
                continue;
            }
            long t = stat.getTotalTimeInForeground();
            if (t <= 0) {
                continue;
            }
            String pkg = stat.getPackageName();
            if (context.getPackageName().equals(pkg)) {
                continue;
            }
            millisByPackage.merge(pkg, t, Long::sum);
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String dayKey = fmt.format(new Date(start));

        PackageManager pm = context.getPackageManager();
        long totalMs = 0;
        for (long ms : millisByPackage.values()) {
            totalMs += ms;
        }

        List<AppUsageRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : millisByPackage.entrySet()) {
            long ms = e.getValue();
            String label = labelForPackage(pm, e.getKey());
            long mins = (ms + 30000L) / 60000L;
            rows.add(new AppUsageRow(e.getKey(), label, mins));
        }
        rows.sort((a, b) -> Long.compare(b.minutes, a.minutes));

        long totalMinutes = (totalMs + 30000L) / 60000L;

        int[] hourlyMinutes = computeHourlyMinutesFromEvents(context, usm, start, end);
        long lateNightMinutes = sumLateNightMinutes(hourlyMinutes);

        return new TodayBreakdown(dayKey, totalMinutes, totalMs, lateNightMinutes, hourlyMinutes, rows);
    }

    private static int[] computeHourlyMinutesFromEvents(Context context, UsageStatsManager usm,
                                                        long start, long end) {
        int[] buckets = new int[24];
        UsageEvents events = usm.queryEvents(start, end);
        if (events == null) {
            return buckets;
        }
        long sessionStart = -1L;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (context.getPackageName().equals(event.getPackageName())) {
                continue;
            }
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                sessionStart = event.getTimeStamp();
            } else if ((type == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || type == UsageEvents.Event.ACTIVITY_PAUSED)
                    && sessionStart > 0L) {
                addSessionToHourBuckets(buckets, sessionStart, event.getTimeStamp());
                sessionStart = -1L;
            }
        }
        return buckets;
    }

    private static void addSessionToHourBuckets(int[] hourMinutes, long startMs, long endMs) {
        if (endMs <= startMs) {
            return;
        }
        Calendar c = Calendar.getInstance();
        long pos = startMs;
        while (pos < endMs) {
            c.setTimeInMillis(pos);
            int hour = c.get(Calendar.HOUR_OF_DAY);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long hourStart = c.getTimeInMillis();
            c.add(Calendar.HOUR_OF_DAY, 1);
            long hourEnd = c.getTimeInMillis();
            long segStart = Math.max(pos, hourStart);
            long segEnd = Math.min(endMs, hourEnd);
            if (segEnd > segStart) {
                int add = (int) ((segEnd - segStart) / 60000L);
                if (add > 0) {
                    hourMinutes[hour] += add;
                }
            }
            pos = hourEnd;
        }
    }

    static long sumLateNightMinutes(int[] hourlyMinutes) {
        if (hourlyMinutes == null || hourlyMinutes.length < 24) {
            return 0L;
        }
        long sum = 0L;
        for (int h = 22; h < 24; h++) {
            sum += hourlyMinutes[h];
        }
        for (int h = 0; h < 6; h++) {
            sum += hourlyMinutes[h];
        }
        return sum;
    }

    private static String labelForPackage(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    public static void uploadTodayUsage(Context context, DatabaseReference databaseRoot, String childCode,
                                        TodayBreakdown breakdown) {
        if (childCode == null || childCode.isEmpty()) {
            return;
        }

        List<Map<String, Object>> appsList = new ArrayList<>();
        int n = Math.min(breakdown.apps.size(), MAX_APPS_TO_SYNC);
        for (int i = 0; i < n; i++) {
            AppUsageRow row = breakdown.apps.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("packageName", row.packageName);
            item.put("label", row.label);
            item.put("minutes", row.minutes);
            appsList.add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("dayKey", breakdown.dayKey);
        payload.put("totalMinutes", breakdown.totalMinutes);
        payload.put("lastUpdated", System.currentTimeMillis());
        payload.put("apps", appsList);

        databaseRoot.child("children").child(childCode).child("todayUsage")
                .setValue(payload)
                .addOnFailureListener(e -> Log.e(TAG, "uploadTodayUsage failed", e));

        Map<String, Object> dayRow = new HashMap<>();
        dayRow.put("totalMinutes", breakdown.totalMinutes);
        dayRow.put("lateNightMinutes", breakdown.lateNightMinutes);
        dayRow.put("lastUpdated", System.currentTimeMillis());
        Map<String, Object> hourly = new HashMap<>();
        for (int h = 0; h < 24; h++) {
            if (breakdown.hourlyMinutes[h] > 0) {
                hourly.put(String.valueOf(h), breakdown.hourlyMinutes[h]);
            }
        }
        if (!hourly.isEmpty()) {
            dayRow.put("hourly", hourly);
        }
        if (!appsList.isEmpty()) {
            dayRow.put("apps", appsList);
        }
        databaseRoot.child("children").child(childCode).child("dailyUsage").child(breakdown.dayKey)
                .setValue(dayRow)
                .addOnFailureListener(e -> Log.e(TAG, "upload dailyUsage failed", e));
    }

    public static void refreshInsightsIfMoodReady(Context context, DatabaseReference databaseRoot,
                                                  String childCode) {
        if (childCode == null || childCode.isEmpty()) {
            return;
        }
        if (MoodCheckInHelper.checkedInToday(context)) {
            InsightsSyncHelper.refreshAll(context, databaseRoot, childCode);
        }
    }

    public static final class TodayBreakdown {
        public final String dayKey;
        public final long totalMinutes;
        /** Raw milliseconds — used for sub-minute-accurate limit enforcement. */
        public final long totalMs;
        public final long lateNightMinutes;
        public final int[] hourlyMinutes;
        public final List<AppUsageRow> apps;

        public TodayBreakdown(String dayKey, long totalMinutes, long totalMs, long lateNightMinutes,
                              int[] hourlyMinutes, List<AppUsageRow> apps) {
            this.dayKey = dayKey;
            this.totalMinutes = totalMinutes;
            this.totalMs = totalMs;
            this.lateNightMinutes = lateNightMinutes;
            this.hourlyMinutes = hourlyMinutes != null ? hourlyMinutes : new int[24];
            this.apps = apps;
        }
    }

    public static final class AppUsageRow {
        public final String packageName;
        public final String label;
        public final long minutes;

        public AppUsageRow(String packageName, String label, long minutes) {
            this.packageName = packageName;
            this.label = label;
            this.minutes = minutes;
        }
    }
}