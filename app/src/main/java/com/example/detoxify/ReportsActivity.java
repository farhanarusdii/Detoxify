package com.example.detoxify;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parent report for one child: weekly &amp; monthly screen time, behaviour insight, mood correlation.
 */
public class ReportsActivity extends AppCompatActivity {

    public static final String EXTRA_CHILD_NAME = "childName";
    public static final String EXTRA_CHILD_CODE = "childCode";
    public static final String EXTRA_MODE = "reportMode";
    public static final String MODE_REPORTS = "reports";
    public static final String MODE_INSIGHTS = "insights";

    private TextView tvChildName;
    private View sectionWeeklyMonthly;
    private View sectionInsights;
    private TextView tvWeeklyTotal;
    private TextView tvDailyAverage;
    private TextView tvWeeklyFootnote;
    private TextView tvMonthLabel;
    private TextView tvMonthlyTotal;
    private TextView tvMonthlyFootnote;
    private LinearLayout layoutMonthlyTopApps;
    private TextView tvRiskLevel;
    private TextView tvBehaviorSummary;
    private TextView tvOveruse;
    private TextView tvLateNight;
    private TextView tvPatterns;
    private TextView tvMoodInsight;
    private TextView tvMoodRecent;

    private DatabaseReference mDatabase;
    private String childCode;
    private String childName;
    private String reportMode = MODE_REPORTS;
    private TextView tvLowMoodAvg;
    private TextView tvHighMoodAvg;
    @Nullable
    private ValueEventListener dailyListener;
    @Nullable
    private ValueEventListener todayListener;
    @Nullable
    private ValueEventListener behaviorListener;
    @Nullable
    private ValueEventListener moodListener;
    @Nullable
    private DatabaseReference dailyRef;
    @Nullable
    private DatabaseReference todayRef;
    @Nullable
    private DatabaseReference behaviorRef;
    @Nullable
    private DatabaseReference moodRef;

