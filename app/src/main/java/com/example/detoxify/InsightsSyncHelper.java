package com.example.detoxify;

import android.content.Context;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.Map;

/**
 * Recomputes behaviour + mood insights from Firebase child snapshot and writes latest insight nodes.
 */
public final class InsightsSyncHelper {

    private static final String TAG = "InsightsSyncHelper";

    private InsightsSyncHelper() {
    }

    public static void refreshAll(Context context, DatabaseReference root, String childCode) {
        if (context == null || root == null || childCode == null || childCode.isEmpty()) {
            return;
        }
        root.child("children").child(childCode)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            return;
                        }
                        Map<String, Object> behavior = BehaviorInsightEngine.compute(context, snapshot);
                        Map<String, Object> mood = MoodCorrelationAnalyzer.compute(context, snapshot);
                        BehaviorInsightEngine.upload(root, childCode, behavior);
                        MoodCorrelationAnalyzer.upload(root, childCode, mood);
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.w(TAG, "refreshAll: " + error.getMessage());
                    }
                });
    }
}
