package com.example.detoxify;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.util.List;

/** Best-effort foreground package: prefers recent usage events, falls back to lastTimeUsed. */
final class ForegroundAppHelper {

    private ForegroundAppHelper() {
    }

    static String getForegroundPackage(Context context) {
        if (!UsageStatsHelper.hasUsageAccess(context)) {
            return null;
        }
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }
        long end = System.currentTimeMillis();
        long begin = end - 8000L;

        UsageEvents events = usm.queryEvents(begin, end);
        if (events != null) {
            UsageEvents.Event ev = new UsageEvents.Event();
            String lastFg = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                int t = ev.getEventType();
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastFg = ev.getPackageName();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastFg = ev.getPackageName();
                }
            }
            if (lastFg != null && !lastFg.isEmpty()) {
                return lastFg;
            }
        }

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end);
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        UsageStats best = null;
        for (UsageStats us : stats) {
            if (us == null) {
                continue;
            }
            if (best == null || us.getLastTimeUsed() > best.getLastTimeUsed()) {
                best = us;
            }
        }
        return best != null ? best.getPackageName() : null;
    }
}
