package com.example.detoxify;

import android.content.Context;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based behavioural analytics: overuse, late-night use, habit patterns, risk level.
 */
public final class BehaviorInsightEngine {

    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";

    private static final int LATE_NIGHT_THRESHOLD_MIN = 30;
    private static final double OVERUSE_AVG_MULTIPLIER = 1.25;

    private BehaviorInsightEngine() {
    }

    public static Map<String, Object> compute(Context context, DataSnapshot childSnap) {
        String todayKey = DayKeysHelper.todayKey();
        List<String> window14 = DayKeysHelper.lastNDayKeys(14);

        long dailyLimit = 120L;
        Long lim = childSnap.child("dailyLimit").getValue(Long.class);
        if (lim != null && lim > 0) {
            dailyLimit = lim;
        }

        Map<String, DayUsage> byDay = new HashMap<>();
        DataSnapshot daily = childSnap.child("dailyUsage");
        for (DataSnapshot day : daily.getChildren()) {
            String key = day.getKey();
            if (key == null) {
                continue;
            }
            Long total = day.child("totalMinutes").getValue(Long.class);
            Long late = day.child("lateNightMinutes").getValue(Long.class);
            byDay.put(key, new DayUsage(total != null ? total : 0L, late != null ? late : 0L));
        }

        DataSnapshot today = childSnap.child("todayUsage");
        if (today.exists()) {
            String dk = today.child("dayKey").getValue(String.class);
            Long tm = today.child("totalMinutes").getValue(Long.class);
            if (dk != null && tm != null) {
                long late = 0L;
                DataSnapshot dayHist = daily.child(dk);
                Long lateHist = dayHist.child("lateNightMinutes").getValue(Long.class);
                if (lateHist != null) {
                    late = lateHist;
                }
                byDay.put(dk, new DayUsage(tm, late));
            }
        }

        long todayMinutes = 0L;
        long todayLateNight = 0L;
        DayUsage todayRow = byDay.get(todayKey);
        if (todayRow != null) {
            todayMinutes = todayRow.totalMinutes;
            todayLateNight = todayRow.lateNightMinutes;
        }

        List<String> window7 = DayKeysHelper.lastNDayKeys(7);

        List<Long> totals = new ArrayList<>();
        List<Long> weekdayTotals = new ArrayList<>();
        List<Long> weekendTotals = new ArrayList<>();
        int lateNightsThisWeek = 0;

        for (String dk : window14) {
            DayUsage row = byDay.get(dk);
            if (row == null || row.totalMinutes <= 0) {
                continue;
            }
            totals.add(row.totalMinutes);
            int dow = dayOfWeek(dk);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendTotals.add(row.totalMinutes);
            } else {
                weekdayTotals.add(row.totalMinutes);
            }
        }

        for (String dk : window7) {
            DayUsage row = byDay.get(dk);
            if (row != null && row.lateNightMinutes >= 15) {
                lateNightsThisWeek++;
            }
        }

        long avg = average(totals);
        long weekdayAvg = average(weekdayTotals);
        long weekendAvg = average(weekendTotals);

        boolean overLimit = todayMinutes >= dailyLimit && dailyLimit > 0;
        boolean overAvg = avg > 0 && todayMinutes > avg * OVERUSE_AVG_MULTIPLIER;
        boolean overuse = overLimit || overAvg;

        boolean lateNightToday = todayLateNight >= LATE_NIGHT_THRESHOLD_MIN;
        boolean lateNightHabit = lateNightsThisWeek >= 3;

        List<Map<String, Object>> patterns = new ArrayList<>();
        if (weekdayAvg > 0 && weekendAvg > weekdayAvg * 1.35) {
            patterns.add(patternEntry("weekend_spike", context.getString(R.string.insight_pattern_weekend)));
        }
        if (totals.size() >= 6) {
            long recent3 = sumLast(totals, 3);
            long prior3 = sumPrior(totals, 3);
            if (prior3 > 0 && recent3 > prior3 * 1.2) {
                patterns.add(patternEntry("rising_trend", context.getString(R.string.insight_pattern_rising)));
            }
        }
        if (lateNightHabit) {
            patterns.add(patternEntry("late_night_habit", context.getString(R.string.insight_pattern_late_habit)));
        }

