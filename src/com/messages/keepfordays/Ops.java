package com.messages.keepfordays;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

/** Core engine: retention window/HA key/schedule persistence, provider
 *  counts (plain READ_SMS), and the delete pass.
 *
 *  v1.8.0 PROVISIONED MODE (the dance-eliminator, Devin 2026-11-06):
 *  the provider write gate is the WRITE_SMS AppOps op — the role was
 *  only ever the standard vehicle for setting it. One-time shell
 *  provisioning
 *      adb shell appops set com.messages.keepfordays WRITE_SMS allow
 *  (+ the READ_SMS runtime grant) lets this app insert/delete WITHOUT
 *  the role, WITHOUT a11y, WITHOUT WADB — live-verified on GOS/A17
 *  (probe A/B 2026-11-06: role-less deletes ret=100 100->0 and
 *  inserts 3/3 land; role stayed with Google Messages throughout).
 *  The entire dance (a11y + WADB + role flips) is GONE from this app.
 *
 *  v1.12.0 CALL HISTORY (Devin 2026-11-09): the same retention
 *  policy/actions now cover the call log too (CallLog.Calls, voicemail
 *  rows included). Provisioning is PER-TYPE (Devin decision 1.a): the
 *  SMS/MMS sweep needs the WRITE_SMS appop + READ_SMS, the call sweep
 *  needs its own pair — WRITE_CALL_LOG appop
 *      adb shell appops set com.messages.keepfordays WRITE_CALL_LOG allow
 *  + the READ_CALL_LOG runtime grant. A missing pair SKIPS that data
 *  type loudly and never blocks the other one. Not granting call-log
 *  access is the supported "app never touches calls" posture.
 *
 *  Provider DELETE for an unprivileged caller is a SILENT no-op
 *  (returns 0, no exception) — re-query counts before/after are the
 *  only truth, and the provisioned gate refuses loudly before trying. */
public final class Ops {
    public static final String PREFS = "ca";
    public static final String PKG_SELF = "com.messages.keepfordays";
    public static final int JOB_ID = 42;
    static final long DAY_MS = 86400000L;

    private Ops() {
    }

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- window / HA key / schedule ----

    /** v1.10.0: the 450-day floor was a testing-era artifact and is
     *  RETIRED (Devin, 2026-11-06). Default window 30 days; any integer
     *  1..36500 accepted — the guard only blocks nonsense values (0 or
     *  a negative window would delete everything immediately). */
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 36500;
    public static final int DEFAULT_DAYS = 30;

    public static int retentionDays(Context c) {
        int d;
        try {
            d = Integer.parseInt(p(c).getString("days", String.valueOf(DEFAULT_DAYS)));
        } catch (Exception e) {
            d = DEFAULT_DAYS;
        }
        if (d < MIN_DAYS) {
            Log2.line(c, "retention window " + d + "d below the minimum —"
                    + " CLAMPED to " + MIN_DAYS + "d (default is "
                    + DEFAULT_DAYS + "d)");
            d = MIN_DAYS;
            setRetentionDays(c, d);
        }
        if (d > MAX_DAYS) d = MAX_DAYS;
        return d;
    }

    /** @return true if saved; REFUSES (loudly) outside [MIN_DAYS, MAX_DAYS]. */
    public static boolean setRetentionDays(Context c, int d) {
        if (d < MIN_DAYS || d > MAX_DAYS) {
            Log2.line(c, "retention window REFUSED: " + d + "d outside ["
                    + MIN_DAYS + "," + MAX_DAYS + "] (any integer in range;"
                    + " default " + DEFAULT_DAYS + "d)");
            return false;
        }
        p(c).edit().putString("days", String.valueOf(d)).commit();
        Log2.line(c, "retention window set to " + d + "d");
        return true;
    }

    public static String haKey(Context c) {
        return p(c).getString("ha_key", "");
    }

    public static void setHaKey(Context c, String k) {
        p(c).edit().putString("ha_key", k == null ? "" : k).commit();
        Log2.line(c, "HA key " + (k == null || k.length() == 0 ? "cleared" : "set"));
    }

    // ---- v1.8.0 provisioning (the appop self-check) ----

