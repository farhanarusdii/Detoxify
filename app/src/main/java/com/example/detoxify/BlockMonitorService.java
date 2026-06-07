package com.example.detoxify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps parent rules in sync via Firebase and enforces blocked apps / daily limit using usage stats.
 */
public class BlockMonitorService extends Service {

    public static final String PREFS_BLOCKED_PACKAGES = "blocked_packages_set";
    /** When true, {@link AppBlockAccessibilityService} gates the whole phone (Family Link–style pause). */
    public static final String PREFS_PHONE_LIMIT_EXCEEDED = "phone_limit_exceeded";
    /**
     * After parent unlock from {@link PhoneLockedActivity}, daily limit gating is paused until this
     * epoch time (ms). Lets the child use the phone until the parent raises the limit or this expires.
     */
    public static final String PREFS_PHONE_LIMIT_OVERRIDE_UNTIL_MS = "phone_limit_override_until_ms";
    /** Parent turned on full-device lock from their dashboard (Family Link–style pause). */
    public static final String PREFS_REMOTE_FULL_LOCK = "remote_full_lock";
    /** Last {@code timeRequest} requestId applied so we do not re-handle approved/denied snapshots. */
    public static final String PREFS_LAST_APPLIED_TIME_REQUEST_ID = "last_applied_time_request_id";
    /** Wall-clock end of parent-approved extra time ({@code children/{code}/timeGrant/expiresAtMs}). */
    public static final String PREFS_TIME_GRANT_EXPIRES_MS = "time_grant_expires_ms";
    /** Max usage minutes while a grant is active ({@code timeGrant/usageCapMinutes}). */
    public static final String PREFS_TIME_GRANT_USAGE_CAP = "time_grant_usage_cap";
    /** Daily limit to restore after the grant ends ({@code timeGrant/baselineLimit}). */
    public static final String PREFS_TIME_GRANT_BASELINE = "time_grant_baseline";

    private static final String CHANNEL_ID = "detoxify_policy";
    private static final String CHANNEL_LOCK_ID = "detoxify_lock";
    private static final int NOTIFICATION_ID = 7102;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService computeExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private FirebaseDatabase firebaseDb;

    private DatabaseReference dailyLimitRef;
    private DatabaseReference blockedRef;
    private DatabaseReference deviceLockRef;
    private DatabaseReference timeRequestRef;
    private DatabaseReference timeGrantRef;
    private ValueEventListener dailyLimitListener;
    private ValueEventListener blockedListener;
    private ValueEventListener deviceLockListener;
    private ValueEventListener timeRequestListener;
    private ValueEventListener timeGrantListener;

    private long lastBlockedKickAt;
    private String lastBlockedPackage;
    /** Last {@code deviceLock/active} from Firebase — show lock UI only on false→true. */
    private Boolean lastRemoteLockActive;

    private final Runnable blockedPoll = new Runnable() {
        @Override
        public void run() {
            try {
                tickBlocked();
            } finally {
                long delayMs = 850L;
                Set<String> blocked = prefs.getStringSet(PREFS_BLOCKED_PACKAGES, Collections.emptySet());
                if (blocked == null || blocked.isEmpty()) {
                    delayMs = 5000L;
                }
                mainHandler.postDelayed(this, delayMs);
            }
        }
    };