        int riskScore = 0;
        if (overuse) {
            riskScore += 2;
        }
        if (lateNightToday) {
            riskScore += 2;
        }
        if (lateNightHabit) {
            riskScore += 1;
        }
        if (!patterns.isEmpty()) {
            riskScore += Math.min(2, patterns.size());
        }

        String riskLevel;
        if (riskScore >= 5) {
            riskLevel = RISK_HIGH;
        } else if (riskScore >= 2) {
            riskLevel = RISK_MEDIUM;
        } else {
            riskLevel = RISK_LOW;
        }

        String overuseReason = "";
        if (overLimit) {
            overuseReason = context.getString(R.string.insight_overuse_limit,
                    DurationFormat.hoursMinutes(todayMinutes),
                    DurationFormat.hoursMinutes(dailyLimit));
        } else if (overAvg) {
            overuseReason = context.getString(R.string.insight_overuse_avg,
                    DurationFormat.hoursMinutes(todayMinutes),
                    DurationFormat.hoursMinutes(avg));
        }

        String summary = buildSummary(context, riskLevel, overuse, lateNightToday, patterns);

        Map<String, Object> out = new HashMap<>();
        out.put("computedAt", System.currentTimeMillis());
        out.put("riskLevel", riskLevel);
        out.put("riskScore", riskScore);
        out.put("summary", summary);
        out.put("overuse", overuse);
        out.put("overuseReason", overuseReason);
        out.put("lateNightToday", lateNightToday);
        out.put("lateNightMinutesToday", todayLateNight);
        out.put("lateNightHabit", lateNightHabit);
        out.put("patterns", patterns);
        out.put("todayMinutes", todayMinutes);
        out.put("sevenDayAverageMinutes", avg);
        return out;
    }

    public static void upload(DatabaseReference root, String childCode, Map<String, Object> insights) {
        if (childCode == null || childCode.isEmpty() || insights == null) {
            return;
        }
        root.child("children").child(childCode).child("behaviorInsights").child("latest")
                .setValue(insights);
    }

    private static Map<String, Object> patternEntry(String id, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("message", message);
        return m;
    }

    private static String buildSummary(Context context, String riskLevel, boolean overuse,
                                       boolean lateNightToday, List<Map<String, Object>> patterns) {
        if (RISK_HIGH.equals(riskLevel)) {
            return context.getString(R.string.insight_summary_high);
        }
        if (overuse && lateNightToday) {
            return context.getString(R.string.insight_summary_overuse_late);
        }
        if (overuse) {
            return context.getString(R.string.insight_summary_overuse);
        }
        if (lateNightToday) {
            return context.getString(R.string.insight_summary_late);
        }
        if (!patterns.isEmpty()) {
            return context.getString(R.string.insight_summary_patterns);
        }
        return context.getString(R.string.insight_summary_ok);
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static long sumLast(List<Long> values, int n) {
        int start = Math.max(0, values.size() - n);
        long sum = 0L;
        for (int i = start; i < values.size(); i++) {
            sum += values.get(i);
        }
        return sum;
    }

    private static long sumPrior(List<Long> values, int n) {
        int end = Math.max(0, values.size() - n);
        long sum = 0L;
        for (int i = Math.max(0, end - n); i < end; i++) {
            sum += values.get(i);
        }
        return sum;
    }

    private static int dayOfWeek(String dayKey) {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance();
            cal.setTime(fmt.parse(dayKey));
            return cal.get(Calendar.DAY_OF_WEEK);
        } catch (Exception e) {
            return Calendar.MONDAY;
        }
    }

    static final class DayUsage {
        final long totalMinutes;
        final long lateNightMinutes;

        DayUsage(long totalMinutes, long lateNightMinutes) {
            this.totalMinutes = totalMinutes;
            this.lateNightMinutes = lateNightMinutes;
        }
    }
}
