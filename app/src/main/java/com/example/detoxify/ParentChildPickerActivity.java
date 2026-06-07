package com.example.detoxify;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * After parent login: pick an existing child profile or add a new one before the dashboard.
 */
public class ParentChildPickerActivity extends AppCompatActivity {

    private AuthManager authManager;
    private DatabaseReference root;
    private String parentId;

    private ListView listChildren;
    private TextView tvEmpty;
    private MaterialButton btnAddChild;
    private MaterialButton btnContinue;

    private final List<ChildRow> rows = new ArrayList<>();
    private ArrayAdapter<ChildRow> adapter;

    private Query childrenQuery;
    private ValueEventListener childrenListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_child_picker);

        authManager = AuthManager.getInstance(this);
        SharedPreferences prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        parentId = prefs.getString("userId", "");

        if (!authManager.isLoggedIn() || parentId.isEmpty()) {
            Toast.makeText(this, R.string.parent_picker_not_logged_in, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ModeSelectionActivity.class));
            finish();
            return;
        }

        prefs.edit().putString("userRole", "parent").apply();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.parent_picker_toolbar);
        }

        listChildren = findViewById(R.id.list_children);
        tvEmpty = findViewById(R.id.tv_picker_empty);
        btnAddChild = findViewById(R.id.btn_add_child);
        btnContinue = findViewById(R.id.btn_continue_dashboard);

        adapter = new ArrayAdapter<>(this, R.layout.list_item_text, rows);
        listChildren.setAdapter(adapter);
        listChildren.setEmptyView(tvEmpty);

        listChildren.setOnItemClickListener((parent, view, position, id) -> {
            ChildRow row = adapter.getItem(position);
            if (row != null) {
                openConnectChild(row.childCode, row.childName);
            }
        });

        btnAddChild.setOnClickListener(v -> showAddChildDialog());
        btnContinue.setOnClickListener(v -> openDashboard(null));

        root = FirebaseDatabase.getInstance().getReference();
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachChildrenListener();
    }

    @Override
    protected void onStop() {
        detachChildrenListener();
        super.onStop();
    }

    private void attachChildrenListener() {
        detachChildrenListener();
        childrenQuery = root.child("children").orderByChild("parentId").equalTo(parentId);
        childrenListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<ChildRow> next = new ArrayList<>();
                for (DataSnapshot childSnap : snapshot.getChildren()) {
                    String code = childSnap.getKey();
                    if (code == null || code.isEmpty()) {
                        continue;
                    }
                    String name = childSnap.child("childName").getValue(String.class);
                    if (name == null || name.isEmpty()) {
                        name = getString(R.string.add_child_default_name);
                    }
                    Boolean connected = childSnap.child("connected").getValue(Boolean.class);
                    boolean online = connected != null && connected;
                    next.add(new ChildRow(code, name, online));
                }
                Collections.sort(next, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

                rows.clear();
                rows.addAll(next);
                adapter.notifyDataSetChanged();
                btnContinue.setText(rows.isEmpty()
                        ? R.string.parent_picker_continue_empty
                        : R.string.parent_picker_continue);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(ParentChildPickerActivity.this,
                        getString(R.string.parent_picker_load_error, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        };
        childrenQuery.addValueEventListener(childrenListener);
    }

    private void detachChildrenListener() {
        if (childrenQuery != null && childrenListener != null) {
            childrenQuery.removeEventListener(childrenListener);
        }
        childrenQuery = null;
        childrenListener = null;
    }

    private void showAddChildDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_child_title);
        builder.setMessage(R.string.add_child_message);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.add_child_name_hint);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton(R.string.add_child_button, (dialog, which) -> {
            String entered = input.getText().toString().trim();
            final String childName = entered.isEmpty()
                    ? getString(R.string.add_child_default_name)
                    : entered;
            String code = authManager.generateChildCode();
            if (code == null) {
                Toast.makeText(this, R.string.add_child_not_logged_in, Toast.LENGTH_LONG).show();
                return;
            }
            authManager.saveChildCode(code, childName, new AuthManager.ChildCodeCallback() {
                @Override
                public void onSuccess(String savedCode) {
                    runOnUiThread(() -> showChildCodeDialog(savedCode, childName));
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(ParentChildPickerActivity.this,
                            getString(R.string.add_child_failed, error),
                            Toast.LENGTH_LONG).show());
                }
            });
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showChildCodeDialog(String code, String childName) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_child_code_title)
                .setMessage(getString(R.string.add_child_code_message, childName, code))
                .setPositiveButton(R.string.parent_picker_open_dashboard, (d, w) -> openDashboard(code))
                .setNegativeButton(R.string.parent_picker_stay_here, null)
                .show();
    }

    private void openDashboard(String childCode) {
        Intent intent = new Intent(this, ParentDashboardActivity.class);
        if (childCode != null && !childCode.isEmpty()) {
            intent.putExtra(ParentDashboardActivity.EXTRA_OPEN_CHILD_CODE, childCode);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openConnectChild(String childCode, String childName) {
        if (childCode == null || childCode.isEmpty()) {
            openDashboard(null);
            return;
        }
        Intent intent = new Intent(this, ParentConnectChildActivity.class);
        intent.putExtra(ParentConnectChildActivity.EXTRA_CHILD_CODE, childCode);
        intent.putExtra(ParentConnectChildActivity.EXTRA_CHILD_NAME, childName);
        startActivity(intent);
        finish();
    }

    private static final class ChildRow {
        final String childCode;
        final String childName;
        final String displayName;

        ChildRow(String childCode, String name, boolean connected) {
            this.childCode = childCode;
            this.childName = name;
            String status = connected ? "🟢" : "⚪";
            this.displayName = status + " " + name + "  ·  " + childCode;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
