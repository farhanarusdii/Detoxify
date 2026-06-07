package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Child device: send a pending time request to the parent via Firebase (shown as a notification on the parent phone).
 */
public final class TimeRequestHelper {

    private TimeRequestHelper() {
    }

    public static void showRequestMoreTimeDialog(AppCompatActivity activity, boolean showSuccessDialog) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.time_request_dialog_title);

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final EditText etHours = new EditText(activity);
        etHours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHours.setHint(R.string.time_request_hint_hours);

        final EditText etMinutes = new EditText(activity);
        etMinutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMinutes.setHint(R.string.time_request_hint_minutes);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int gap = (int) (8 * density);
        lp.setMargins(0, 0, gap, 0);
        row.addView(etHours, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(etMinutes, lp2);
        wrap.addView(row);

        builder.setView(wrap);

        builder.setPositiveButton(R.string.time_request_send, (dialog, which) -> {
            try {
                String sh = etHours.getText().toString().trim();
                String sm = etMinutes.getText().toString().trim();
                long h = sh.isEmpty() ? 0L : Long.parseLong(sh);
                long mi = sm.isEmpty() ? 0L : Long.parseLong(sm);
                if (mi >= 60 || h < 0 || mi < 0) {
                    Toast.makeText(activity, R.string.time_request_invalid_minutes, Toast.LENGTH_SHORT).show();
                    return;
                }
                long totalMin = h * 60L + mi;
                if (totalMin <= 0) {
                    Toast.makeText(activity, R.string.time_request_need_amount, Toast.LENGTH_SHORT).show();
                    return;
                }
                submitTimeRequest(activity, totalMin, showSuccessDialog);
            } catch (NumberFormatException e) {
                Toast.makeText(activity, R.string.time_request_invalid_numbers, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(android.R.string.cancel, null);

        boolean pauseEnforcement = PhoneLockGate.isLockScreenResumed()
                || PhoneLockPolicy.isPhoneGated(activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE));
        if (pauseEnforcement) {
            PhoneLockGate.beginChildInteraction();
        }
        AlertDialog dialog = builder.create();
        if (pauseEnforcement) {
            dialog.setOnDismissListener(d -> PhoneLockGate.endChildInteraction());
        }
        dialog.show();
    }

    public static void submitTimeRequest(AppCompatActivity activity, long totalMinutes, boolean showSuccessDialog) {
        SharedPreferences prefs = activity.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        String childCode = prefs.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            Toast.makeText(activity, R.string.time_request_need_connection, Toast.LENGTH_LONG).show();
            return;
        }
        String requestId = UUID.randomUUID().toString();
        String childName = prefs.getString("childName", "");
        if (childName == null || childName.isEmpty()) {
            childName = activity.getString(R.string.notif_child_default_name);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", requestId);
        payload.put("requestedMinutes", totalMinutes);
        payload.put("status", "pending");
        payload.put("createdAt", ServerValue.TIMESTAMP);
        payload.put("childName", childName);

        FirebaseDatabase.getInstance().getReference()
                .child("children").child(childCode).child("timeRequest")
                .setValue(payload)
                .addOnSuccessListener(aVoid -> activity.runOnUiThread(() -> {
                    Toast.makeText(activity,
                            activity.getString(R.string.time_request_sent_toast,
                                    DurationFormat.hoursMinutes(totalMinutes)),
                            Toast.LENGTH_LONG).show();
                    if (showSuccessDialog) {
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.time_request_sent_title)
                                .setMessage(R.string.time_request_sent_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                }))
                .addOnFailureListener(e -> activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                                activity.getString(R.string.time_request_failed,
                                        e.getMessage() != null ? e.getMessage() : ""),
                                Toast.LENGTH_LONG).show()));
    }
}
