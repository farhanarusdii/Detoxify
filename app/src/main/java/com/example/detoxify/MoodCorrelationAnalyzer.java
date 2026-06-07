package com.example.detoxify;

import android.content.Context;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Links mood check-ins to daily screen time and surfaces a simple correlation insight.
 */
public final class MoodCorrelationAnalyzer {

    private MoodCorrelationAnalyzer() {
    }

    public static Map<String, Object> compute(Context context, DataSnapshot childSnap) {
        Map<String, Integer> moodByDay = new HashMap<>();
        DataSnapshot moods = childSnap.child("moodCheckIns");
        for (DataSnapshot entry : moods.getChildren()) {
            String dayKey = entry.child("dayKey").getValue(String.class);
            Long mood = entry.child("mood").getValue(Long.class);
            if (dayKey == null || mood == null) {
                continue;
            }
            int m = mood.intValue();
            Integer existing = moodByDay.get(dayKey);
            if (existing == null || m > existing) {
                moodByDay.put(dayKey, m);
            }
        }

        Map<String, Long> screenByDay = new HashMap<>();
        for (DataSnapshot day : childSnap.child("dailyUsage").getChildren()) {
            String key = day.getKey();
            Long total = day.child("totalMinutes").getValue(Long.class);
            if (key != null && total != null) {
                screenByDay.put(key, total);
            }
        }
        DataSnapshot today = childSnap.child("todayUsage");
        if (today.exists()) {
            String dk = today.child("dayKey").getValue(String.class);
            Long tm = today.child("totalMinutes").getValue(Long.class);
            if (dk != null && tm != null) {
                screenByDay.put(dk, tm);
            }
        }

        List<Integer> lowMoodScreen = new ArrayList<>();
        List<Integer> highMoodScreen = new ArrayList<>();
        List<Map<String, Object>> recentCheckIns = new ArrayList<>();

        for (DataSnapshot entry : moods.getChildren()) {
            String dayKey = entry.child("dayKey").getValue(String.class);
            Long mood = entry.child("mood").getValue(Long.class);
            String label = entry.child("label").getValue(String.class);
            Long ts = entry.child("timestamp").getValue(Long.class);
            if (dayKey == null || mood == null) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("dayKey", dayKey);
            row.put("mood", mood.intValue());
            row.put("label", label != null ? label : "");
            row.put("timestamp", ts != null ? ts : 0L);
            Long screen = screenByDay.get(dayKey);
            row.put("screenMinutes", screen != null ? screen : 0L);
            recentCheckIns.add(row);

            if (screen == null || screen <= 0) {
                continue;
            }
            int m = mood.intValue();
            if (m <= 2) {
                lowMoodScreen.add(screen.intValue());
            } else if (m >= 4) {
                highMoodScreen.add(screen.intValue());
            }
        }

        recentCheckIns.sort((a, b) -> Long.compare(
                (Long) b.get("timestamp"), (Long) a.get("timestamp")));
        if (recentCheckIns.size() > 10) {
            recentCheckIns = recentCheckIns.subList(0, 10);
        }

        long lowAvg = averageInt(lowMoodScreen);
        long highAvg = averageInt(highMoodScreen);
        int pairedDays = lowMoodScreen.size() + highMoodScreen.size();

        String insightText;
        if (pairedDays < 2) {
            insightText = context.getString(R.string.mood_insight_need_more);
        } else if (lowAvg > 0 && highAvg > 0 && lowAvg > highAvg) {
            int pct = (int) Math.round((lowAvg - highAvg) * 100.0 / Math.max(1, highAvg));
            insightText = context.getString(R.string.mood_insight_higher_on_low,
                    DurationFormat.hoursMinutes(lowAvg),
                    DurationFormat.hoursMinutes(highAvg),
                    pct);
        } else if (lowAvg > 0 && highAvg > 0) {
            insightText = context.getString(R.string.mood_insight_balanced,
                    DurationFormat.hoursMinutes(lowAvg),
                    DurationFormat.hoursMinutes(highAvg));
        } else {
            insightText = context.getString(R.string.mood_insight_partial);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("computedAt", System.currentTimeMillis());
        out.put("insightText", insightText);
        out.put("lowMoodAvgMinutes", lowAvg);
        out.put("highMoodAvgMinutes", highAvg);
        out.put("pairedDays", pairedDays);
        out.put("checkInCount", moodByDay.size());
        out.put("recentCheckIns", recentCheckIns);
        return out;
    }

    public static void upload(DatabaseReference root, String childCode, Map<String, Object> insights) {
        if (childCode == null || childCode.isEmpty() || insights == null) {
            return;
        }
        root.child("children").child(childCode).child("moodInsights").child("latest")
                .setValue(insights);
    }

    private static long averageInt(List<Integer> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (int v : values) {
            sum += v;
        }
        return sum / values.size();
    }
}
