package com.example.detoxify;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lists pending screen-time requests from all children linked to the logged-in parent.
 * Tapping a row opens {@link ApproveTimeRequestActivity} to approve or deny.
 */
public class ParentReviewRequestsActivity extends AppCompatActivity {

    private DatabaseReference root;
    private Query childrenQuery;
    private ValueEventListener childrenListener;

    private ListView listView;
    private TextView tvEmpty;
    private ArrayAdapter<PendingRequest> adapter;
    private final List<PendingRequest> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_review_requests);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.parent_review_requests_title);
        }

        listView = findViewById(R.id.list_pending_requests);
        tvEmpty = findViewById(R.id.tv_empty_requests);
        listView.setEmptyView(tvEmpty);

        adapter = new ArrayAdapter<>(this, R.layout.list_item_text, rows);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            PendingRequest item = adapter.getItem(position);
            if (item == null) {
                return;
            }
            Intent i = new Intent(this, ApproveTimeRequestActivity.class);
            i.putExtra(ApproveTimeRequestActivity.EXTRA_CHILD_CODE, item.childCode);
            i.putExtra(ApproveTimeRequestActivity.EXTRA_REQUEST_ID, item.requestId);
            startActivity(i);
        });

        root = FirebaseDatabase.getInstance().getReference();
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachListener();
    }

    @Override
    protected void onStop() {
        detachListener();
        super.onStop();
    }

    private void attachListener() {
        detachListener();
        SharedPreferences p = getSharedPreferences("Detoxify", MODE_PRIVATE);
        String parentId = p.getString("userId", "");
        if (TextUtils.isEmpty(parentId)) {
            Toast.makeText(this, R.string.parent_review_requests_not_logged_in, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        childrenQuery = root.child("children").orderByChild("parentId").equalTo(parentId);
        childrenListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<PendingRequest> next = new ArrayList<>();
                for (DataSnapshot childSnap : snapshot.getChildren()) {
                    String childCode = childSnap.getKey();
                    if (childCode == null || childCode.isEmpty()) {
                        continue;
                    }
                    DataSnapshot tr = childSnap.child("timeRequest");
                    String status = tr.child("status").getValue(String.class);
                    String requestId = tr.child("requestId").getValue(String.class);
                    if (!"pending".equals(status) || requestId == null || requestId.isEmpty()) {
                        continue;
                    }
                    Long requested = tr.child("requestedMinutes").getValue(Long.class);
                    long reqMin = requested != null ? requested : 0L;

                    String childName = childSnap.child("childName").getValue(String.class);
                    if (childName == null || childName.isEmpty()) {
                        childName = getString(R.string.notif_child_default_name);
                    }

                    String line = getString(R.string.parent_review_line_pattern,
                            childName,
                            DurationFormat.hoursMinutes(reqMin));
                    next.add(new PendingRequest(childCode, requestId, line));
                }
                Collections.sort(next, PendingRequest.BY_DISPLAY);

                rows.clear();
                rows.addAll(next);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(ParentReviewRequestsActivity.this,
                        getString(R.string.parent_review_requests_load_error, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        };
        childrenQuery.addValueEventListener(childrenListener);
    }

    private void detachListener() {
        if (childrenQuery != null && childrenListener != null) {
            childrenQuery.removeEventListener(childrenListener);
        }
        childrenQuery = null;
        childrenListener = null;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private static final class PendingRequest {
        /** Comparator defined inside the nested class so it can read private fields (sibling inner classes cannot). */
        static final Comparator<PendingRequest> BY_DISPLAY =
                (a, b) -> a.displayLine.compareToIgnoreCase(b.displayLine);

        final String childCode;
        final String requestId;
        final String displayLine;

        PendingRequest(String childCode, String requestId, String displayLine) {
            this.childCode = childCode;
            this.requestId = requestId;
            this.displayLine = displayLine;
        }

        @NonNull
        @Override
        public String toString() {
            return displayLine;
        }
    }
}
