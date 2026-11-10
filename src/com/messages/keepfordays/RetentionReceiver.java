package com.messages.keepfordays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Two triggers, one transport (v1.8.0 provisioned mode):
 *
 *  1. RUN_RETENTION (in the intent-filter): Home Assistant companion
 *     fires action com.messages.keepfordays.action.RUN_RETENTION with
 *     extra key = <shared secret set in the panel>. Wrong/missing key
 *     or unconfigured key = refused and logged, never silent. Runs the
 *     provider pass DIRECTLY — no dance, no a11y, no WADB (v1.8.0).
 *
 *  2. RUN_PASS (explicit-component broadcast — NOT in the intent-filter;
 *     adb/orchestrator use):
 *       am broadcast --user 0 -n com.messages.keepfordays/.RetentionReceiver
 *         -a com.messages.keepfordays.action.RUN_PASS
 *     No key extra. Runs Ops.deletePass directly.
 *
 *  Both gate on Ops provisioning (v1.12.0 per-type: the WRITE_SMS
 *  appop + READ_SMS pair and/or the WRITE_CALL_LOG appop +
 *  READ_CALL_LOG pair — a pass runs if AT LEAST ONE type is
 *  provisioned; the unprovisioned type is skipped loudly by
 *  Ops.deletePass). Without provisioning, provider DELETE is a SILENT
 *  no-op at the provider — so the trigger layer refuses LOUDLY before
 *  trying (the silent no-op is invisible without the app-side gate +
 *  re-query).
 *
 *  v1.1.9–v1.7.x history: RUN_PASS used to require a SHELL-GRANTED role
 *  (flip-archiver first, flip-messages after; SHELL_HANDBACK_PENDING).
 *  That whole dance is gone — the appop IS the write privilege
 *  (live-verified on GOS/A17, probe A/B 2026-11-06).
 */
public class RetentionReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.messages.keepfordays.action.RUN_RETENTION";
    public static final String ACTION_PASS = "com.messages.keepfordays.action.RUN_PASS";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_PASS.equals(action)) {
            runPass(context, "broadcast");
            return;
        }
        if (!ACTION_RUN.equals(action)) return;
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String want = Ops.haKey(context);
                    if (want.length() == 0) {
                        Log2.line(context, "ha: refused — no key configured in the panel");
                        pr.finish();
                        return;
                    }
                    Bundle x = intent.getExtras();
                    String got = x == null ? null : x.getString("key");
                    if (!want.equals(got)) {
                        Log2.line(context, "ha: REFUSED — key mismatch"
                                + (got == null ? " (no key extra)" : ""));
                        pr.finish();
                        return;
                    }
                    Log2.line(context, "ha: key OK — running provisioned pass");
                    doPass(context, "ha");
                } catch (Throwable t) {
                    Log2.line(context, "ha crashed: " + t);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    /** RUN_PASS path (own goAsync lifecycle). */
    private void runPass(final Context context, final String source) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doPass(context, source);
                } catch (Throwable t) {
                    Log2.line(context, "RUN_PASS crashed: " + t);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    /** The shared provisioned pass: loud refusal when NOT provisioned
     *  for ANY type (never a silent provider no-op at the trigger
     *  layer), verdict to events.log + panel + notification. No role
     *  hand-back — nothing is ever taken. */
    private void doPass(final Context context, final String source) {
        if (!Ops.isProvisioned(context) && !Ops.isCallProvisioned(context)) {
            String v = "pass refused — NOT PROVISIONED; " + Ops.provisionStatus(context)
                    + " ; nothing deleted";
            Log2.line(context, "pass(" + source + "): " + v);
            Ops.setLastVerdict(context, source + ": " + v);
            Ops.notifyKind(context, "fail", "Retention pass refused — not provisioned", v);
            return;
        }
        String verdict = Ops.deletePass(context, source);
        Ops.setLastVerdict(context, source + ": " + verdict);
        // v1.11.0: broadcast/manual success is its own preference (default
        // ON — explicit triggers deserve feedback).
        Ops.notifyKind(context, "run_ok", "Retention pass complete (" + source + ")", verdict);
    }
}