    private final Runnable limitPoll = new Runnable() {
        @Override
        public void run() {
            computeExecutor.execute(() -> {
                long nextMs = 30_000L;
                try {
                    nextMs = tickDailyLimit();
                } finally {
                    long delay = nextMs;
                    mainHandler.post(() -> mainHandler.postDelayed(limitPoll, delay));
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("Detoxify", MODE_PRIVATE);
        firebaseDb = FirebaseDatabase.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification(false);
        attachFirebasePolicyListeners();

        mainHandler.removeCallbacks(blockedPoll);
        mainHandler.removeCallbacks(limitPoll);
        mainHandler.post(blockedPoll);
        mainHandler.post(limitPoll);
        return START_STICKY;
    }

    private void attachFirebasePolicyListeners() {
        detachFirebasePolicyListeners();
        String childCode = prefs.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            return;
        }

        DatabaseReference base = firebaseDb.getReference().child("children").child(childCode);
        lastRemoteLockActive = null;

        dailyLimitRef = base.child("dailyLimit");
        blockedRef = base.child("blockedPackages");
        timeGrantRef = base.child("timeGrant");

        dailyLimitListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long lim = snapshot.getValue(Long.class);
                if (lim != null && lim > 0) {
                    prefs.edit().putLong("phone_daily_limit", lim).apply();
                    scheduleLimitRecheck();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };

        blockedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                HashSet<String> pkgs = new HashSet<>();
                for (DataSnapshot c : snapshot.getChildren()) {
                    String p = c.getValue(String.class);
                    if (p != null) {
                        pkgs.add(p);
                    }
                }
                prefs.edit().putStringSet(PREFS_BLOCKED_PACKAGES, pkgs).apply();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };

        timeGrantListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                SharedPreferences.Editor ed = prefs.edit();
                if (!snapshot.exists()) {
                    ed.remove(PREFS_TIME_GRANT_EXPIRES_MS)
                            .remove(PREFS_TIME_GRANT_USAGE_CAP)
                            .remove(PREFS_TIME_GRANT_BASELINE);
                } else {
                    Long expires = snapshot.child("expiresAtMs").getValue(Long.class);
                    Long usageCap = snapshot.child("usageCapMinutes").getValue(Long.class);
                    Long baseline = snapshot.child("baselineLimit").getValue(Long.class);
                    if (expires != null && expires > 0L) {
                        ed.putLong(PREFS_TIME_GRANT_EXPIRES_MS, expires);
                    } else {
                        ed.remove(PREFS_TIME_GRANT_EXPIRES_MS);
                    }
                    if (usageCap != null && usageCap > 0L) {
                        ed.putLong(PREFS_TIME_GRANT_USAGE_CAP, usageCap);
                    } else {
                        ed.remove(PREFS_TIME_GRANT_USAGE_CAP);
                    }
                    if (baseline != null && baseline > 0L) {
                        ed.putLong(PREFS_TIME_GRANT_BASELINE, baseline);
                    } else {
                        ed.remove(PREFS_TIME_GRANT_BASELINE);
                    }
                }
                ed.commit();
                scheduleLimitRecheck();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };

        dailyLimitRef.addValueEventListener(dailyLimitListener);
        blockedRef.addValueEventListener(blockedListener);
        timeGrantRef.addValueEventListener(timeGrantListener);

        deviceLockRef = base.child("deviceLock").child("active");
        deviceLockListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean active = snapshot.getValue(Boolean.class);
                boolean on = active != null && active;
                boolean wasOn = Boolean.TRUE.equals(lastRemoteLockActive);
                lastRemoteLockActive = on;

                prefs.edit().putBoolean(PREFS_REMOTE_FULL_LOCK, on).commit();
                if (on) {
                    if (!wasOn) {
                        presentRemoteLockScreen();
                    }
                } else {
                    scheduleLimitRecheck();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        deviceLockRef.addValueEventListener(deviceLockListener);

        timeRequestRef = base.child("timeRequest");
        timeRequestListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return;
                }
                String status = snapshot.child("status").getValue(String.class);
                String rid = snapshot.child("requestId").getValue(String.class);
                if (rid == null || rid.isEmpty()) {
                    return;
                }
                if (!snapshot.hasChild("resolvedAt")) {
                    return;
                }
                String last = prefs.getString(PREFS_LAST_APPLIED_TIME_REQUEST_ID, "");
                if (rid.equals(last)) {
                    return;
                }
                if ("approved".equals(status) || "denied".equals(status)) {
                    prefs.edit().putString(PREFS_LAST_APPLIED_TIME_REQUEST_ID, rid).apply();
                    if ("denied".equals(status)) {
                        mainHandler.post(() ->
                                PhoneLockGate.showDeniedNoticeOnLockScreen(BlockMonitorService.this));
                    }
                    scheduleLimitRecheck();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        timeRequestRef.addValueEventListener(timeRequestListener);

        if (prefs.getBoolean(PREFS_REMOTE_FULL_LOCK, false)) {
            mainHandler.postDelayed(this::presentRemoteLockScreen, 400L);
        }
    }

    private void scheduleLimitRecheck() {
        computeExecutor.execute(() -> {
            try {
                tickDailyLimit();
            } catch (Exception ignored) {
            }
        });
    }

    private void presentRemoteLockScreen() {
        if (!prefs.getBoolean(PREFS_REMOTE_FULL_LOCK, false)) {
            return;
        }
        if (PhoneLockGate.shouldDeferPhoneLockEnforcement()) {
            return;
        }
        PhoneLockGate.requestLockPresentation(this, PhoneLockedActivity.LOCK_REASON_REMOTE);
    }

    private void detachFirebasePolicyListeners() {
        if (dailyLimitRef != null && dailyLimitListener != null) {
            dailyLimitRef.removeEventListener(dailyLimitListener);
        }
        if (blockedRef != null && blockedListener != null) {
            blockedRef.removeEventListener(blockedListener);
        }
        if (deviceLockRef != null && deviceLockListener != null) {
            deviceLockRef.removeEventListener(deviceLockListener);
        }
        if (timeRequestRef != null && timeRequestListener != null) {
            timeRequestRef.removeEventListener(timeRequestListener);
        }
        if (timeGrantRef != null && timeGrantListener != null) {
            timeGrantRef.removeEventListener(timeGrantListener);
        }
        dailyLimitRef = null;
        blockedRef = null;
        deviceLockRef = null;
        timeRequestRef = null;
        timeGrantRef = null;
        dailyLimitListener = null;
        blockedListener = null;
        deviceLockListener = null;
        timeRequestListener = null;
        timeGrantListener = null;
    }

    private void startForegroundWithNotification(boolean lockActive) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Parent rules",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            NotificationChannel lockCh = new NotificationChannel(
                    CHANNEL_LOCK_ID,
                    "Screen time lock",
                    NotificationManager.IMPORTANCE_HIGH);
            lockCh.setDescription("Shows when daily screen time is up");
            nm.createNotificationChannel(lockCh);
        }

