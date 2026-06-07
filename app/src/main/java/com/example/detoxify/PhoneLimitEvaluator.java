package com.example.detoxify;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for daily-limit and approved-extra-time (wall-clock grant) enforcement.
 */
final class PhoneLimitEvaluator {

    static final int REASON_NONE = 0;
    static final int REASON_DAILY_USAGE = 1;
    static final int REASON_GRANT_EXPIRED = 2;

    static final class Result {
        final boolean shouldLock;
        final int reason;
        final long usedMinutes;
        final long effectiveLimitMinutes;
        final long nextPollMs;
        final boolean grantActive;
        final long grantExpiresAtMs;

        private Result(boolean shouldLock, int reason, long usedMinutes, long effectiveLimitMinutes,
                       long nextPollMs, boolean grantActive, long grantExpiresAtMs) {
            this.shouldLock = shouldLock;
            this.reason = reason;
            this.usedMinutes = usedMinutes;
            this.effectiveLimitMinutes = effectiveLimitMinutes;
            this.nextPollMs = nextPollMs;
            this.grantActive = grantActive;
            this.grantExpiresAtMs = grantExpiresAtMs;
        }

        static Result noLock(long used, long limit, long nextPollMs, boolean grantActive,
                             long grantExpiresAtMs) {
            return new Result(false, REASON_NONE, used, limit, nextPollMs, grantActive, grantExpiresAtMs);
        }

        static Result lock(int reason, long used, long limit, long nextPollMs, boolean grantActive,
                           long grantExpiresAtMs) {
            return new Result(true, reason, used, limit, nextPollMs, grantActive, grantExpiresAtMs);
        }
    }

    private PhoneLimitEvaluator() {
    }

    static Result evaluate(Context context, SharedPreferences prefs) {
        String childCode = prefs.getString("connectedChildCode", "");
        if (childCode.isEmpty()) {
            return Result.noLock(0, 0, 30_000L, false, 0L);
        }
        if (prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)) {
            return Result.noLock(0, 0, 30_000L, false, 0L);
        }
        if (!UsageStatsHelper.hasUsageAccess(context)) {
            return Result.noLock(0, 0, 30_000L, false, 0L);
        }

        long now = System.currentTimeMillis();

        UsageStatsHelper.TodayBreakdown breakdown = UsageStatsHelper.computeToday(context);
        long used = breakdown.totalMinutes;
        // Use raw milliseconds for the lock comparison so we don't get a ~30s
        // rounding gap where the countdown hits zero but the lock never fires.
        long usedMs = breakdown.totalMs;
        long effectiveLimitMs = effectiveLimitMinutes(prefs, now) * 60_000L;

        long grantExpires = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_EXPIRES_MS, 0L);
        boolean grantConfigured = grantExpires > 0L;
        boolean grantActive = grantConfigured && now < grantExpires;

        if (grantConfigured && !grantActive) {
            long baseline = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_BASELINE,
                    prefs.getLong("phone_daily_limit", prefs.getLong("daily_limit", 120)));
            return Result.lock(REASON_GRANT_EXPIRED, used, baseline, 5_000L, false, grantExpires);
        }

        long effectiveLimit = effectiveLimitMinutes(prefs, now);
        if (effectiveLimit <= 0) {
            return Result.noLock(used, effectiveLimit, 30_000L, grantActive, grantExpires);
        }

        // Compare in milliseconds — avoids the ~30s rounding gap where the
        // countdown shows 0:00 but used (in whole minutes) is still < effectiveLimit.
        if (usedMs >= effectiveLimitMs) {
            return Result.lock(REASON_DAILY_USAGE, used, effectiveLimit, 8_000L, grantActive, grantExpires);
        }

        long nextPoll = 30_000L;
        long remainingMs = effectiveLimitMs - usedMs;
        // Poll fast when under 5 min or under 10% remaining
        if (remainingMs <= 5 * 60_000L || usedMs * 10 >= effectiveLimitMs * 9) {
            nextPoll = 5_000L;
        }
        // Poll even faster in the last minute
        if (remainingMs <= 60_000L) {
            nextPoll = 1_000L;
        }
        if (grantActive) {
            long untilGrantEnd = grantExpires - now;
            if (untilGrantEnd > 0 && untilGrantEnd < 60_000L) {
                nextPoll = Math.min(nextPoll, Math.max(1_000L, untilGrantEnd + 250L));
            }
        }
        return Result.noLock(used, effectiveLimit, nextPoll, grantActive, grantExpires);
    }

    static long effectiveLimitMinutes(SharedPreferences prefs, long nowMs) {
        long dailyLimit = prefs.getLong("phone_daily_limit", prefs.getLong("daily_limit", 120));
        long grantExpires = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_EXPIRES_MS, 0L);
        long grantUsageCap = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_USAGE_CAP, 0L);
        long grantBaseline = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_BASELINE, 0L);

        if (grantExpires > 0L && nowMs < grantExpires && grantUsageCap > 0L) {
            return grantUsageCap;
        }
        if (grantBaseline > 0L) {
            return grantBaseline;
        }
        return dailyLimit;
    }

    static boolean isGrantActive(SharedPreferences prefs, long nowMs) {
        long grantExpires = prefs.getLong(BlockMonitorService.PREFS_TIME_GRANT_EXPIRES_MS, 0L);
        return grantExpires > 0L && nowMs < grantExpires;
    }
}