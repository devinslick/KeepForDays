package com.messages.keepfordays;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

public class RespondViaMessageService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String to = "?";
        if (intent != null && intent.getData() != null) {
            to = intent.getData().getSchemeSpecificPart();
        }
        Log2.line(this, "RESPOND_VIA_MESSAGE REFUSED loudly (Archiver does not send) to=" + to);
        Toast.makeText(this, "Archiver: direct reply unsupported — message NOT sent",
                Toast.LENGTH_LONG).show();
        stopSelf(startId);
        return Service.START_NOT_STICKY;
    }
}
