package com.messages.keepfordays;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

public class SmsDeliverReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) {
            Log2.line(context, "DELIVER: no pdus in intent — nothing written (POSSIBLE LOST TEXT)");
            return;
        }
        StringBuilder body = new StringBuilder();
        String addr = null;
        for (int i = 0; i < msgs.length; i++) {
            SmsMessage m = msgs[i];
            if (m == null) continue;
            if (addr == null && m.getOriginatingAddress() != null) addr = m.getOriginatingAddress();
            if (m.getDisplayMessageBody() != null) body.append(m.getDisplayMessageBody());
        }
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, addr == null ? "" : addr);
        v.put(Telephony.Sms.BODY, body.toString());
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        int sub = SubscriptionManager.getDefaultSubscriptionId();
        if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            v.put(Telephony.Sms.SUBSCRIPTION_ID, sub);
        }
        Uri uri = null;
        String err = null;
        try {
            uri = context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v);
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        boolean dummy = uri == null || "0".equals(uri.getLastPathSegment());
        if (err != null || dummy) {
            Log2.line(context, "DELIVER INSERT NOT PROVEN (err=" + err + " uri=" + uri
                    + ") — POSSIBLE LOST TEXT from " + addr);
        } else {
            int after = -1;
            Cursor cur = null;
            try {
                cur = context.getContentResolver().query(
                        Telephony.Sms.CONTENT_URI, new String[]{"_id"},
                        Telephony.Sms.ADDRESS + "=?", new String[]{addr == null ? "" : addr}, null);
                after = cur == null ? -1 : cur.getCount();
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                if (cur != null) cur.close();
            }
            Log2.line(context, "DELIVER: wrote " + uri + " (rows from " + addr + " now=" + after
                    + (err != null ? "; requery err=" + err : "") + ")");
        }
        notifyText(context, addr, body.toString());
    }

    private void notifyText(Context context, String addr, String body) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("sms") == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "sms", "SMS", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String title = "SMS via Archiver" + (addr == null ? "" : " — " + addr);
        String text = body.length() > 200 ? body.substring(0, 200) + "…" : body.toString();
        Notification n = new Notification.Builder(context, "sms")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        nm.notify(1001, n);
    }
}
