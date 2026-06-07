package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

/** Parent-facing dialog to set a child's daily screen-time limit and sync to Firebase. */
final class DailyLimitDialogHelper {

    interface Listener {
        void onSaved(long newLimitMinutes);

        void onCancelled();
    }

    private DailyLimitDialogHelper() {
    }

    /** Only pause lock enforcement for dialogs shown on the child lock screen. */
    private static boolean shouldPauseLockEnforcement(Context context) {
        return context instanceof PhoneLockedActivity;
    }

    static void show(AppCompatActivity activity, String childCode,
                     long currentLimitMinutes, long minLimitMinutes, int titleRes,
                     CharSequence message, boolean clearDeviceLock, Listener listener) {
        if (childCode == null || childCode.isEmpty()) {
            Toast.makeText(activity, R.string.time_request_need_connection, Toast.LENGTH_SHORT).show();
            if (listener != null) {
                listener.onCancelled();
            }
            return;
        }

        LinearLayout layout = buildTimeInputs(activity, currentLimitMinutes);
        EditText etHours = (EditText) layout.getChildAt(0);
        EditText etMinutes = (EditText) layout.getChildAt(1);

        boolean pauseLockEnforcement = shouldPauseLockEnforcement(activity);
        if (pauseLockEnforcement) {
            PhoneLockGate.beginChildInteraction();
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setMessage(message)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                })
                .setPositiveButton(R.string.set_time_limit_button, null)
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(d -> {
            if (pauseLockEnforcement) {
                PhoneLockGate.endChildInteraction();
            }
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long newLimit = parseLimitMinutes(activity, etHours, etMinutes);
            if (newLimit <= 0) {
                return;
            }
            if (newLimit < minLimitMinutes) {
                Toast.makeText(activity,
                        activity.getString(R.string.phone_lock_set_limit_too_low,
                                DurationFormat.hoursMinutes(minLimitMinutes)),
                        Toast.LENGTH_LONG).show();
                return;
            }
            saveLimit(activity, childCode, newLimit, clearDeviceLock, () -> {
                dialog.dismiss();
                if (listener != null) {
                    listener.onSaved(newLimit);
                }
            });
        }));
        dialog.show();
    }

    private static LinearLayout buildTimeInputs(Context context, long currentLimitMinutes) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText etHours = new EditText(context);
        etHours.setHint(R.string.time_request_hint_hours);
        etHours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams hourLp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etHours.setLayoutParams(hourLp);

        EditText etMinutes = new EditText(context);
        etMinutes.setHint(R.string.time_request_hint_minutes);
        etMinutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams minLp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        minLp.setMarginStart(pad);
        etMinutes.setLayoutParams(minLp);

        long hours = currentLimitMinutes / 60;
        long mins = currentLimitMinutes % 60;
        etHours.setText(String.valueOf(hours));
        etMinutes.setText(String.valueOf(mins));

        layout.addView(etHours);
        layout.addView(etMinutes);
        return layout;
    }

    private static long parseLimitMinutes(Context context, EditText etHours, EditText etMinutes) {
        try {
            String sh = etHours.getText().toString().trim();
            String sm = etMinutes.getText().toString().trim();
            long h = sh.isEmpty() ? 0L : Long.parseLong(sh);
            long m = sm.isEmpty() ? 0L : Long.parseLong(sm);
            if (h < 0 || m < 0 || m >= 60) {
                throw new IllegalArgumentException();
            }
            return h * 60L + m;
        } catch (IllegalArgumentException e) {
            Toast.makeText(context, R.string.time_request_invalid_numbers, Toast.LENGTH_SHORT).show();
            return 0L;
        }
    }

    private static void saveLimit(Context context, String childCode, long newLimit,
                                  boolean clearDeviceLock, Runnable onSuccess) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("baselineDailyLimit", newLimit);
        updates.put("dailyLimit", newLimit);
        updates.put("timeGrant", null);
        if (clearDeviceLock) {
            updates.put("deviceLock/active", false);
        }

        FirebaseDatabase.getInstance().getReference()
                .child("children").child(childCode)
                .updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    applyLocalLimit(context, childCode, newLimit, clearDeviceLock);
                    onSuccess.run();
                })
                .addOnFailureListener(e -> Toast.makeText(context,
                        context.getString(R.string.set_time_limit_failed,
                                e.getMessage() != null ? e.getMessage() : ""),
                        Toast.LENGTH_LONG).show());
    }

    static void applyLocalLimit(Context context, String childCode, long newLimit,
                                boolean clearDeviceLock) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit()
                .putLong("phone_daily_limit", newLimit)
                .putBoolean(BlockMonitorService.PREFS_PHONE_LIMIT_EXCEEDED, false)
                .remove(BlockMonitorService.PREFS_PHONE_LIMIT_OVERRIDE_UNTIL_MS)
                .remove(BlockMonitorService.PREFS_TIME_GRANT_EXPIRES_MS)
                .remove(BlockMonitorService.PREFS_TIME_GRANT_USAGE_CAP)
                .remove(BlockMonitorService.PREFS_TIME_GRANT_BASELINE);
        if (clearDeviceLock) {
            ed.putBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false);
        }
        ed.commit();
    }
}
