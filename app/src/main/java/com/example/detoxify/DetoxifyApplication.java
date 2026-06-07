package com.example.detoxify;

import android.app.Application;
import android.content.SharedPreferences;

/**
 * Starts background limit enforcement whenever a child device session is active,
 * so the phone still locks when time is up even if the child is not on the dashboard.
 */
public class DetoxifyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences(DetoxifyPrefs.PREFS_NAME, MODE_PRIVATE);
        if (AuthManager.getInstance(this).isChildConnected()
                && prefs.getBoolean(DetoxifyPrefs.KEY_CHILD_PERMISSIONS_DONE, false)) {
            BlockMonitorService.startMonitoring(this);
        }
    }
}