    private DataSnapshot lastDaily;
    private DataSnapshot lastToday;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reports);

        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);
        childCode = getIntent().getStringExtra(EXTRA_CHILD_CODE);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_INSIGHTS.equals(mode)) {
            reportMode = MODE_INSIGHTS;
        }
        if (TextUtils.isEmpty(childName)) {
            childName = "Child";
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(MODE_INSIGHTS.equals(reportMode)
                    ? R.string.insights_page_title
                    : R.string.reports_title);
        }

        tvChildName = findViewById(R.id.tv_child_name);
        sectionWeeklyMonthly = findViewById(R.id.section_weekly_monthly);
        sectionInsights = findViewById(R.id.section_insights);
        TextView tvSubtitle = findViewById(R.id.tv_reports_subtitle);
        tvWeeklyTotal = findViewById(R.id.tv_weekly_total);
        tvDailyAverage = findViewById(R.id.tv_daily_average);
        tvWeeklyFootnote = findViewById(R.id.tv_weekly_footnote);
        tvMonthLabel = findViewById(R.id.tv_month_label);
        tvMonthlyTotal = findViewById(R.id.tv_monthly_total);
        tvMonthlyFootnote = findViewById(R.id.tv_monthly_footnote);
        layoutMonthlyTopApps = findViewById(R.id.layout_monthly_top_apps);
        tvRiskLevel = findViewById(R.id.tv_risk_level);
        tvBehaviorSummary = findViewById(R.id.tv_behavior_summary);
        tvOveruse = findViewById(R.id.tv_overuse);
        tvLateNight = findViewById(R.id.tv_late_night);
        tvPatterns = findViewById(R.id.tv_patterns);
        tvMoodInsight = findViewById(R.id.tv_mood_insight);
        tvMoodRecent = findViewById(R.id.tv_mood_recent);
        tvLowMoodAvg = findViewById(R.id.tv_low_mood_avg);
        tvHighMoodAvg = findViewById(R.id.tv_high_mood_avg);
        tvChildName.setText(childName);
        tvMonthLabel.setText(UsageReportHelper.currentMonthLabel());

        boolean showReports = MODE_REPORTS.equals(reportMode);
        boolean showInsights = MODE_INSIGHTS.equals(reportMode);
        sectionWeeklyMonthly.setVisibility(showReports ? View.VISIBLE : View.GONE);
        sectionInsights.setVisibility(showInsights ? View.VISIBLE : View.GONE);
        tvSubtitle.setText(showInsights
                ? R.string.reports_subtitle_insights
                : R.string.reports_subtitle_usage);

        if (TextUtils.isEmpty(childCode)) {
            showMissingChild();
            return;
        }

        mDatabase = FirebaseDatabase.getInstance().getReference();
        if (showInsights) {
            InsightsSyncHelper.refreshAll(this, mDatabase, childCode);
        }
        attachListeners();
    }

    @Override
    protected void onDestroy() {
        detachListeners();
        super.onDestroy();
    }

    private void showMissingChild() {
        tvWeeklyTotal.setText("—");
        tvDailyAverage.setText("—");
        tvMonthlyTotal.setText("—");
        tvWeeklyFootnote.setText(R.string.reports_missing_child_code);
        tvBehaviorSummary.setText(R.string.reports_missing_child_code);
        tvMoodInsight.setText("");
    }

    private void attachListeners() {
        detachListeners();

        boolean showReports = MODE_REPORTS.equals(reportMode);
        boolean showInsights = MODE_INSIGHTS.equals(reportMode);

        if (showReports) {
            dailyRef = mDatabase.child("children").child(childCode).child("dailyUsage");
            todayRef = mDatabase.child("children").child(childCode).child("todayUsage");
        }
        if (showInsights) {
            behaviorRef = mDatabase.child("children").child(childCode).child("behaviorInsights").child("latest");
            moodRef = mDatabase.child("children").child(childCode).child("moodInsights").child("latest");
        }

        dailyListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                lastDaily = snapshot;
                applyUsageReports();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvWeeklyFootnote.setText(getString(R.string.reports_load_error, error.getMessage()));
            }
        };
        todayListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                lastToday = snapshot;
                applyUsageReports();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvWeeklyFootnote.setText(getString(R.string.reports_load_error, error.getMessage()));
            }
        };
        behaviorListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                bindBehavior(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvBehaviorSummary.setText(getString(R.string.reports_load_error, error.getMessage()));
            }
        };
        moodListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                bindMood(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvMoodInsight.setText(getString(R.string.reports_load_error, error.getMessage()));
            }
        };

        if (dailyRef != null && dailyListener != null) {
            dailyRef.addValueEventListener(dailyListener);
        }
        if (todayRef != null && todayListener != null) {
            todayRef.addValueEventListener(todayListener);
        }
        if (behaviorRef != null && behaviorListener != null) {
            behaviorRef.addValueEventListener(behaviorListener);
        }
        if (moodRef != null && moodListener != null) {
            moodRef.addValueEventListener(moodListener);
        }
    }

    private void detachListeners() {
        if (dailyRef != null && dailyListener != null) {
            dailyRef.removeEventListener(dailyListener);
        }
        if (todayRef != null && todayListener != null) {
            todayRef.removeEventListener(todayListener);
        }
        if (behaviorRef != null && behaviorListener != null) {
            behaviorRef.removeEventListener(behaviorListener);
        }
        if (moodRef != null && moodListener != null) {
            moodRef.removeEventListener(moodListener);
        }
        dailyRef = null;
        todayRef = null;
        behaviorRef = null;
        moodRef = null;
        dailyListener = null;
        todayListener = null;
        behaviorListener = null;
        moodListener = null;
    }

    private void applyUsageReports() {
        List<String> weekKeys = DayKeysHelper.lastNDayKeys(7);
        List<String> monthKeys = UsageReportHelper.currentMonthDayKeys();

        Map<String, Long> weekByDay = UsageReportHelper.buildMinutesByDay(lastDaily, lastToday, weekKeys);
        Map<String, Long> monthByDay = UsageReportHelper.buildMinutesByDay(lastDaily, lastToday, monthKeys);

        long weekSum = UsageReportHelper.sumMinutes(weekByDay, weekKeys);
        int weekDaysWithData = UsageReportHelper.countDaysWithData(weekByDay, weekKeys);
        long weekAvg = weekSum / 7L;

        long monthSum = UsageReportHelper.sumMinutes(monthByDay, monthKeys);
        int monthDaysWithData = UsageReportHelper.countDaysWithData(monthByDay, monthKeys);

        tvWeeklyTotal.setText(DurationFormat.hoursMinutes(weekSum));
        tvDailyAverage.setText(DurationFormat.hoursMinutes(weekAvg));

        if (weekSum == 0 && (lastDaily == null || !lastDaily.exists())) {
            tvWeeklyFootnote.setText(R.string.reports_empty_history);
        } else {
            tvWeeklyFootnote.setText(getString(R.string.reports_footnote_pattern, weekDaysWithData));
        }

        tvMonthlyTotal.setText(getString(R.string.reports_monthly_total_label,
                DurationFormat.hoursMinutes(monthSum)));

        List<UsageReportHelper.TopAppEntry> topApps =
                UsageReportHelper.topAppsForMonth(lastDaily, lastToday, 5);
        bindMonthlyTopApps(topApps);

        if (monthSum == 0 && topApps.isEmpty()) {
            tvMonthlyFootnote.setText(R.string.reports_monthly_empty);
        } else if (topApps.isEmpty()) {
            tvMonthlyFootnote.setText(R.string.reports_monthly_top_empty);
        } else {
            tvMonthlyFootnote.setText(getString(R.string.reports_monthly_footnote,
                    monthDaysWithData, monthKeys.size()));
        }
    }

    private void bindMonthlyTopApps(List<UsageReportHelper.TopAppEntry> topApps) {
        UsageReportHelper.bindTopAppsList(this, layoutMonthlyTopApps, topApps,
                R.string.reports_monthly_top_empty);
    }

    private void bindBehavior(DataSnapshot snap) {
        if (!snap.exists()) {
            tvRiskLevel.setText("—");
            tvBehaviorSummary.setText(R.string.insights_empty_behavior);
            tvOveruse.setText("");
            tvLateNight.setText("");
            tvPatterns.setText("");
            return;
        }

        String risk = snap.child("riskLevel").getValue(String.class);
        tvRiskLevel.setText(riskLabel(risk));
        tintRisk(risk);

        String summary = snap.child("summary").getValue(String.class);
        tvBehaviorSummary.setText(summary != null ? summary : "");

        Boolean overuse = snap.child("overuse").getValue(Boolean.class);
        String overuseReason = snap.child("overuseReason").getValue(String.class);
        if (Boolean.TRUE.equals(overuse) && overuseReason != null && !overuseReason.isEmpty()) {
            tvOveruse.setText(overuseReason);
        } else if (Boolean.TRUE.equals(overuse)) {
            tvOveruse.setText(R.string.insights_overuse_yes);
        } else {
            tvOveruse.setText(R.string.insights_overuse_no);
        }

        Boolean lateToday = snap.child("lateNightToday").getValue(Boolean.class);
        Long lateMin = snap.child("lateNightMinutesToday").getValue(Long.class);
        long late = lateMin != null ? lateMin : 0L;
        if (Boolean.TRUE.equals(lateToday) && late > 0) {
            tvLateNight.setText(getString(R.string.insights_late_night_pattern,
                    DurationFormat.hoursMinutes(late)));
        } else {
            tvLateNight.setText(R.string.insights_late_night_none);
        }

        List<String> patternLines = new ArrayList<>();
        for (DataSnapshot p : snap.child("patterns").getChildren()) {
            String msg = p.child("message").getValue(String.class);
            if (msg != null && !msg.isEmpty()) {
                patternLines.add("• " + msg);
            }
        }
        if (patternLines.isEmpty()) {
            tvPatterns.setText(R.string.insights_no_patterns);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < patternLines.size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append(patternLines.get(i));
            }
            tvPatterns.setText(sb.toString());
        }
    }

    private void bindMood(DataSnapshot snap) {
        if (!snap.exists()) {
            tvMoodInsight.setText(R.string.insights_empty_mood);
            tvMoodRecent.setText("");
            return;
        }
        Long lowAvg = snap.child("lowMoodAvgMinutes").getValue(Long.class);
        Long highAvg = snap.child("highMoodAvgMinutes").getValue(Long.class);
        Long pairedDays = snap.child("pairedDays").getValue(Long.class);

        if (tvLowMoodAvg != null) {
            tvLowMoodAvg.setText(lowAvg != null && lowAvg > 0
                    ? DurationFormat.hoursMinutes(lowAvg) : "—");
        }
        if (tvHighMoodAvg != null) {
            tvHighMoodAvg.setText(highAvg != null && highAvg > 0
                    ? DurationFormat.hoursMinutes(highAvg) : "—");
        }
        String insight = snap.child("insightText").getValue(String.class);
        tvMoodInsight.setText(insight != null ? insight : "");

        List<String> lines = new ArrayList<>();
        for (DataSnapshot row : snap.child("recentCheckIns").getChildren()) {
            String label = row.child("label").getValue(String.class);
            String dayKey = row.child("dayKey").getValue(String.class);
            Long screen = row.child("screenMinutes").getValue(Long.class);
            if (label == null) {
                label = "";
            }
            if (dayKey == null) {
                dayKey = "";
            }
            long min = screen != null ? screen : 0L;
            lines.add(getString(R.string.mood_recent_line, dayKey, label,
                    DurationFormat.hoursMinutes(min)));
        }
        if (lines.isEmpty()) {
            tvMoodRecent.setText("");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append(lines.get(i));
            }
            tvMoodRecent.setText(sb.toString());
        }
    }

    private String riskLabel(@Nullable String risk) {
        if (BehaviorInsightEngine.RISK_HIGH.equals(risk)) {
            return getString(R.string.insights_risk_high);
        }
        if (BehaviorInsightEngine.RISK_MEDIUM.equals(risk)) {
            return getString(R.string.insights_risk_medium);
        }
        return getString(R.string.insights_risk_low);
    }

    private void tintRisk(@Nullable String risk) {
        int color;
        if (BehaviorInsightEngine.RISK_HIGH.equals(risk)) {
            color = getResources().getColor(R.color.detox_rose, getTheme());
        } else if (BehaviorInsightEngine.RISK_MEDIUM.equals(risk)) {
            color = getResources().getColor(R.color.detox_peach, getTheme());
        } else {
            color = getResources().getColor(R.color.detox_olive, getTheme());
        }
        tvRiskLevel.setTextColor(color);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
