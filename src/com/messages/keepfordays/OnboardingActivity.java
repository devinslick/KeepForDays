package com.messages.keepfordays;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** v1.9.0 onboarding wizard — first run only, SKIPPABLE at every step.
 *  Page 1: what the app is. Page 2: permissions check/warning (READ_SMS
 *  + notifications runtime requests; provisioning status + the fix
 *  command with copy). Page 3: baseline settings pointer + DONE.
 *  Skip/finish persists the flag; the wizard never returns. */
public class OnboardingActivity extends Activity {

    LinearLayout content;   // page host
    TextView stepView;
    int page = 0;
    boolean finished;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Ops.onboarded(this)) {           // already done — never re-show
            finished = true;
            finish();
            return;
        }
        Log2.line(this, "KeepForDays onboarding: start");

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(this));

        // top bar: title + SKIP
        LinearLayout top = Ui.row(this);
        int p = Ui.dp(this, 16);
        top.setPadding(p, p, p, p);
        TextView t = Ui.headline(this, "Welcome to Keep For Days");
        t.setLayoutParams(Ui.wrap());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tp);
        top.addView(t);
        Button skip = Ui.tonal(this, "SKIP");
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { done("skipped"); }
        });
        top.addView(skip);
        root.addView(top);

        stepView = Ui.small(this, "");
        stepView.setGravity(Gravity.CENTER);
        stepView.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(stepView);

        content = Ui.column(this);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, 0, pad, pad);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(content);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        show(0);
    }

    void show(int n) {
        page = n;
        content.removeAllViews();
        stepView.setText("Step " + (n + 1) + " of 3");
        if (n == 0) pageWelcome();
        else if (n == 1) pagePermissions();
        else pageSetup();
    }

    void pageWelcome() {
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.title(this, "Automatic retention"));
        c1.addView(Ui.body(this,
                "Keep For Days deletes messages — SMS, MMS, and RCS —"
                + " and call history older than your retention window"
                + " (default " + Ops.DEFAULT_DAYS + " days), every day,"
                + " on a schedule you control. The SMS role always stays"
                + " with Google Messages and the dialer role with your"
                + " Phone app — this app never becomes either."));
        content.addView(c1);
        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.title(this, "Private by construction"));
        c2.addView(Ui.body(this,
                "No network permission, no analytics, no accessibility "
                + "service, no wireless debugging. The app talks only to "
                + "your message and call databases — and call history is"
                + " opt-in: if you never grant it, the app never touches"
                + " calls."));
        content.addView(c2);
        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.title(this, "One-time setup"));
        c3.addView(Ui.body(this,
                "Two things are needed once: standard permissions "
                + "(next page) and one adb command per data type that"
                + " grants the write privilege permanently. Everything"
                + " is checkable later on the Home screen."));
        content.addView(c3);
        content.addView(nextButton("NEXT: PERMISSIONS"));
    }

    void pagePermissions() {
        boolean sms = checkSms();
        boolean notif = checkNotif();
        boolean calls = checkCalls();
        boolean provSms = Ops.isProvisioned(this);
        boolean provCall = Ops.isCallProvisioned(this);

        LinearLayout chips = Ui.row(this);
        chips.addView(Ui.chip(this, sms ? "SMS: granted" : "SMS: MISSING",
                sms ? Ui.ok(this) : Ui.danger(this), sms ? Ui.ok(this) : Ui.danger(this)));
        LinearLayout.LayoutParams cl = Ui.wrap();
        cl.setMargins(Ui.dp(this, 8), 0, 0, 0);
        TextView n = Ui.chip(this, notif ? "Notifications: granted" : "Notifications: off",
                notif ? Ui.ok(this) : Ui.textSec(this), notif ? Ui.ok(this) : Ui.textSec(this));
        n.setLayoutParams(cl);
        chips.addView(n);
        LinearLayout.LayoutParams cl2 = Ui.wrap();
        cl2.setMargins(Ui.dp(this, 8), 0, 0, 0);
        TextView ck = Ui.chip(this, calls ? "Calls: granted" : "Calls: not granted (opt-out)",
                calls ? Ui.ok(this) : Ui.textSec(this),
                calls ? Ui.ok(this) : Ui.textSec(this));
        ck.setLayoutParams(cl2);
        chips.addView(ck);
        content.addView(chips);

        if (!sms || !notif) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.body(this,
                    "Grant the standard permissions below — nothing is "
                    + "deleted until you arm a schedule or run a pass."));
            Button g = Ui.filled(this, "GRANT SMS + NOTIFICATION PERMISSIONS");
            g.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { requestPerms(); }
            });
            card.addView(g);
            content.addView(card);
        }

        LinearLayout callCard = Ui.card(this);
        callCard.addView(Ui.title(this, "Call history (optional)"));
        callCard.addView(Ui.body(this, calls
                ? "READ_CALL_LOG granted — call retention is available"
                + " once the calls write gate is provisioned."
                : "READ_CALL_LOG not granted — call history is not"
                + " counted or touched. Grant it only if you want call"
                + " retention."));
        if (!calls) {
            Button gc = Ui.filled(this, "GRANT CALL LOG PERMISSION");
            gc.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { requestCallPerms(); }
            });
            callCard.addView(gc);
        }
        content.addView(callCard);

        LinearLayout provCard = Ui.card(this);
        provCard.addView(Ui.title(this, "Write privilege (one-time adb)"));
        provCard.addView(Ui.body(this,
                (provSms ? "sms/mms: PROVISIONED.\n" : "sms/mms: NOT YET — run:\n"
                + Ops.PROVISION_CMD + "\n")
                + (provCall ? "calls: PROVISIONED.\n" : "calls: NOT YET — run:\n"
                + Ops.PROVISION_CMD_CALL + "\n")));
        if (!provSms) {
            TextView cmd = Ui.mono(this, Ops.PROVISION_CMD);
            provCard.addView(cmd);
            Button cp = Ui.tonal(this, "COPY SMS COMMAND");
            cp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Ui.copyToClipboard(OnboardingActivity.this,
                            Ops.PROVISION_CMD, "Provisioning command copied");
                }
            });
            provCard.addView(cp);
        }
        if (!provCall) {
            TextView cmd2 = Ui.mono(this, Ops.PROVISION_CMD_CALL);
            provCard.addView(cmd2);
            Button cp2 = Ui.tonal(this, "COPY CALLS COMMAND");
            cp2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Ui.copyToClipboard(OnboardingActivity.this,
                            Ops.PROVISION_CMD_CALL, "Provisioning command copied");
                }
            });
            provCard.addView(cp2);
        }
        provCard.addView(Ui.small(this,
                "Without provisioning, that type's retention passes skip"
                + " loudly and delete nothing (safe by default)."));
        content.addView(provCard);

        content.addView(nextButton("NEXT: SETUP"));
    }

    void pageSetup() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "Your retention window"));
        card.addView(Ui.body(this, "Currently: " + Ops.retentionDays(this)
                + " days (default " + Ops.DEFAULT_DAYS + " — change it any"
                + " time under Basic)."));
        content.addView(card);
        LinearLayout card2 = Ui.card(this);
        card2.addView(Ui.title(this, "Daily schedule"));
        card2.addView(Ui.body(this, "Scheduling lives under Scheduling in "
                + "the sidebar. A provisioned pass runs even while the "
                + "phone is locked or asleep; the schedule is OFF until "
                + "you arm it."));
        content.addView(card2);
        LinearLayout card3 = Ui.card(this);
        card3.addView(Ui.title(this, "Debug & tests"));
        card3.addView(Ui.body(this, "The Debug section creates synthetic "
                + "1969-dated test SMS/MMS/calls and purges them — the "
                + "retention pipeline can be exercised without touching "
                + "real data."));
        content.addView(card3);
        content.addView(nextButton("DONE"));
    }

    Button nextButton(String label) {
        Button b = Ui.filled(this, label);
        LinearLayout.LayoutParams lp = Ui.match();
        lp.setMargins(0, Ui.dp(this, 12), 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page < 2) show(page + 1);
                else done("completed");
            }
        });
        return b;
    }

    void requestPerms() {
        requestPermissions(new String[]{
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.POST_NOTIFICATIONS}, 2);
    }

    void requestCallPerms() {
        requestPermissions(new String[]{
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.WRITE_CALL_LOG}, 3);
    }

    boolean checkSms() {
        return checkSelfPermission(android.Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean checkNotif() {
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean checkCalls() {
        return checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if ((code == 2 || code == 3) && page == 1) show(1);   // refresh chips
    }

    void done(String how) {
        if (finished) return;
        finished = true;
        Ops.setOnboarded(this);
        Log2.line(this, "KeepForDays onboarding: " + how);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (page > 0) show(page - 1);
        else done("skipped");
    }
}
