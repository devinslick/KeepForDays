package com.messages.keepfordays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MmsPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log2.line(context, "WAP_PUSH (MMS) received while Archiver holds the SMS role — "
                + "NOT retrieved. Restore Messages as default, then open Messages "
                + "to fetch it (the MMSC keeps the message for retry).");
    }
}
