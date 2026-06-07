package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Child device: daily mood check-in stored under {@code children/{code}/moodCheckIns}. */
public final class MoodCheckInHelper {

    private static boolean promptShownThisSession;
    private static String promptSessionDay = "";

    private MoodCheckInHelper() {
    }

    /**
     * Shows a daily reminder popup once per app session until the child checks in.
     */
    public static void promptDailyCheckInIfNeeded(AppCompatActivity activity) {
        if (checkedInToday(activity)) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        if (prefs.getString("connectedChildCode", "").isEmpty()) {
            return;
        }
        String todayKey = DayKeysHelper.todayKey();
        if (!todayKey.equals(promptSessionDay)) {
            promptSessionDay = todayKey;
            promptShownThisSession = false;
        }
        if (promptShownThisSession) {
            return;
        }
        promptShownThisSession = true;
        showCheckInDialog(activity, true);
    }

    public static void showCheckInDialog(AppCompatActivity activity) {
        showCheckInDialog(activity, false);
    }

    private static void showCheckInDialog(AppCompatActivity activity, boolean isReminder) {
        SharedPreferences prefs = activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        String childCode = prefs.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            Toast.makeText(activity, R.string.mood_need_connection, Toast.LENGTH_LONG).show();
            return;
        }

        String todayKey = DayKeysHelper.todayKey();
        String lastDay = prefs.getString("last_mood_checkin_day", "");
        if (todayKey.equals(lastDay)) {
            Toast.makeText(activity, R.string.mood_already_today, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = activity.getResources().getStringArray(R.array.mood_labels);
        final int[] values = activity.getResources().getIntArray(R.array.mood_values);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.mood_dialog_title);
        if (isReminder) {
            builder.setMessage(R.string.mood_prompt_message);
        }
        builder.setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= values.length) {
                        return;
                    }
                    submit(activity, childCode, values[which], labels[which], todayKey);
                });
        if (isReminder) {
            builder.setNegativeButton(R.string.mood_prompt_later, null);
        } else {
            builder.setNegativeButton(android.R.string.cancel, null);
        }
        builder.show();
    }

    private static void submit(AppCompatActivity activity, String childCode, int mood, String label,
                               String dayKey) {
        String childName = activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE)
                .getString("childName", "");
        if (childName == null || childName.isEmpty()) {
            childName = activity.getString(R.string.notif_child_default_name);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("mood", mood);
        payload.put("label", label);
        payload.put("dayKey", dayKey);
        payload.put("timestamp", ServerValue.TIMESTAMP);
        payload.put("childName", childName);

        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("children").child(childCode).child("moodCheckIns").push()
                .setValue(payload)
                .addOnSuccessListener(aVoid -> activity.runOnUiThread(() -> {
                    activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE)
                            .edit()
                            .putString("last_mood_checkin_day", dayKey)
                            .apply();
                    Toast.makeText(activity,
                            activity.getString(R.string.mood_saved_toast, label),
                            Toast.LENGTH_LONG).show();
                    if (activity instanceof ChildDashboardActivity) {
                        ((ChildDashboardActivity) activity).updateMoodCardStatus();
                    }
                    InsightsSyncHelper.refreshAll(activity, root, childCode);
                }))
                .addOnFailureListener(e -> activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                                activity.getString(R.string.mood_save_failed,
                                        e.getMessage() != null ? e.getMessage() : ""),
                                Toast.LENGTH_LONG).show()));
    }

    /** For UI: whether child already checked in today. */
    public static boolean checkedInToday(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        return DayKeysHelper.todayKey().equals(prefs.getString("last_mood_checkin_day", ""));
    }
}