    /** OP_WRITE_SMS is hidden from the public SDK (no OPSTR constant);
     *  the string form with the public checkOpNoThrow(String,...) is the
     *  supported self-check on API 29+. checkOp (not noteOp): a pure
     *  read, no usage recording, no side effects. */
    public static boolean writeOpAllowed(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (ao == null) return false;
            return ao.checkOpNoThrow("android:write_sms",
                    android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            Log2.line(c, "appop self-check failed: " + t);
            return false;
        }
    }

    public static boolean readSmsGranted(Context c) {
        try {
            return c.checkSelfPermission(android.Manifest.permission.READ_SMS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the one-time shell provisioning is in place: the
     *  WRITE_SMS appop ALLOWED + READ_SMS granted. Gates the SMS/MMS
     *  sweep (deletePass phase 1/2 and all TestOps sms/mms writes) and
     *  refuses loudly when it is missing. v1.12.0: call-log handling
     *  has its OWN pair below — provisioning is per-type. */
    public static boolean isProvisioned(Context c) {
        return writeOpAllowed(c) && readSmsGranted(c);
    }

    public static String PROVISION_CMD =
            "adb shell appops set com.messages.keepfordays WRITE_SMS allow";

    // ---- v1.12.0 per-type provisioning: CALL LOG ----
    // Same shape as SMS: the write gate is an appop (OP_WRITE_CALL_LOG,
    // hidden from the public SDK, string-form checkOpNoThrow is the
    // supported self-check on API 29+) + the READ_CALL_LOG runtime
    // grant. The default-dialer role is the usual vehicle for setting
    // the appop; the app never holds it (same posture as SMS).

    /** OP_WRITE_CALL_LOG is hidden from the public SDK (no OPSTR
     *  constant); string form + checkOpNoThrow (a pure read). */
    public static boolean writeOpCallAllowed(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (ao == null) return false;
            return ao.checkOpNoThrow("android:write_call_log",
                    android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            Log2.line(c, "appop self-check (call) failed: " + t);
            return false;
        }
    }

    public static boolean readCallLogGranted(Context c) {
        try {
            return c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the call sweep may run: WRITE_CALL_LOG appop ALLOWED
     *  + READ_CALL_LOG granted. Gates the call phase of deletePass and
     *  the call TestOps; refusing here SKIPS calls without touching
     *  the SMS/MMS sweep. */
    public static boolean isCallProvisioned(Context c) {
        return writeOpCallAllowed(c) && readCallLogGranted(c);
    }

    public static String PROVISION_CMD_CALL =
            "adb shell appops set com.messages.keepfordays WRITE_CALL_LOG allow";

    // ---- v1.8.2: thread-armed MMS seeding (panel path) ----

    /** Provisioned callers cannot allocate threads on this build (see
     *  BoundaryTestReceiver.mmsSeed) — MMS seeds render only with a
     *  thread id resolved once via shell and persisted here. BTEST_MMSSEED
     *  saves it whenever it runs with a thread; panel seeds use it. */
    public static long seedThreadId(Context c) {
        return p(c).getLong("seed_thread", -1L);
    }

    public static void setSeedThreadId(Context c, long id) {
        if (id > 0) {
            p(c).edit().putLong("seed_thread", id).commit();
            Log2.line(c, "seed thread ARMED: " + id
                    + " (panel MMS seeds will render in your messaging app)");
        }
    }

    // ---- v1.9.0 onboarding ----

    public static boolean onboarded(Context c) {
        return p(c).getBoolean("onboarded", false);
    }

    public static void setOnboarded(Context c) {
        p(c).edit().putBoolean("onboarded", true).commit();
        Log2.line(c, "onboarding complete/skipped — first-run flow dismissed");
    }

    /** Human-readable provisioning verdict for the panel + refusals.
     *  v1.12.0: per-type — one line per data type, each with its own
     *  fix command. */
    public static String provisionStatus(Context c) {
        boolean sop = writeOpAllowed(c);
        boolean srd = readSmsGranted(c);
        boolean sms = sop && srd;
        boolean cop = writeOpCallAllowed(c);
        boolean crd = readCallLogGranted(c);
        boolean call = cop && crd;
        return "provisioned: sms/mms " + (sms ? "YES" : "NO")
                + " (appop WRITE_SMS: " + (sop ? "ALLOWED" : "not allowed")
                + ", READ_SMS: " + (srd ? "granted" : "MISSING") + ")"
                + (sms ? "" : " — fix: " + PROVISION_CMD)
                + "\ncalls " + (call ? "YES" : "NO")
                + " (appop WRITE_CALL_LOG: " + (cop ? "ALLOWED" : "not allowed")
                + ", READ_CALL_LOG: " + (crd ? "granted" : "MISSING") + ")"
                + (call ? "" : " — fix: " + PROVISION_CMD_CALL);
    }

    // ---- last pass verdict (panel status; ex-dance_state/dance_last) ----

    public static void setLastVerdict(Context c, String v) {
        p(c).edit().putString("last_verdict", v).commit();
    }

    public static String lastVerdict(Context c) {
        return p(c).getString("last_verdict", "(none yet)");
    }

    public static boolean scheduleEnabled(Context c) {
        return p(c).getBoolean("sched", false);
    }

    // ---- v1.5.5 schedule: daily at a configurable hour, one-shot chained ----
    // JobScheduler periodic jobs give no wall-clock control and no visible
    // next-run; a persisted ONE-SHOT job armed for the next occurrence of
    // the run hour (re-armed by RetentionJob at every fire) is deterministic
    // and inspectable. Caveat: an app force-stop suspends jobs until the app
    // is launched again (platform behavior) — the panel status shows it.

    public static int runHour(Context c) {
        int h = p(c).getInt("run_hour", 3);
        return (h < 0 || h > 23) ? 3 : h;
    }

    /** v1.10.2: minute granularity — the time picker picks HH:MM. */
    public static int runMinute(Context c) {
        int m = p(c).getInt("run_min", 0);
        return (m < 0 || m > 59) ? 0 : m;
    }

    static String fmtHM(int h, int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", h, m);
    }

    public static void setRunTime(Context c, int h, int m) {
        if (h < 0 || h > 23 || m < 0 || m > 59) {
            Log2.line(c, "run time REFUSED: " + h + ":" + m);
            return;
        }
        p(c).edit().putInt("run_hour", h).putInt("run_min", m).commit();
        Log2.line(c, "run time set to " + fmtHM(h, m));
        if (scheduleEnabled(c)) armNextRun(c);      // re-arm for the new time
    }

    public static void setRunHour(Context c, int h) {
        setRunTime(c, h, runMinute(c));
    }

    public static void setRunMinute(Context c, int m) {
        setRunTime(c, runHour(c), m);
    }

    /** Next wall-clock occurrence of the run time (local time), epoch ms. */
    public static long nextRunAt(Context c) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, runHour(c));
        cal.set(java.util.Calendar.MINUTE, runMinute(c));
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    public static String fmtTime(long ms) {
        if (ms <= 0) return "(none)";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    /** Arms the one-shot persisted job for the next run hour.
     *  @return the armed-for epoch ms, or -1 on failure (loud). */
    public static long armNextRun(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            Log2.line(c, "schedule arm REFUSED: no JobScheduler");
            return -1;
        }
        long at = nextRunAt(c);
        long delta = Math.max(60000L, at - System.currentTimeMillis());
        JobInfo b = new JobInfo.Builder(JOB_ID, new ComponentName(c, RetentionJob.class))
                .setMinimumLatency(delta)
                .setOverrideDeadline(delta + 2L * 3600000L)   // bound platform batching
                .setPersisted(true)
                .build();
        try {
            int rc = js.schedule(b);
            boolean ok = js.getPendingJob(JOB_ID) != null;
            Log2.line(c, "schedule armed for " + fmtTime(at) + " (rc=" + rc
                    + " pendingJob=" + ok + ")");
            if (ok) p(c).edit().putLong("next_run_ms", at).commit();
            return ok ? at : -1;
        } catch (Exception e) {
            Log2.line(c, "schedule arm FAILED (never-silent): " + e);
            return -1;
        }
    }

    public static long armedForMs(Context c) {
        return p(c).getLong("next_run_ms", 0);
    }

    public static long lastFireMs(Context c) {
        return p(c).getLong("last_fire_ms", 0);
    }

    // ---- (v1.6.x Wi-Fi precondition reads removed with the dance:
    //      a provisioned pass needs no screen, no Wi-Fi, no listener) ----

    public static void recordFire(Context c) {
        p(c).edit().putLong("last_fire_ms", System.currentTimeMillis()).commit();
    }

    /** Returns true if the requested schedule state is CONFIRMED on the platform. */
    public static boolean setSchedule(Context c, boolean on) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (on) {
            if (js == null) {
                Log2.line(c, "schedule ON refused: no JobScheduler");
                return false;
            }
            // prereq for an unattended run — refuse loudly BEFORE arming.
            // v1.8.0: provisioning replaces the a11y/shell-key checks; a
            // provisioned pass needs NO screen, NO Wi-Fi, NO WADB, so a
            // 03:00 run just runs. v1.12.0: provisioning is per-type —
            // the schedule needs AT LEAST ONE provisioned type; an
            // unprovisioned type is skipped (loudly) each pass.
            String broken = null;
            if (!isProvisioned(c) && !isCallProvisioned(c))
                broken = "not provisioned (" + PROVISION_CMD + " / "
                        + PROVISION_CMD_CALL + ")";
            else if (retentionDays(c) < MIN_DAYS) broken = "retention window below floor";
            if (broken != null) {
                Log2.line(c, "schedule ON REFUSED — " + broken + " (fix setup first)");
                return false;
            }
            p(c).edit().putBoolean("sched", true).commit();
            long at = armNextRun(c);
            return at > 0;
        } else {
            p(c).edit().putBoolean("sched", false).commit();
            if (js != null) js.cancel(JOB_ID);
            boolean gone = js == null || js.getPendingJob(JOB_ID) == null;
            Log2.line(c, "schedule OFF: pendingJob gone=" + gone);
            return gone;
        }
    }

    // ---- notifications ----

    static NotificationManager nm(Context c) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel("runs") == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "runs", "Retention runs", NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    public static void notify(Context c, String title, String text) {
        NotificationManager nm = nm(c);
        if (nm == null) return;
        String t = text.length() > 300 ? text.substring(0, 300) + "…" : text;
        Notification n = new Notification.Builder(c, "runs")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(title)
                .setContentText(t)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .build();
        nm.notify(2001, n);
    }

    // ---- v1.11.0 notification preferences ----
    // Kinds: sched_ok (scheduled-run success — DEFAULT OFF, no daily
    // noise), run_ok (manual/broadcast success), fail (failures &
    // refusals), test (TestOps verdicts).

    public static boolean notifEnabled(Context c, String kind) {
        if ("sched_ok".equals(kind)) return p(c).getBoolean("n_sched_ok", false);
        return p(c).getBoolean("n_" + kind, true);
    }

    public static void setNotifEnabled(Context c, String kind, boolean on) {
        p(c).edit().putBoolean("n_" + kind, on).commit();
        Log2.line(c, "notification pref '" + kind + "' = " + on);
    }

    /** Posts only when the scenario's notification preference allows;
     *  suppressed posts are logged (never silently dropped overall). */
    public static void notifyKind(Context c, String kind, String title, String text) {
        if (notifEnabled(c, kind)) {
            notify(c, title, text);
        } else {
            Log2.line(c, "notification suppressed by prefs: " + title);
        }
    }

    // ---- counts (plain READ_SMS — no role needed; do not regress) ----

    public static int countRows(Context c, Uri uri, String sel, String[] args) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{"_id"}, sel, args, null);
            return cur == null ? -1 : cur.getCount();
        } catch (Throwable t) {
            Log2.line(c, "query " + uri + " failed: " + t);
            return -2;
        } finally {
            if (cur != null) cur.close();
        }
    }

