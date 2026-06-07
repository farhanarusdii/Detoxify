package com.example.detoxify;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Parent: respond to a child time request — set a new daily limit, unlock remote lock, and mark the request approved (or deny).
 */
public class ApproveTimeRequestActivity extends AppCompatActivity {

    public static final String EXTRA_CHILD_CODE = "childCode";
    public static final String EXTRA_REQUEST_ID = "requestId";

    private DatabaseReference mDatabase;
    private String childCode;
    private String requestId;

    private TextView tvSummary;
    private TextView tvSuggested;
    private EditText etHours;
    private EditText etMinutes;
    private MaterialButton btnApprove;
    private MaterialButton btnDeny;

    private long suggestedLimitMinutes = 120;
    private long usedMinAtLoad;
    private long baselineLimitAtLoad = 120;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_approve_time_request);

        childCode = getIntent().getStringExtra(EXTRA_CHILD_CODE);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.approve_time_request_title);
        }

        tvSummary = findViewById(R.id.tv_request_summary);
        tvSuggested = findViewById(R.id.tv_suggested_hint);
        etHours = findViewById(R.id.et_limit_hours);
        etMinutes = findViewById(R.id.et_limit_minutes);
        btnApprove = findViewById(R.id.btn_approve);
        btnDeny = findViewById(R.id.btn_deny);

        mDatabase = FirebaseDatabase.getInstance().getReference();

        if (TextUtils.isEmpty(childCode) || TextUtils.isEmpty(requestId)) {
            Toast.makeText(this, R.string.approve_missing_args, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loadContext();

        btnApprove.setOnClickListener(v -> approve());
        btnDeny.setOnClickListener(v -> deny());
    }

    private void loadContext() {
        DatabaseReference childRef = mDatabase.child("children").child(childCode);
        childRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists()) {
                    Toast.makeText(ApproveTimeRequestActivity.this, R.string.approve_child_missing,
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                String childName = snap.child("childName").getValue(String.class);
                if (childName == null) {
                    childName = "";
                }

                DataSnapshot tr = snap.child("timeRequest");
                String rid = tr.child("requestId").getValue(String.class);
                String status = tr.child("status").getValue(String.class);
                if (rid == null || rid.isEmpty() || !"pending".equals(status)
                        || !requestId.equals(rid)) {
                    Toast.makeText(ApproveTimeRequestActivity.this, R.string.approve_request_stale,
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                Long requested = tr.child("requestedMinutes").getValue(Long.class);
                long req = requested != null ? requested : 0L;

                Long baseline = snap.child("baselineDailyLimit").getValue(Long.class);
                Long daily = snap.child("dailyLimit").getValue(Long.class);
                long dailyLim = daily != null && daily > 0 ? daily : 120L;
                baselineLimitAtLoad = baseline != null && baseline > 0 ? baseline : dailyLim;

                DataSnapshot today = snap.child("todayUsage");
                Long used = today.child("totalMinutes").getValue(Long.class);
                long usedMin = used != null ? used : 0L;
                usedMinAtLoad = usedMin;

                suggestedLimitMinutes = Math.max(baselineLimitAtLoad, usedMin + req);
                if (suggestedLimitMinutes <= 0) {
                    suggestedLimitMinutes = dailyLim;
                }

                long sh = suggestedLimitMinutes / 60;
                long sm = suggestedLimitMinutes % 60;
                etHours.setText(String.valueOf(sh));
                etMinutes.setText(String.valueOf(sm));

                tvSummary.setText(getString(R.string.approve_summary_pattern,
                        childName.isEmpty() ? getString(R.string.notif_child_default_name) : childName,
                        DurationFormat.hoursMinutes(req)));
                tvSuggested.setText(getString(R.string.approve_suggested_pattern,
                        DurationFormat.hoursMinutes(suggestedLimitMinutes),
                        DurationFormat.hoursMinutes(dailyLim),
                        DurationFormat.hoursMinutes(usedMin)));

                loaded = true;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ApproveTimeRequestActivity.this,
                        getString(R.string.approve_load_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private long parseNewLimitMinutes() {
        String sh = etHours.getText().toString().trim();
        String sm = etMinutes.getText().toString().trim();
        long hours = sh.isEmpty() ? 0L : Long.parseLong(sh);
        long mins = sm.isEmpty() ? 0L : Long.parseLong(sm);
        if (hours < 0 || mins < 0 || mins >= 60) {
            throw new IllegalArgumentException(getString(R.string.time_request_invalid_minutes));
        }
        return hours * 60L + mins;
    }

    private void approve() {
        if (!loaded) {
            return;
        }
        final long newLimit;
        try {
            newLimit = parseNewLimitMinutes();
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.time_request_invalid_numbers, Toast.LENGTH_SHORT).show();
            return;
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage() != null ? e.getMessage()
                    : getString(R.string.time_request_invalid_minutes), Toast.LENGTH_SHORT).show();
            return;
        }
        if (newLimit <= 0) {
            Toast.makeText(this, R.string.approve_limit_positive, Toast.LENGTH_SHORT).show();
            return;
        }

        long grantedExtra = Math.max(0L, newLimit - usedMinAtLoad);
        long expiresAtMs = System.currentTimeMillis() + grantedExtra * 60_000L;

        Map<String, Object> grant = new HashMap<>();
        grant.put("expiresAtMs", expiresAtMs);
        grant.put("baselineLimit", baselineLimitAtLoad);
        grant.put("usageCapMinutes", newLimit);
        grant.put("grantedMinutes", grantedExtra);

        Map<String, Object> updates = new HashMap<>();
        updates.put("dailyLimit", newLimit);
        updates.put("timeGrant", grant);
        updates.put("deviceLock/active", false);
        updates.put("timeRequest/status", "approved");
        updates.put("timeRequest/resolvedAt", ServerValue.TIMESTAMP);
        updates.put("timeRequest/grantedDailyLimitMinutes", newLimit);
        updates.put("timeRequest/grantedMinutes", grantedExtra);
        updates.put("timeRequest/grantExpiresAtMs", expiresAtMs);

        mDatabase.child("children").child(childCode).updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, R.string.approve_success, Toast.LENGTH_LONG).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.approve_failed, e.getMessage() != null ? e.getMessage() : ""),
                        Toast.LENGTH_LONG).show());
    }

    private void deny() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("timeRequest/status", "denied");
        updates.put("timeRequest/resolvedAt", ServerValue.TIMESTAMP);

        mDatabase.child("children").child(childCode).updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, R.string.approve_denied_toast, Toast.LENGTH_LONG).show();
                    finish();
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.approve_failed, e.getMessage() != null ? e.getMessage() : ""),
                        Toast.LENGTH_LONG).show());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
