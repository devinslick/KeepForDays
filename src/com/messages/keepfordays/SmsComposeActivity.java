package com.messages.keepfordays;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class SmsComposeActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        Uri data = in == null ? null : in.getData();
        // Forward compose intents to Google Messages (the archiver never sends).
        Intent out = new Intent(Intent.ACTION_SENDTO);
        out.setData(data);
        out.setPackage("com.google.android.apps.messaging");
        try {
            startActivity(out);
            Log2.line(this, "SENDTO forwarded (" + data + ")");
        } catch (Exception e) {
            Log2.line(this, "SENDTO forward failed: " + e.getMessage());
        }
        finish();
    }
}
