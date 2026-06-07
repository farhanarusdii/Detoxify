package com.example.detoxify;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CompareChildrenActivity extends AppCompatActivity {

    private BarChart barChart;
    private TextView tvCompareMonth;
    private TextView tvEmpty;
    private LinearLayout layoutCompareTopApps;
    private View layoutCompareTopAppsSection;

    private DatabaseReference root;
    private String parentId;

    private final List<CompareRow> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compare_children);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.compare_title);
        }

        barChart = findViewById(R.id.barChart);
        tvCompareMonth = findViewById(R.id.tv_compare_month);
        tvEmpty = findViewById(R.id.tv_compare_empty);
        layoutCompareTopApps = findViewById(R.id.layout_compare_top_apps);
        layoutCompareTopAppsSection = findViewById(R.id.layout_compare_top_apps_section);

        tvCompareMonth.setText(getString(R.string.compare_month_heading,
                UsageReportHelper.currentMonthLabel()));

        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        parentId = prefs.getString("userId", "");

        root = FirebaseDatabase.getInstance().getReference();

        if (parentId.isEmpty()) {
            Toast.makeText(this, R.string.compare_not_logged_in, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loadComparison();
    }

    private void loadComparison() {
        Query query = root.child("children")
                .orderByChild("parentId")
                .equalTo(parentId);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                rows.clear();

                if (!snapshot.exists()) {
                    showEmpty();
                    return;
                }

                final int totalChildren = (int) snapshot.getChildrenCount();
                final int[] pending = {totalChildren};

                for (DataSnapshot childSnap : snapshot.getChildren()) {
                    String childCode = childSnap.getKey();
                    String childName = childSnap.child("childName").getValue(String.class);

                    if (childName == null || childName.isEmpty()) {
                        childName = getString(R.string.add_child_default_name);
                    }

                    if (childCode == null) {
                        pending[0]--;
                        if (pending[0] == 0) {
                            finishLoading();
                        }
                        continue;
                    }

                    final String finalChildName = childName;

                    root.child("children").child(childCode)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(DataSnapshot full) {
                                    DataSnapshot daily = full.child("dailyUsage");
                                    DataSnapshot today = full.child("todayUsage");

                                    long monthMinutes =
                                            UsageReportHelper.monthTotalFromDaily(daily, today);
                                    List<UsageReportHelper.TopAppEntry> topApps =
                                            UsageReportHelper.topAppsForMonth(daily, today, 5);

                                    rows.add(new CompareRow(finalChildName, monthMinutes, topApps));

                                    pending[0]--;
                                    if (pending[0] == 0) {
                                        finishLoading();
                                    }
                                }

                                @Override
                                public void onCancelled(DatabaseError error) {
                                    pending[0]--;
                                    if (pending[0] == 0) {
                                        finishLoading();
                                    }
                                }
                            });
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(CompareChildrenActivity.this,
                        getString(R.string.parent_picker_load_error, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishLoading() {
        Collections.sort(rows, (a, b) -> Long.compare(b.monthMinutes, a.monthMinutes));

        runOnUiThread(() -> {
            if (rows.isEmpty()) {
                showEmpty();
            } else {
                tvEmpty.setVisibility(View.GONE);
                barChart.setVisibility(View.VISIBLE);
                if (layoutCompareTopAppsSection != null) {
                    layoutCompareTopAppsSection.setVisibility(View.VISIBLE);
                }
                setupBarChart();
                bindMonthlyTopAppsByChild();
            }
        });
    }

    private void setupBarChart() {
        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            CompareRow row = rows.get(i);
            float hours = row.monthMinutes / 60f;
            entries.add(new BarEntry(i, hours));
            labels.add(row.name);
        }

        BarDataSet dataSet = new BarDataSet(entries, getString(R.string.compare_chart_legend));
        dataSet.setColors(
                Color.parseColor("#A8D5BA"),
                Color.parseColor("#F9C5D5"),
                Color.parseColor("#CDE7BE"),
                Color.parseColor("#B5EAD7")
        );
        dataSet.setValueTextSize(12f);

        BarData data = new BarData(dataSet);
        data.setBarWidth(0.5f);
        barChart.setData(data);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setGranularity(1f);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);

        barChart.getAxisRight().setEnabled(false);

        Description description = new Description();
        description.setText("");
        barChart.setDescription(description);

        Legend legend = barChart.getLegend();
        legend.setEnabled(true);

        barChart.animateY(1000);
        barChart.invalidate();
    }

    private void bindMonthlyTopAppsByChild() {
        layoutCompareTopApps.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (CompareRow row : rows) {
            View card = inflater.inflate(R.layout.item_compare_child_monthly,
                    layoutCompareTopApps, false);
            TextView tvName = card.findViewById(R.id.tv_compare_child_name);
            TextView tvTotal = card.findViewById(R.id.tv_compare_child_total);
            LinearLayout appsContainer = card.findViewById(R.id.layout_compare_child_top_apps);

            tvName.setText(row.name);
            tvTotal.setText(getString(R.string.reports_monthly_total_label,
                    DurationFormat.hoursMinutes(row.monthMinutes)));
            UsageReportHelper.bindTopAppsList(this, appsContainer, row.topApps,
                    R.string.reports_monthly_top_empty);

            layoutCompareTopApps.addView(card);
        }
    }

    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        barChart.setVisibility(View.GONE);
        if (layoutCompareTopAppsSection != null) {
            layoutCompareTopAppsSection.setVisibility(View.GONE);
        }
        layoutCompareTopApps.removeAllViews();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    static class CompareRow {
        final String name;
        final long monthMinutes;
        final List<UsageReportHelper.TopAppEntry> topApps;

        CompareRow(String name, long monthMinutes, List<UsageReportHelper.TopAppEntry> topApps) {
            this.name = name;
            this.monthMinutes = monthMinutes;
            this.topApps = topApps != null ? topApps : Collections.emptyList();
        }
    }
}
