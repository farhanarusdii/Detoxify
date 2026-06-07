package com.example.detoxify;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Calendar day keys {@code yyyy-MM-dd} for rolling windows. */
public final class DayKeysHelper {

    private DayKeysHelper() {
    }

    public static List<String> lastNDayKeys(int n) {
        if (n <= 0) {
            return Collections.emptyList();
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(fmt.format(new Date(cal.getTimeInMillis())));
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        Collections.reverse(keys);
        return keys;
    }

    public static String todayKey() {
        List<String> keys = lastNDayKeys(1);
        return keys.isEmpty() ? "" : keys.get(0);
    }
}
