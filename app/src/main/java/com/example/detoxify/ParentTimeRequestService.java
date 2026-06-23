package com.example.detoxify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a lightweight Realtime Database watch on all children linked to the logged-in parent
 * and raises a notification when a child submits a pending {@code timeRequest}.
 */
public class ParentTimeRequestService extends Service {

    private static final String TAG = "ParentTimeRequestSvc";

    static final String CHANNEL_WATCH  = "detoxify_parent_watch";
    static final String CHANNEL_ALERTS = "detoxify_time_request_alerts";
    /** Separate channel for excessive-usage alerts so parents can control them independently. */
    static final String CHANNEL_USAGE_ALERTS = "detoxify_parent_usage_alerts";

    private static final int NOTIF_ID_FOREGROUND  = 7101;
    private static final int NOTIF_ID_ALERT_BASE  = 7200;
    private static final int NOTIF_ID_USAGE_BASE  = 7300;

    private final FirebaseDatabase db = FirebaseDatabase.getInstance();
    private DatabaseReference root;

    @Nullable
    private ValueEventListener childrenQueryListener;
    @Nullable
    private Query childrenQuery;

    private final Map<String, ValueEventListener> timeRequestListeners = new HashMap<>();
    /** Firebase listeners watching {@code children/{code}/usageAlerts} per child. */
    private final Map<String, ValueEventListener> usageAlertListeners  = new HashMap<>();
    /**
     * Child name cache populated from the {@code children} query snapshot.
     * Keyed by child code so usage-alert and time-request notifications always show
     * the correct child name without an extra Firebase read.
     */
    private final Map<String, String> childNames = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        root = db.getReference();
        createChannels();
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        detachAllFirebaseListeners();
        attachChildrenQuery();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        detachAllFirebaseListeners();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel watch = new NotificationChannel(
                CHANNEL_WATCH,
                getString(R.string.notif_channel_watch_name),
                NotificationManager.IMPORTANCE_LOW);
        watch.setDescription(getString(R.string.notif_channel_watch_desc));
        nm.createNotificationChannel(watch);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(getString(R.string.notif_channel_alerts_desc));
        nm.createNotificationChannel(alerts);