        String channel = lockActive ? CHANNEL_LOCK_ID : CHANNEL_ID;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true);

        if (lockActive) {
            Intent lockIntent = new Intent(this, PhoneLockedActivity.class);
            lockIntent.putExtra(PhoneLockedActivity.EXTRA_LOCK_REASON,
                    PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent fullScreen = PendingIntent.getActivity(this, 7103, lockIntent, piFlags);
            builder.setContentTitle(getString(R.string.phone_lock_title))
                    .setContentText(getString(R.string.phone_lock_message))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(fullScreen)
                    .setFullScreenIntent(fullScreen, true);
        } else {
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText("Applying limits from parent")
                    .setPriority(NotificationCompat.PRIORITY_LOW);
        }

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void tickBlocked() {
        if (!UsageStatsHelper.hasUsageAccess(this)) {
            return;
        }
        if (PhoneLockPolicy.isPhoneGated(prefs)) {
            String fg = ForegroundAppHelper.getForegroundPackage(this);
            if (fg != null && !fg.isEmpty() && !fg.equals(getPackageName())
                    && !PhoneLockPolicy.isPackageAllowedWhenPhoneLocked(this, fg)
                    && !PhoneLockPolicy.isInputMethodPackage(fg)) {
                long now = System.currentTimeMillis();
                if (!fg.equals(lastBlockedPackage) || now - lastBlockedKickAt >= 12_000L) {
                    lastBlockedKickAt = now;
                    lastBlockedPackage = fg;
                    goHome();
                    mainHandler.post(() -> PhoneLockGate.requestLockPresentation(this,
                            PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT));
                }
            }
            return;
        }
        Set<String> blocked = prefs.getStringSet(PREFS_BLOCKED_PACKAGES, Collections.emptySet());
        if (blocked == null || blocked.isEmpty()) {
            return;
        }
        String fg = ForegroundAppHelper.getForegroundPackage(this);
        if (fg == null || fg.isEmpty()) {
            return;
        }
        if (fg.equals(getPackageName())) {
            return;
        }
        HashSet<String> copy = new HashSet<>(blocked);
        if (!copy.contains(fg)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (fg.equals(lastBlockedPackage) && now - lastBlockedKickAt < 12_000L) {
            return;
        }
        lastBlockedKickAt = now;
        lastBlockedPackage = fg;

        goHome();
    }

    /**
     * Updates usage vs daily phone limit. Shows {@link PhoneLockedActivity} and sets
     * {@link #PREFS_PHONE_LIMIT_EXCEEDED} when the limit is reached (child device only).
     *
     * @return delay before the next poll (faster while locked so parent remote updates apply quickly)
     */
    private long tickDailyLimit() {
        String childCode = prefs.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            prefs.edit().putBoolean(PREFS_PHONE_LIMIT_EXCEEDED, false).apply();
            mainHandler.post(() -> startForegroundWithNotification(false));
            return 30_000L;
        }
        if (prefs.getBoolean(PREFS_REMOTE_FULL_LOCK, false)) {
            return 30_000L;
        }

        PhoneLimitEvaluator.Result result = PhoneLimitEvaluator.evaluate(this, prefs);
        prefs.edit().putLong("used_today", result.usedMinutes).apply();

        if (result.reason == PhoneLimitEvaluator.REASON_GRANT_EXPIRED) {
            expireTimeGrant(childCode);
            applyLockResult(result);
            return result.nextPollMs;
        }

        if (result.shouldLock) {
            applyLockResult(result);
            return result.nextPollMs;
        }

        prefs.edit().putBoolean(PREFS_PHONE_LIMIT_EXCEEDED, false).apply();
        mainHandler.post(() -> startForegroundWithNotification(false));
        return result.nextPollMs;
    }

    private void applyLockResult(PhoneLimitEvaluator.Result result) {
        prefs.edit().putBoolean(PREFS_PHONE_LIMIT_EXCEEDED, true).commit();
        mainHandler.post(() -> {
            startForegroundWithNotification(true);
            if (!PhoneLockGate.isLockScreenResumed()) {
                PhoneLockGate.requestLockPresentation(BlockMonitorService.this,
                        PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT);
            }
        });
    }

    private void expireTimeGrant(String childCode) {
        long baseline = prefs.getLong(PREFS_TIME_GRANT_BASELINE,
                prefs.getLong("phone_daily_limit", prefs.getLong("daily_limit", 120)));

        prefs.edit()
                .remove(PREFS_TIME_GRANT_EXPIRES_MS)
                .remove(PREFS_TIME_GRANT_USAGE_CAP)
                .putLong(PREFS_TIME_GRANT_BASELINE, baseline)
                .putLong("phone_daily_limit", baseline)
                .apply();

        Map<String, Object> updates = new HashMap<>();
        updates.put("dailyLimit", baseline);
        updates.put("timeGrant", null);
        firebaseDb.getReference().child("children").child(childCode).updateChildren(updates);
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        detachFirebasePolicyListeners();
        mainHandler.removeCallbacks(blockedPoll);
        mainHandler.removeCallbacks(limitPoll);
        computeExecutor.shutdown();
        super.onDestroy();
    }

    static void startMonitoring(Context context) {
        Intent i = new Intent(context, BlockMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    static void stopMonitoring(Context context) {
        context.stopService(new Intent(context, BlockMonitorService.class));
    }
}