    // ---- the delete pass (provisioned; re-query = truth) ----

    /** SYNCHRONOUS — call off the UI thread. Requires per-type
     *  provisioning (v1.12.0): the SMS/MMS sweep runs when the
     *  WRITE_SMS appop + READ_SMS pair is in place, the call sweep when
     *  WRITE_CALL_LOG + READ_CALL_LOG is; an unprovisioned type is
     *  SKIPPED loudly (named in the verdict) and never blocks the
     *  other. Refuses outright only when NEITHER type is provisioned.
     *  Works screen-off, locked, no Wi-Fi — nothing but the providers
     *  is touched. MMS date is SECONDS; SMS and CallLog dates are
     *  MILLISECONDS. */
    public static String deletePass(Context c, String source) {
        boolean smsOk = isProvisioned(c);
        boolean callOk = isCallProvisioned(c);
        if (!smsOk && !callOk) {
            String res = "SKIP: NOT PROVISIONED — " + provisionStatus(c)
                    + " ; nothing deleted";
            Log2.line(c, "pass(" + source + "): " + res);
            return res;
        }
        int days = retentionDays(c);
        long cutMs = System.currentTimeMillis() - days * DAY_MS;
        String smsCut = String.valueOf(cutMs);
        String mmsCut = String.valueOf(cutMs / 1000L);
        Log2.line(c, "pass(" + source + "): provisioned mode (per-type;"
                + " sms=" + (smsOk ? "yes" : "SKIP") + " calls="
                + (callOk ? "yes" : "SKIP") + "; role NOT held) N=" + days
                + "d cutoff(ms)=" + smsCut);

        String err = null;
        StringBuilder skip = new StringBuilder();

        // ---- SMS/MMS sweep (unchanged policy; gated on smsOk) ----
        int smsDel = 0, mmsDel = 0;
        long[] smsOut = new long[]{0, 0, 0};
        long[] mmsOut = new long[]{0, 0, 0};
        int smsBefore = 0, mmsBefore = 0, smsAfter = 0, mmsAfter = 0;
        int smsRaw = 0, mmsRaw = 0;
        if (smsOk) {
            // PHASE 1 — VISIBLE rows (restricted view): chunked _id
            // deletes. Bounded 500-row batches keep Google Messages'
            // bugle_db reconciliation smooth for real (default-app-
            // created) rows.
            smsBefore = countRows(c, Telephony.Sms.CONTENT_URI, "date < ?", new String[]{smsCut});
            mmsBefore = countRows(c, Telephony.Mms.CONTENT_URI, "date < ?", new String[]{mmsCut});
            if (smsBefore > 0) {
                smsOut = chunkedDelete(c, Telephony.Sms.CONTENT_URI, "date < ?",
                        new String[]{smsCut}, smsBefore);
                if (smsOut[2] != 0) err = "sms batches:" + smsOut[2];
            }
            if (mmsBefore > 0) {
                mmsOut = chunkedDelete(c, Telephony.Mms.CONTENT_URI, "date < ?",
                        new String[]{mmsCut}, mmsBefore);
                if (mmsOut[2] != 0) err = (err == null ? "" : err + "; ")
                        + "mms batches:" + mmsOut[2];
            }

            // PHASE 2 — INVISIBLE leftovers: the restricted view hides
            // rows created by non-default callers (self-seeded test
            // rows and the mirror class), and it can LIE — count-before
            // said "nothing" while hundreds of rows existed (live
            // 2026-11-06, the "nothing older than 30d" false verdict).
            // The DELETE path hits the base table UNFILTERED, so an
            // unconditional raw WHERE sweep per table removes what
            // phase 1 could not see. Delete return values are the
            // truth; ret 0 = genuinely nothing.
            try {
                smsRaw = c.getContentResolver().delete(Telephony.Sms.CONTENT_URI,
                        "date < ?", new String[]{smsCut});
            } catch (Throwable t) {
                err = (err == null ? "" : err + "; ")
                        + "sms raw sweep threw: " + t.getMessage();
            }
            try {
                mmsRaw = c.getContentResolver().delete(Telephony.Mms.CONTENT_URI,
                        "date < ?", new String[]{mmsCut});
            } catch (Throwable t) {
                err = (err == null ? "" : err + "; ")
                        + "mms raw sweep threw: " + t.getMessage();
            }
            smsAfter = countRows(c, Telephony.Sms.CONTENT_URI, "date < ?", new String[]{smsCut});
            mmsAfter = countRows(c, Telephony.Mms.CONTENT_URI, "date < ?", new String[]{mmsCut});
            smsDel = (smsBefore - smsAfter) + smsRaw;
            mmsDel = (mmsBefore - mmsAfter) + mmsRaw;
        } else {
            skip.append("; sms/mms SKIPPED — not provisioned (").append(PROVISION_CMD).append(')');
        }

        // ---- v1.12.0 CALL HISTORY sweep (same policy; gated on
        //      callOk; voicemail rows included — they live in the same
        //      table under the same date semantics) ----
        int callDel = 0;
        long[] callOut = new long[]{0, 0, 0};
        int callRaw = 0;
        int callBefore = 0, callAfter = 0;
        if (callOk) {
            callBefore = countRows(c,
                    android.provider.CallLog.Calls.CONTENT_URI,
                    "date < ?", new String[]{smsCut});
            if (callBefore > 0) {
                callOut = chunkedDelete(c,
                        android.provider.CallLog.Calls.CONTENT_URI,
                        "date < ?", new String[]{smsCut}, callBefore);
                if (callOut[2] != 0) err = (err == null ? "" : err + "; ")
                        + "call batches:" + callOut[2];
            }
            // raw sweep — same invisible-leftover rationale as SMS/MMS.
            try {
                callRaw = c.getContentResolver().delete(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        "date < ?", new String[]{smsCut});
            } catch (Throwable t) {
                err = (err == null ? "" : err + "; ")
                        + "call raw sweep threw: " + t.getMessage();
            }
            int callAfterLocal = countRows(c,
                    android.provider.CallLog.Calls.CONTENT_URI,
                    "date < ?", new String[]{smsCut});
            callAfter = callAfterLocal;
            callDel = (callBefore - callAfter) + callRaw;
        } else {
            skip.append("; calls SKIPPED — not provisioned (")
                    .append(PROVISION_CMD_CALL).append(')');
        }

        String res;
        if (smsDel <= 0 && mmsDel <= 0 && callDel <= 0) {
            // honest: BOTH the visible pass and the raw sweep found
            // nothing — the restricted view no longer gates the verdict.
            res = "nothing older than " + days + "d — nothing deleted";
        } else {
            // totals lead: the notification reads "deleted N sms and M
            // mms and K calls"; the phase decomposition stays in
            // events.log for forensics.
            Log2.line(c, "pass detail: visible pass sms " + (smsBefore - smsAfter)
                    + " / mms " + (mmsBefore - mmsAfter) + " / calls "
                    + (callBefore - callAfter)
                    + "; raw sweep sms " + smsRaw + " / mms " + mmsRaw
                    + " / calls " + callRaw + "; batches " + smsOut[0]
                    + "/" + mmsOut[0] + "/" + callOut[0]);
            res = "deleted " + smsDel + " sms and " + mmsDel + " mms and "
                    + callDel + " calls older than " + days + "d"
                    + (err == null ? "" : "; errors: " + err);
        }
        res = res + skip;
        Log2.line(c, "pass: " + res);
        if (err != null) {
            notifyKind(c, "fail", "Retention pass — errors", res);
        }
        return res;
    }

