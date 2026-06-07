package com.example.detoxify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;

/**
 * Starts or stops the Firebase listener that alerts parents about screen-time extension requests.
 */
public final class ParentWatchController {

    private ParentWatchController() {
    }

    public static void startWatching(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("Detoxify", Context.MODE_PRIVATE);
        if (!p.getBoolean("isLoggedIn", false)) {
            return;
        }
        if (!"parent".equals(p.getString("userRole", ""))) {
            return;
        }
        if (p.getString("userId", "").isEmpty()) {
            return;
        }
        Intent i = new Intent(app, ParentTimeRequestService.class);
        ContextCompat.startForegroundService(app, i);
    }

    public static void stopWatching(Context context) {
        context.getApplicationContext().stopService(new Intent(context, ParentTimeRequestService.class));
    }
}
