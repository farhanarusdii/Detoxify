package com.example.detoxify;

/** UI formatting: durations stored as whole minutes are shown as {@code Hh Mm}. */
final class DurationFormat {

    private DurationFormat() {
    }

    static String hoursMinutes(long totalMinutes) {
        if (totalMinutes < 0) {
            totalMinutes = 0;
        }
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;
        return h + "h " + m + "m";
    }

    /**
     * Formats a duration from raw milliseconds with second-level accuracy.
     * When >= 5 minutes: shows "Xh Ym" (same as hoursMinutes).
     * When < 5 minutes: shows "X:SS" so the countdown never appears stuck.
     * When < 1 minute:  shows "0:SS" (e.g. "0:42").
     */
    static String fromMs(long totalMs) {
        if (totalMs <= 0) {
            return "0:00";
        }
        long totalSeconds = totalMs / 1_000L;
        if (totalMs >= 5 * 60 * 1_000L) {
            // 5+ minutes: show whole minutes (no rounding so it never jumps up)
            long mins = totalSeconds / 60;
            return hoursMinutes(mins);
        }
        // Under 5 minutes: show M:SS
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return m + ":" + String.format(java.util.Locale.US, "%02d", s);
    }
}