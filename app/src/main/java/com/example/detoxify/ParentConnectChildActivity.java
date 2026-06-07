package com.example.detoxify;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Parent flow: after selecting a child profile, share the link code and wait for the child device
 * to connect before opening the main dashboard.
 */
public class ParentConnectChildActivity extends AppCompatActivity {

    public static final String EXTRA_CHILD_CODE = "childCode";
    public static final String EXTRA_CHILD_NAME = "childName";

    private String childCode;
    private String childName;
    private DatabaseReference childRef;
    private ValueEventListener connectedListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_connect_child);

        childCode = getIntent().getStringExtra(EXTRA_CHILD_CODE);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);
        if (childCode == null || childCode.isEmpty()) {
            finish();
            return;
        }
        if (childName == null || childName.isEmpty()) {
            childName = getString(R.string.add_child_default_name);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.flow_connect_child_toolbar);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        TextView tvName = findViewById(R.id.tv_connect_child_name);
        TextView tvCode = findViewById(R.id.tv_connect_child_code);
        TextView tvStatus = findViewById(R.id.tv_connect_status);
        MaterialButton btnContinue = findViewById(R.id.btn_continue_dashboard);

        tvName.setText(childName);
        tvCode.setText(childCode);

        childRef = FirebaseDatabase.getInstance().getReference("children").child(childCode);
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean connected = snapshot.child("connected").getValue(Boolean.class);
                boolean online = connected != null && connected;
                tvStatus.setText(online
                        ? R.string.flow_connect_status_online
                        : R.string.flow_connect_status_waiting);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                tvStatus.setText(getString(R.string.flow_connect_status_error, error.getMessage()));
            }
        };
        childRef.child("connected").addValueEventListener(connectedListener);

        btnContinue.setOnClickListener(v -> {
            Intent intent = new Intent(this, ParentDashboardActivity.class);
            intent.putExtra(ParentDashboardActivity.EXTRA_OPEN_CHILD_CODE, childCode);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (childRef != null && connectedListener != null) {
            childRef.child("connected").removeEventListener(connectedListener);
        }
        super.onDestroy();
    }
}
