package com.example.detoxify;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.google.firebase.database.DataSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Aggregates {@code dailyUsage} + {@code todayUsage} into weekly/monthly totals. */
public final class UsageReportHelper {

    private UsageReportHelper() {
    }

    public static Map<String, Long> buildMinutesByDay(DataSnapshot dailySnap, DataSnapshot todaySnap,
                                                      List<String> dayKeys) {
        Map<String, Long> minutesByDay = new HashMap<>();
        for (String k : dayKeys) {
            minutesByDay.put(k, 0L);
        }
        if (dailySnap != null && dailySnap.exists()) {
            for (DataSnapshot day : dailySnap.getChildren()) {
                String key = day.getKey();
                if (key == null || !minutesByDay.containsKey(key)) {
                    continue;
                }
                Long m = day.child("totalMinutes").getValue(Long.class);
                minutesByDay.put(key, m != null ? m : 0L);
            }
        }
        if (todaySnap != null && todaySnap.exists()) {
            String dk = todaySnap.child("dayKey").getValue(String.class);
            Long tm = todaySnap.child("totalMinutes").getValue(Long.class);
            if (dk != null && tm != null && minutesByDay.containsKey(dk)) {
                minutesByDay.put(dk, tm);
            }
        }
        return minutesByDay;
    }

    public static long sumMinutes(Map<String, Long> minutesByDay, List<String> dayKeys) {
        long sum = 0L;
        for (String k : dayKeys) {
            Long v = minutesByDay.get(k);
            sum += v != null ? v : 0L;
        }
        return sum;
    }

    public static int countDaysWithData(Map<String, Long> minutesByDay, List<String> dayKeys) {
        int n = 0;
        for (String k : dayKeys) {
            Long v = minutesByDay.get(k);
            if (v != null && v > 0) {
                n++;
            }
        }
        return n;
    }

    /** All calendar days in the current month (including today), oldest first. */
    public static List<String> currentMonthDayKeys() {
        Calendar cal = Calendar.getInstance();
        int month = cal.get(Calendar.MONTH);
        int year = cal.get(Calendar.YEAR);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        List<String> keys = new ArrayList<>();
        while (cal.get(Calendar.MONTH) == month && cal.get(Calendar.YEAR) == year) {
            keys.add(fmt.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return keys;
    }

    public static String currentMonthLabel() {
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                .format(Calendar.getInstance().getTime());
    }

    /** Sum screen minutes for current calendar month from a child's {@code dailyUsage} node. */
    public static long monthTotalFromDaily(DataSnapshot dailySnap, DataSnapshot todaySnap) {
        List<String> monthKeys = currentMonthDayKeys();
        Map<String, Long> byDay = buildMinutesByDay(dailySnap, todaySnap, monthKeys);
        return sumMinutes(byDay, monthKeys);
    }

    /** Top apps by total minutes in the current calendar month (from per-day {@code apps} lists). */
    public static List<TopAppEntry> topAppsForMonth(DataSnapshot dailySnap, DataSnapshot todaySnap, int limit) {
        List<String> monthKeys = currentMonthDayKeys();
        Set<String> monthSet = new HashSet<>(monthKeys);
        String todayKey = DayKeysHelper.todayKey();

        Map<String, Long> minutesByApp = new HashMap<>();

        if (dailySnap != null && dailySnap.exists()) {
            for (DataSnapshot day : dailySnap.getChildren()) {
                String dayKey = day.getKey();
                if (dayKey == null || !monthSet.contains(dayKey) || dayKey.equals(todayKey)) {
                    continue;
                }
                mergeAppsFromDay(day.child("apps"), minutesByApp);
            }
        }
        if (todaySnap != null && todaySnap.exists()) {
            String dk = todaySnap.child("dayKey").getValue(String.class);
            if (dk != null && monthSet.contains(dk)) {
                mergeAppsFromDay(todaySnap.child("apps"), minutesByApp);
            }
        }

        List<TopAppEntry> ranked = new ArrayList<>();
        for (Map.Entry<String, Long> e : minutesByApp.entrySet()) {
            if (e.getValue() > 0) {
                ranked.add(new TopAppEntry(e.getKey(), e.getValue()));
            }
        }
        Collections.sort(ranked, (a, b) -> Long.compare(b.minutes, a.minutes));
        if (ranked.size() > limit) {
            return ranked.subList(0, limit);
        }
        return ranked;
    }

    private static void mergeAppsFromDay(DataSnapshot appsSnap, Map<String, Long> minutesByApp) {
        if (appsSnap == null || !appsSnap.exists()) {
            return;
        }
        for (DataSnapshot app : appsSnap.getChildren()) {
            String label = app.child("label").getValue(String.class);
            String packageName = app.child("packageName").getValue(String.class);
            Long mins = app.child("minutes").getValue(Long.class);
            if (mins == null || mins <= 0) {
                continue;
            }
            String key = label != null && !label.isEmpty()
                    ? label
                    : (packageName != null ? packageName : "App");
            minutesByApp.merge(key, mins, Long::sum);
        }
    }

    /** Fills a vertical container with ranked app rows (used in reports and compare screens). */
    public static void bindTopAppsList(Context context, LinearLayout parent,
                                       List<TopAppEntry> topApps, @StringRes int emptyTextRes) {
        parent.removeAllViews();
        if (topApps == null || topApps.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(emptyTextRes);
            empty.setTextColor(context.getColor(R.color.detox_text_secondary));
            empty.setTextSize(13f);
            parent.addView(empty);
            return;
        }

        long maxMinutes = topApps.get(0).minutes;
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < topApps.size(); i++) {
            TopAppEntry entry = topApps.get(i);
            View row = inflater.inflate(R.layout.item_top_app_row, parent, false);
            TextView rank = row.findViewById(R.id.tv_app_rank);
            TextView name = row.findViewById(R.id.tv_app_name);
            TextView time = row.findViewById(R.id.tv_app_time);
            ProgressBar bar = row.findViewById(R.id.progress_app);

            rank.setText((i + 1) + ".");
            name.setText(entry.label);
            time.setText(DurationFormat.hoursMinutes(entry.minutes));
            int pct = maxMinutes > 0 ? (int) ((entry.minutes * 100L) / maxMinutes) : 0;
            bar.setProgress(Math.max(pct, 4));
            parent.addView(row);
        }
    }

    public static final class TopAppEntry {
        public final String label;
        public final long minutes;

        public TopAppEntry(String label, long minutes) {
            this.label = label;
            this.minutes = minutes;
        }
    }
}
