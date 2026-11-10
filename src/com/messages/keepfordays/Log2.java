package com.messages.keepfordays;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** UTC-timestamped append-only event log (files/events.log) + logcat.
 *  Never-swallow: every phase, verdict, selector miss and node-tree
 *  dump lands here. */
public final class Log2 {
    static final String TAG = "KeepForDays";

    private Log2() {
    }

    public static void line(Context c, String msg) {
        Log.i(TAG, msg);
        FileWriter w = null;
        try {
            File f = new File(c.getFilesDir(), "events.log");
            w = new FileWriter(f, true);
            w.write(ts() + " " + msg + "\n");
        } catch (IOException e) {
            Log.e(TAG, "event log write failed", e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    Log.e(TAG, "event log close failed", e);
                }
            }
        }
    }

    public static String path(Context c) {
        return new File(c.getFilesDir(), "events.log").getAbsolutePath();
    }

    private static String ts() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
