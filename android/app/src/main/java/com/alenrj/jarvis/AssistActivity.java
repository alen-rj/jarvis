package com.alenrj.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Entry point when Jarvis is the phone's default assistant (long-press power / home,
 * assistant gesture) or a headset voice button. Opens Jarvis already listening.
 */
public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(JarvisPlugin.EXTRA_WAKE, true);
        startActivity(open);
        finish();
        overridePendingTransition(0, 0);
    }
}
