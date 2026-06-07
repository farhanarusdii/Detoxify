package com.example.detoxify;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlockAppsActivity extends AppCompatActivity {

    private ListView listViewApps;
    private TextView emptyView;
    private String childCode;
    private DatabaseReference mDatabase;

    /** Same order as {@link #listViewApps} rows — package to block for each checked line. */
    private final List<String> packageNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block_apps);

        childCode = getIntent().getStringExtra("childCode");
        String childName = getIntent().getStringExtra("childName");
        if (childName == null) {
            childName = "";
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Block Apps - " + childName);
        }

        if (childCode == null || childCode.isEmpty()) {
            Toast.makeText(this, "No child selected. Pick a child on the dashboard first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mDatabase = FirebaseDatabase.getInstance().getReference();

        listViewApps = findViewById(R.id.list_view_apps);
        emptyView = findViewById(R.id.block_apps_empty);
        listViewApps.setEmptyView(emptyView);
        emptyView.setText(R.string.block_apps_loading);
        listViewApps.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, new ArrayList<>()));
        listViewApps.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        loadBlockableAppsFromFirebase();

        findViewById(R.id.btn_block_selected).setOnClickListener(v -> saveBlockedToFirebase());
    }

    private void loadBlockableAppsFromFirebase() {
        mDatabase.child("children").child(childCode).child("blockableApps")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<InstalledAppsHelper.AppRow> rows = parseBlockableRows(snapshot);
                        packageNames.clear();
                        for (InstalledAppsHelper.AppRow r : rows) {
                            packageNames.add(r.packageName);
                        }
                        if (rows.isEmpty()) {
                            ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(BlockAppsActivity.this,
                                    android.R.layout.simple_list_item_multiple_choice, new ArrayList<>());
                            listViewApps.setAdapter(emptyAdapter);
                            listViewApps.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                            emptyView.setText(R.string.block_apps_empty_child);
                            return;
                        }
                        List<String> lines = InstalledAppsHelper.buildDisplayLines(rows);
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(BlockAppsActivity.this,
                                android.R.layout.simple_list_item_multiple_choice, lines);
                        listViewApps.setAdapter(adapter);
                        listViewApps.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                        applyBlockedSelectionsFromFirebase();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(BlockAppsActivity.this,
                                "Could not load apps: " + error.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        emptyView.setText(R.string.block_apps_empty_child);
                    }
                });
    }

    private static List<InstalledAppsHelper.AppRow> parseBlockableRows(DataSnapshot snapshot) {
        List<InstalledAppsHelper.AppRow> rows = new ArrayList<>();
        if (!snapshot.exists()) {
            return rows;
        }
        DataSnapshot appsSnap = snapshot.child("apps");
        for (DataSnapshot app : appsSnap.getChildren()) {
            String pkg = app.child("packageName").getValue(String.class);
            String label = app.child("label").getValue(String.class);
            if (pkg != null && !pkg.isEmpty()) {
                rows.add(new InstalledAppsHelper.AppRow(pkg,
                        label != null && !label.isEmpty() ? label : pkg));
            }
        }
        return rows;
    }

    private void applyBlockedSelectionsFromFirebase() {
        mDatabase.child("children").child(childCode).child("blockedPackages")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Set<String> blockedPkgs = new HashSet<>();
                        for (DataSnapshot item : snapshot.getChildren()) {
                            String p = item.getValue(String.class);
                            if (p != null) {
                                blockedPkgs.add(p);
                            }
                        }
                        for (int i = 0; i < packageNames.size(); i++) {
                            if (blockedPkgs.contains(packageNames.get(i))) {
                                listViewApps.setItemChecked(i, true);
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(BlockAppsActivity.this,
                                "Could not load blocked apps: " + error.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void saveBlockedToFirebase() {
        if (packageNames.isEmpty()) {
            Toast.makeText(this, R.string.block_apps_nothing_to_save, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> packages = new ArrayList<>();
        for (int i = 0; i < packageNames.size(); i++) {
            if (listViewApps.isItemChecked(i)) {
                packages.add(packageNames.get(i));
            }
        }

        mDatabase.child("children").child(childCode).child("blockedPackages").setValue(packages)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(this,
                                getString(R.string.block_apps_saved, packages.size()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Exception e = task.getException();
                        Toast.makeText(this,
                                "Save failed: " + (e != null ? e.getMessage() : "unknown"),
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
