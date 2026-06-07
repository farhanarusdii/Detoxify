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
}
