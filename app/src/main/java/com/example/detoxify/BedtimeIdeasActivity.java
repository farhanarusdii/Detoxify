package com.example.detoxify;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * Screen-time alternatives for families. {@link #EXTRA_AUDIENCE} selects parent vs. child wording.
 *
 * When launched from {@link PhoneLockedActivity} with {@link #EXTRA_FROM_LOCK_SCREEN} = true the
 * normal {@link PhoneLockRedirect#redirectIfGated} guard is bypassed so the activity stays open
 * while the phone is locked.  The back-button is also overridden to return to the lock screen
 * rather than performing a normal OS back (which could surface the home screen or another app).
 */
public class BedtimeIdeasActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIENCE        = "audience";
    public static final String AUDIENCE_PARENT       = "parent";
    public static final String AUDIENCE_CHILD        = "child";

    /**
     * Boolean extra set by {@link PhoneLockedActivity} when it opens this screen.
     * When true the activity is allowed to stay visible while the phone is gated, and
     * the back / navigate-up action returns the child to the lock screen rather than
     * doing a normal OS back.
     */
    public static final String EXTRA_FROM_LOCK_SCREEN = "from_lock_screen";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String  audience       = getIntent().getStringExtra(EXTRA_AUDIENCE);
        boolean fromLockScreen = getIntent().getBooleanExtra(EXTRA_FROM_LOCK_SCREEN, false);

        // Guard: if the phone is gated and this was NOT deliberately opened from the lock
        // screen, redirect back to the lock screen immediately (original behaviour).
        if (!fromLockScreen && AUDIENCE_CHILD.equals(audience)
                && PhoneLockRedirect.redirectIfGated(this)) {
            return;
        }

        setContentView(R.layout.activity_bedtime_ideas);
        if (audience == null) {
            audience = AUDIENCE_PARENT;
        }
        boolean forChild = AUDIENCE_CHILD.equals(audience);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(forChild
                    ? R.string.bedtime_ideas_title_child
                    : R.string.bedtime_ideas_title);
        }

        TextView intro = findViewById(R.id.tv_bedtime_intro);
        TextView s1 = findViewById(R.id.tv_bedtime_section_1);
        TextView s2 = findViewById(R.id.tv_bedtime_section_2);
        TextView s3 = findViewById(R.id.tv_bedtime_section_3);
        TextView s4 = findViewById(R.id.tv_bedtime_section_4);

        if (forChild) {
            intro.setText(R.string.bedtime_ideas_child_intro);
            s1.setText(R.string.bedtime_ideas_child_calm);
            s2.setText(R.string.bedtime_ideas_child_creative);
            s3.setText(R.string.bedtime_ideas_child_connection);
            s4.setText(R.string.bedtime_ideas_child_tips);
        } else {
            intro.setText(R.string.bedtime_ideas_intro);
            s1.setText(R.string.bedtime_ideas_calm);
            s2.setText(R.string.bedtime_ideas_creative);
            s3.setText(R.string.bedtime_ideas_connection);
            s4.setText(R.string.bedtime_ideas_tips);
        }
    }

    /**
     * Back / up navigation.
     *
     * When opened from the lock screen we send the child back to the lock screen explicitly
     * rather than letting the OS decide — a normal back() could surface the launcher or
     * another app task that happens to be beneath us in the back-stack.
     */
    @Override
    public boolean onSupportNavigateUp() {
        navigateBackSafely();
        return true;
    }

    @Override
    public void onBackPressed() {
        navigateBackSafely();
    }

    private void navigateBackSafely() {
        boolean fromLockScreen = getIntent().getBooleanExtra(EXTRA_FROM_LOCK_SCREEN, false);
        if (fromLockScreen) {
            // Return explicitly to the lock screen so the child cannot end up on the
            // home screen or in another app by pressing back.
            android.content.SharedPreferences prefs =
                    getSharedPreferences("Detoxify", MODE_PRIVATE);
            int reason = prefs.getBoolean(BlockMonitorService.PREFS_REMOTE_FULL_LOCK, false)
                    ? PhoneLockedActivity.LOCK_REASON_REMOTE
                    : PhoneLockedActivity.LOCK_REASON_DAILY_LIMIT;
            PhoneLockGate.requestLockPresentation(this, reason);
        }
        finish();
    }

    /**
     * Release the interaction-pause depth that PhoneLockedActivity incremented before
     * opening this screen (via PhoneLockGate.beginChildInteraction()).
     *
     * onDestroy() fires regardless of whether the child pressed back, the system killed
     * the activity, or a configuration change recreated it — so this is the safest place
     * to balance the counter.
     *
     * Without this, interactionPauseDepth stays > 0 after the child returns to the lock
     * screen, permanently disabling accessibility enforcement and allowing the child to
     * reach the home screen or any other app.
     */
    @Override
    protected void onDestroy() {
        if (getIntent().getBooleanExtra(EXTRA_FROM_LOCK_SCREEN, false)) {
            PhoneLockGate.endChildInteraction();
        }
        super.onDestroy();
    }
}