    /** v1.5.3: deletes rows matching sel in CHUNK-row _id batches (oldest
     *  first). Returns {batchesUsed, totalRet, failedBatches}. A batch that
     *  throws or deletes 0 while rows remain is logged and ENDS the sweep
     *  (never spin on a poisoned batch); the caller's re-query is truth. */
    static final int DEL_CHUNK = 500;

    static long[] chunkedDelete(Context c, android.net.Uri uri, String sel,
                                String[] args, int totalRows) {
        long batches = 0, retTotal = 0, failed = 0;
        int maxBatches = totalRows / DEL_CHUNK + 4;
        while (batches < maxBatches) {
            android.database.Cursor cur = null;
            StringBuilder ids = new StringBuilder();
            int n = 0;
            try {
                cur = c.getContentResolver().query(uri, new String[]{"_id"},
                        sel, args, "date ASC, _id ASC LIMIT " + DEL_CHUNK);
                while (cur != null && cur.moveToNext()) {
                    if (n > 0) ids.append(',');
                    ids.append(cur.getLong(0));
                    n++;
                }
            } catch (Throwable t) {
                Log2.line(c, "chunk select failed: " + t);
                failed++;
                break;
            } finally {
                if (cur != null) cur.close();
            }
            if (n == 0) break;                       // nothing left
            try {
                int r = c.getContentResolver().delete(uri,
                        sel + " AND _id IN (" + ids + ")", args);
                batches++;
                if (r == 0) {                        // poisoned batch — stop, don't spin
                    Log2.line(c, "chunk batch " + batches + " deleted 0 of " + n
                            + " selected — stopping this table's sweep");
                    failed++;
                    break;
                }
                retTotal += r;
            } catch (Throwable t) {
                Log2.line(c, "chunk batch " + (batches + 1) + " threw: " + t
                        + " — stopping this table's sweep");
                failed++;
                break;
            }
        }
        return new long[]{batches, retTotal, failed};
    }
}