        NotificationChannel usageAlerts = new NotificationChannel(
                CHANNEL_USAGE_ALERTS,
                getString(R.string.notif_channel_parent_usage_name),
                NotificationManager.IMPORTANCE_HIGH);
        usageAlerts.setDescription(getString(R.string.notif_channel_parent_usage_desc));
        usageAlerts.enableVibration(true);
        nm.createNotificationChannel(usageAlerts);
    }

    private Notification buildForegroundNotification() {
        PendingIntent pi = pendingActivity(new Intent(this, ParentDashboardActivity.class), 0);
        return new NotificationCompat.Builder(this, CHANNEL_WATCH)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(getString(R.string.notif_watch_title))
                .setContentText(getString(R.string.notif_watch_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void attachChildrenQuery() {
        SharedPreferences p = getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        String parentId = p.getString("userId", "");
        if (parentId.isEmpty()) {
            Log.w(TAG, "No parentId; stopping");
            stopSelf();
            return;
        }

        childrenQuery = root.child("children").orderByChild("parentId").equalTo(parentId);
        childrenQueryListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                syncPerChildListeners(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "children query: " + error.getMessage());
            }
        };
        childrenQuery.addValueEventListener(childrenQueryListener);
    }

    private void syncPerChildListeners(DataSnapshot childrenSnapshot) {
        Set<String> wanted = new HashSet<>();
        for (DataSnapshot c : childrenSnapshot.getChildren()) {
            String code = c.getKey();
            if (code != null) {
                wanted.add(code);
                // Cache the child's display name so notifications always use the right name.
                String name = c.child("childName").getValue(String.class);
                if (name != null && !name.isEmpty()) {
                    childNames.put(code, name);
                }
            }
        }

        Iterator<Map.Entry<String, ValueEventListener>> it = timeRequestListeners.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ValueEventListener> e = it.next();
            if (!wanted.contains(e.getKey())) {
                root.child("children").child(e.getKey()).child("timeRequest").removeEventListener(e.getValue());
                it.remove();
            }
        }

        for (String code : wanted) {
            if (timeRequestListeners.containsKey(code)) {
                continue;
            }
            DatabaseReference ref = root.child("children").child(code).child("timeRequest");
            ValueEventListener listener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    handleTimeRequestSnapshot(code, snapshot);
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Log.w(TAG, "timeRequest " + code + ": " + error.getMessage());
                }
            };
            ref.addValueEventListener(listener);
            timeRequestListeners.put(code, listener);

            // ── Usage-alert listener ───────────────────────────────────────────
            // Watches children/{code}/usageAlerts and notifies the parent when a
            // 75 % or 90 % threshold alert arrives from the child's device.
            if (!usageAlertListeners.containsKey(code)) {
                DatabaseReference alertRef = root.child("children").child(code).child("usageAlerts");
                ValueEventListener alertListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        handleUsageAlertsSnapshot(code, snapshot);
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.w(TAG, "usageAlerts " + code + ": " + error.getMessage());
                    }
                };
                alertRef.addValueEventListener(alertListener);
                usageAlertListeners.put(code, alertListener);
            }
        }
    }

    private void handleTimeRequestSnapshot(String childCode, DataSnapshot snap) {
        if (snap == null || !snap.exists()) {
            return;
        }
        String status = snap.child("status").getValue(String.class);
        if (!"pending".equals(status)) {
            return;
        }
        String requestId = snap.child("requestId").getValue(String.class);
        if (requestId == null || requestId.isEmpty()) {
            return;
        }

        SharedPreferences p = getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        String seenKey = "seen_time_request_id_" + childCode;
        if (requestId.equals(p.getString(seenKey, ""))) {
            return;
        }

        Long minsObj = snap.child("requestedMinutes").getValue(Long.class);
        long mins = minsObj != null ? minsObj : 0L;
        // Prefer the cache; fall back to whatever the child wrote into the request node.
        String childName = childNames.containsKey(childCode)
                ? childNames.get(childCode)
                : snap.child("childName").getValue(String.class);
        if (childName == null || childName.isEmpty()) {
            childName = getString(R.string.notif_child_default_name);
        }

        showTimeRequestNotification(childCode, childName, mins, requestId);

        p.edit().putString(seenKey, requestId).apply();
    }

    private void showTimeRequestNotification(String childCode, String childName, long requestedMinutes,
                                             String requestId) {
        Intent open = new Intent(this, ApproveTimeRequestActivity.class);
        open.putExtra(ApproveTimeRequestActivity.EXTRA_CHILD_CODE, childCode);
        open.putExtra(ApproveTimeRequestActivity.EXTRA_REQUEST_ID, requestId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = pendingActivity(open, childCode.hashCode() & 0xffff);

        Intent approve = new Intent(this, ApproveTimeRequestActivity.class);
        approve.putExtra(ApproveTimeRequestActivity.EXTRA_CHILD_CODE, childCode);
        approve.putExtra(ApproveTimeRequestActivity.EXTRA_REQUEST_ID, requestId);
        approve.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int approveReq = (childCode.hashCode() ^ requestId.hashCode()) & 0x3fff_ffff;
        PendingIntent piApprove = pendingActivity(approve, approveReq);

        String body = getString(R.string.notif_time_request_body,
                childName, DurationFormat.hoursMinutes(requestedMinutes));

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(getString(R.string.notif_time_request_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .addAction(0, getString(R.string.notif_action_approve), piApprove)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int id = NOTIF_ID_ALERT_BASE + (Math.abs(childCode.hashCode()) % 50);
        nm.notify(id, n);
    }

    /**
     * Called whenever the {@code children/{code}/usageAlerts} node changes.
     * Iterates unseen alerts and shows a notification to the parent for each one.
     * Marks each alert as seen by writing {@code seen=true} back to Firebase so
     * reconnects don't re-fire the same alert.
     */
    private void handleUsageAlertsSnapshot(String childCode, DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return;

        // Look up name from the cache populated by syncPerChildListeners.
        // Falls back to "Your child" only if the parent's children snapshot hasn't arrived yet.
        String childName = childNames.containsKey(childCode)
                ? childNames.get(childCode)
                : getString(R.string.notif_child_default_name);

        for (DataSnapshot alert : snapshot.getChildren()) {
            Boolean seen = alert.child("seen").getValue(Boolean.class);
            if (Boolean.TRUE.equals(seen)) continue;

            Long pctObj   = alert.child("percent").getValue(Long.class);
            Long usedObj  = alert.child("usedMinutes").getValue(Long.class);
            Long limitObj = alert.child("limitMinutes").getValue(Long.class);
            if (pctObj == null || usedObj == null || limitObj == null) continue;

            int  pct   = pctObj.intValue();
            long used  = usedObj;
            long limit = limitObj;

            showUsageAlertNotification(childCode, childName, pct, used, limit);

            // Mark seen so re-attach doesn't re-notify.
            alert.getRef().child("seen").setValue(true);
        }
    }

    private void showUsageAlertNotification(String childCode, String childName,
                                            int pct, long usedMinutes, long limitMinutes) {
        String title = getString(R.string.notif_parent_usage_title, childName);
        String body  = getString(
                pct >= 90
                        ? R.string.notif_parent_usage_body_90
                        : R.string.notif_parent_usage_body_75,
                childName, usedMinutes, limitMinutes);

        Intent open = new Intent(this, ParentDashboardActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = pendingActivity(open, (childCode.hashCode() ^ pct) & 0xffff);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_USAGE_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int id = NOTIF_ID_USAGE_BASE + (Math.abs(childCode.hashCode()) % 50) + (pct >= 90 ? 1 : 0);
        nm.notify(id, n);
    }

    private PendingIntent pendingActivity(Intent intent, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, requestCode, intent, flags);
    }

    private void detachAllFirebaseListeners() {
        if (childrenQuery != null && childrenQueryListener != null) {
            childrenQuery.removeEventListener(childrenQueryListener);
        }
        childrenQuery = null;
        childrenQueryListener = null;

        for (Map.Entry<String, ValueEventListener> e : timeRequestListeners.entrySet()) {
            root.child("children").child(e.getKey()).child("timeRequest").removeEventListener(e.getValue());
        }
        timeRequestListeners.clear();

        for (Map.Entry<String, ValueEventListener> e : usageAlertListeners.entrySet()) {
            root.child("children").child(e.getKey()).child("usageAlerts").removeEventListener(e.getValue());
        }
        usageAlertListeners.clear();
        childNames.clear();
    }
}