package com.example.detoxify;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * Screen-time alternatives for families. {@link #EXTRA_AUDIENCE} selects parent vs. child wording.
 */
public class BedtimeIdeasActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIENCE = "audience";
    public static final String AUDIENCE_PARENT = "parent";
    public static final String AUDIENCE_CHILD = "child";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String audience = getIntent().getStringExtra(EXTRA_AUDIENCE);
        if (AUDIENCE_CHILD.equals(audience) && PhoneLockRedirect.redirectIfGated(this)) {
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

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
