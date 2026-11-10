package com.messages.keepfordays;

import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** v1.9.0 — Material You scaffold: hand-rolled navigation drawer +
 *  sections (Home / Basic / Advanced / Scheduling / Debug / Help / Logs),
 *  permission banner, skippable onboarding (OnboardingActivity, first
 *  run). Engine logic stays in Ops/TestOps — this file is UI only. */
public class MainActivity extends Activity {

    static final String VER = "1.0.1";

    static final String[] NAV = {"Home", "Basic", "Advanced", "Scheduling",
            "Notifications", "Debug", "Help", "Logs", "About"};

    String current = "Home";

    LinearLayout contentWrap;   // top bar + section host
    LinearLayout drawer;
    View scrim;
    TextView titleView;
    LinearLayout sectionHost;

    LinearLayout homeBannerBox;
    LinearLayout homeBox;
    TextView schedInfo;
    TextView debugInfo;
    TextView logView;
    EditText daysEdit;
    EditText keyEdit;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log2.line(this, "KeepForDays " + VER + " start (material scaffold)");
        try {
            String ins;
            try {
                ins = getPackageManager().getInstallSourceInfo(getPackageName())
                        .getInstallingPackageName();
            } catch (Throwable noSource) {
                ins = getPackageManager().getInstallerPackageName(getPackageName());
            }
            Log2.line(this, "installer: " + ins);
        } catch (Throwable t) {
            Log2.line(this, "installer: unknown (" + t + ")");
        }

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.bg(this));

        // ---- content column ----
        contentWrap = Ui.column(this);
        LinearLayout top = Ui.row(this);
        int p = Ui.dp(this, 12);
        top.setPadding(p, p, p, p);
        Button burger = Ui.tonal(this, "MENU");
        burger.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        });
        top.addView(burger);
        titleView = Ui.headline(this, "Keep For Days");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        titleView.setLayoutParams(tp);
        top.addView(titleView);
        contentWrap.addView(top);

        sectionHost = Ui.column(this);
        sectionHost.setPadding(p, 0, p, p);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(sectionHost);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(contentWrap, Ui.match());

        // ---- scrim + drawer ----
        scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        root.addView(scrim, Ui.match());

        int dw = Math.min(Ui.dp(this, 300),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        drawer = Ui.column(this);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(Ui.surface(this));
        g.setCornerRadii(new float[]{0, 0, Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16), 0, 0});
        drawer.setBackground(g);
        int pd = Ui.dp(this, 16);
        drawer.setPadding(0, pd, 0, pd);
        drawer.setElevation(Ui.dp(this, 8));
        drawer.setTranslationX(-dw);
        TextView h = Ui.headline(this, "Keep For Days");
        h.setPadding(pd, Ui.dp(this, 8), pd, pd);
        TextView v = Ui.small(this, "v" + VER + " — provisioned retention");
        v.setPadding(pd, 0, pd, pd);
        drawer.addView(h);
        drawer.addView(v);
        for (final String n : NAV) {
            drawer.addView(Ui.navItem(this, n, new Runnable() {
                public void run() { select(n); }
            }));
        }
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(spacer);
        TextView foot = Ui.small(this, "SMS role rests with Google"
                + " Messages.\nDefault window: " + Ops.DEFAULT_DAYS
                + " days (overridable).");
        foot.setPadding(pd, Ui.dp(this, 8), pd, Ui.dp(this, 8));
        drawer.addView(foot);
        root.addView(drawer, new android.widget.FrameLayout.LayoutParams(dw,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        select("Home");

        if (!Ops.onboarded(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    void openDrawer() {
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(0.55f).setDuration(180).start();
        drawer.animate().translationX(0).setDuration(180).start();
    }

    void closeDrawer() {
        drawer.animate().translationX(-drawer.getWidth()).setDuration(180).start();
        scrim.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            public void run() { scrim.setVisibility(View.GONE); }
        }).start();
    }

    boolean drawerOpen() {
        return scrim.getVisibility() == View.VISIBLE;
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen()) closeDrawer();
        else super.onBackPressed();
    }

    // ---- section switching ----

    void select(String name) {
        current = name;
        titleView.setText("Keep For Days — " + name);
        Ui.markSelected(this, drawer, name);
        sectionHost.removeAllViews();
        homeBox = null;
        schedInfo = null;
        debugInfo = null;
        logView = null;
        daysEdit = null;
        keyEdit = null;
        if ("Home".equals(name)) buildHome();
        else if ("Basic".equals(name)) buildBasic();
        else if ("Advanced".equals(name)) buildAdvanced();
        else if ("Scheduling".equals(name)) buildScheduling();
        else if ("Notifications".equals(name)) buildNotifications();
        else if ("Debug".equals(name)) buildDebug();
        else if ("Help".equals(name)) buildHelp();
        else if ("Logs".equals(name)) buildLogs();
        else if ("About".equals(name)) buildAbout();
        if (drawerOpen()) closeDrawer();
        refresh();
    }

    // ---- HOME ----

    void buildHome() {
        homeBannerBox = Ui.column(this);
        sectionHost.addView(homeBannerBox);

        LinearLayout pass = Ui.card(this);
        Button run = Ui.filled(this, "RUN PASS NOW");
        run.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { actRunPass(); }
        });
        pass.addView(run);
        pass.addView(Ui.small(this,
                "Deletes provider messages older than the retention window."
                + " Provider-side only; the SMS role never leaves Google"
                + " Messages."));
        sectionHost.addView(pass);

        LinearLayout c = Ui.card(this);
        c.addView(Ui.title(this, "Status"));
        homeBox = Ui.column(this);
        homeBox.addView(Ui.body(this, "loading…"));
        c.addView(homeBox);
        sectionHost.addView(c);

        LinearLayout last = Ui.card(this);
        last.addView(Ui.title(this, "Last result"));
        TextView lv = Ui.body(this, Ops.lastVerdict(this));
        lv.setTextIsSelectable(true);
        last.addView(lv);
        sectionHost.addView(last);
    }

    /** home status + action-needed banner (bg data, ui paint). */
    void buildHomeStatus() {
        if (homeBox == null || homeBannerBox == null
                || homeBox.getParent() == null) return;
        final boolean sms = readSms();
        final boolean calls = readCallLog();
        final boolean provSms = Ops.isProvisioned(this);
        final boolean provCall = Ops.isCallProvisioned(this);
        final int days = Ops.retentionDays(this);
        long cutMs = System.currentTimeMillis() - days * 86400000L;
        final int smsTot = Ops.countRows(this, Telephony.Sms.CONTENT_URI, null, null);
        final int smsOld = Ops.countRows(this, Telephony.Sms.CONTENT_URI,
                "date < ?", new String[]{String.valueOf(cutMs)});
        final int mmsTot = Ops.countRows(this, Telephony.Mms.CONTENT_URI, null, null);
        final int mmsOld = Ops.countRows(this, Telephony.Mms.CONTENT_URI,
                "date < ?", new String[]{String.valueOf(cutMs / 1000L)});
        // READ_CALL_LOG missing → the query throws; countRows reports -2
        // and the banner explains the fix. Never gate sms counts on it.
        final int callTot = calls ? Ops.countRows(this,
                android.provider.CallLog.Calls.CONTENT_URI, null, null) : -2;
        final int callOld = calls ? Ops.countRows(this,
                android.provider.CallLog.Calls.CONTENT_URI, "date < ?",
                new String[]{String.valueOf(cutMs)}) : -2;
        final int tSms = TestOps.testSmsCount(this);
        final int tMms = TestOps.testMmsCount(this);
        final int tCalls = TestOps.testCallCount(this);
        final boolean sched = Ops.scheduleEnabled(this);
        runOnUiThread(new Runnable() {
            public void run() {
                if (homeBox == null || homeBox.getParent() == null) return;
                homeBannerBox.removeAllViews();
                if (!sms || !provSms || (calls && !provCall)) {
                    LinearLayout banner = Ui.card(MainActivity.this);
                    android.graphics.drawable.GradientDrawable d =
                            new android.graphics.drawable.GradientDrawable();
                    d.setCornerRadius(Ui.dp(MainActivity.this, 16));
                    d.setColor(Color.argb(60, Color.red(Ui.danger(MainActivity.this)),
                            Color.green(Ui.danger(MainActivity.this)),
                            Color.blue(Ui.danger(MainActivity.this))));
                    banner.setBackground(d);
                    banner.addView(Ui.title(MainActivity.this, "ACTION NEEDED"));
                    if (!sms) {
                        banner.addView(Ui.body(MainActivity.this,
                                "READ_SMS permission is missing — the app"
                                + " cannot count messages."));
                        Button g = Ui.filled(MainActivity.this,
                                "GRANT SMS PERMISSIONS");
                        g.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                requestPermissions(new String[]{
                                        android.Manifest.permission.READ_SMS,
                                        android.Manifest.permission.RECEIVE_SMS,
                                        android.Manifest.permission.SEND_SMS,
                                        android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                            }
                        });
                        banner.addView(g);
                    }
                    if (!provSms) {
                        banner.addView(Ui.body(MainActivity.this,
                                "sms/mms not provisioned — those passes"
                                        + " refuse and delete nothing. One-time command:"));
                        banner.addView(Ui.mono(MainActivity.this, Ops.PROVISION_CMD));
                        Button cp = Ui.tonal(MainActivity.this, "COPY COMMAND (SMS)");
                        cp.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                Ui.copyToClipboard(MainActivity.this,
                                        Ops.PROVISION_CMD,
                                        "Provisioning command copied");
                            }
                        });
                        banner.addView(cp);
                    }
                    if (calls && !provCall) {
                        banner.addView(Ui.body(MainActivity.this,
                                "calls not provisioned — call-history passes"
                                        + " are SKIPPED (sms/mms unaffected). One-time command:"));
                        banner.addView(Ui.mono(MainActivity.this, Ops.PROVISION_CMD_CALL));
                        Button cp2 = Ui.tonal(MainActivity.this, "COPY COMMAND (CALLS)");
                        cp2.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                Ui.copyToClipboard(MainActivity.this,
                                        Ops.PROVISION_CMD_CALL,
                                        "Provisioning command copied");
                            }
                        });
                        banner.addView(cp2);
                    }
                    if (!calls) {
                        banner.addView(Ui.body(MainActivity.this,
                                "READ_CALL_LOG not granted — call history is"
                                + " not counted or touched (that is the"
                                + " supported opt-out; grant it only if you"
                                + " want call retention)."));
                        Button gc = Ui.filled(MainActivity.this, "GRANT CALL LOG PERMISSION");
                        gc.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                requestPermissions(new String[]{
                                        android.Manifest.permission.READ_CALL_LOG,
                                        android.Manifest.permission.WRITE_CALL_LOG}, 3);
                            }
                        });
                        banner.addView(gc);
                    }
                    homeBannerBox.addView(banner);
                }

                homeBox.removeAllViews();
                LinearLayout chips = Ui.row(MainActivity.this);
                chips.addView(Ui.chip(MainActivity.this,
                        provSms ? "SMS PROVISIONED" : "SMS NOT PROVISIONED",
                        provSms ? Ui.ok(MainActivity.this) : Ui.danger(MainActivity.this),
                        provSms ? Ui.ok(MainActivity.this) : Ui.danger(MainActivity.this)));
                LinearLayout.LayoutParams l2 = Ui.wrap();
                l2.setMargins(Ui.dp(MainActivity.this, 8), 0, 0, 0);
                TextView c1 = Ui.chip(MainActivity.this,
                        provCall ? "CALLS PROVISIONED" : "CALLS NOT PROVISIONED",
                        provCall ? Ui.ok(MainActivity.this) : Ui.textSec(MainActivity.this),
                        provCall ? Ui.ok(MainActivity.this) : Ui.textSec(MainActivity.this));
                c1.setLayoutParams(l2);
                chips.addView(c1);
                LinearLayout.LayoutParams l3 = Ui.wrap();
                l3.setMargins(Ui.dp(MainActivity.this, 8), 0, 0, 0);
                TextView s2 = Ui.chip(MainActivity.this,
                        "SCHEDULE: " + (sched ? "ON" : "off"),
                        sched ? Ui.accent(MainActivity.this) : Ui.textSec(MainActivity.this),
                        Ui.textPri(MainActivity.this));
                s2.setLayoutParams(l3);
                chips.addView(s2);
                homeBox.addView(chips);
                homeBox.addView(Ui.body(MainActivity.this,
                        "window: " + days + "d"
                        + "\nsms: total=" + smsTot + "  older-than-window=" + smsOld
                        + "\nmms: total=" + mmsTot + "  older-than-window=" + mmsOld
                        + "\ncalls: total=" + (callTot < 0 ? "n/a (no permission)" : callTot)
                        + "  older-than-window=" + (callOld < 0 ? "n/a" : callOld)
                        + "\ntest rows (restricted view; provider truth may"
                        + " be higher): sms " + tSms + " / mms " + tMms
                        + " / calls " + tCalls));
            }
        });
    }

    // ---- BASIC ----

    void buildBasic() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "Retention window"));
        card.addView(Ui.small(this, "Default 30 days. Any integer 1–"
                + Ops.MAX_DAYS + " accepted."));
        daysEdit = new EditText(this);
        daysEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        daysEdit.setText(String.valueOf(Ops.retentionDays(this)));
        card.addView(daysEdit, Ui.match());
        Button save = Ui.filled(this, "SAVE RETENTION WINDOW");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int d;
                try {
                    d = Integer.parseInt(daysEdit.getText().toString().trim());
                } catch (Exception e) {
                    d = Ops.retentionDays(MainActivity.this);
                }
                boolean ok = Ops.setRetentionDays(MainActivity.this, d);
                logLine(ok ? "retention window saved: " + d + "d"
                        : "REFUSED: window must be " + Ops.MIN_DAYS + "-"
                        + Ops.MAX_DAYS + "d (any integer)");
                daysEdit.setText(String.valueOf(Ops.retentionDays(MainActivity.this)));
                refresh();
            }
        });
        card.addView(save, Ui.match());
        sectionHost.addView(card);

        LinearLayout k = Ui.card(this);
        k.addView(Ui.title(this, "Home Assistant key"));
        k.addView(Ui.small(this, "Shared secret for the RUN_RETENTION"
                + " broadcast (HA companion). Wrong/missing key = refusal."));
        keyEdit = new EditText(this);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        keyEdit.setText(Ops.haKey(this));
        k.addView(keyEdit, Ui.match());
        Button ks = Ui.filled(this, "SAVE HA KEY");
        ks.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ops.setHaKey(MainActivity.this, keyEdit.getText().toString().trim());
                logLine("HA key saved");
            }
        });
        k.addView(ks, Ui.match());
        sectionHost.addView(k);
    }

    // ---- ADVANCED ----

    void buildAdvanced() {
        LinearLayout prov = Ui.card(this);
        prov.addView(Ui.title(this, "Provisioning (per data type)"));
        prov.addView(Ui.body(this, Ops.provisionStatus(this)));
        prov.addView(Ui.small(this,
                "Each data type has its own write gate and grant; a"
                + " missing pair SKIPS that type's sweep and never blocks"
                + " the others. Not granting call-log access at all is the"
                + " supported \"app never touches calls\" posture."));
        LinearLayout row = Ui.row(this);
        Button cp = Ui.tonal(this, "COPY SMS COMMAND");
        cp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.copyToClipboard(MainActivity.this, Ops.PROVISION_CMD,
                        "Provisioning command copied");
            }
        });
        row.addView(cp);
        Button cp2 = Ui.tonal(this, "COPY CALLS COMMAND");
        LinearLayout.LayoutParams cl = Ui.wrap();
        cl.setMargins(Ui.dp(this, 8), 0, 0, 0);
        cp2.setLayoutParams(cl);
        cp2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.copyToClipboard(MainActivity.this, Ops.PROVISION_CMD_CALL,
                        "Provisioning command copied");
            }
        });
        row.addView(cp2);
        prov.addView(row);
        prov.addView(Ui.small(this,
                "The WRITE_SMS / WRITE_CALL_LOG appops are the providers'"
                + " real write gates — the SMS/dialer roles were only ever"
                + " their usual vehicles. The ops are persistent (survive"
                + " reboots and app updates); if role churn ever stomps"
                + " one, re-run its command."));
        sectionHost.addView(prov);

        LinearLayout ev = Ui.card(this);
        ev.addView(Ui.title(this, "Event log"));
        ev.addView(Ui.small(this, "Append-only, UTC timestamps. Export"
                + " writes a verified copy to Download/ (also viewable"
                + " under Logs)."));
        Button ex = Ui.tonal(this, "EXPORT EVENTS.LOG");
        ex.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { actExport(); }
        });
        ev.addView(ex);
        sectionHost.addView(ev);
    }

    // ---- NOTIFICATIONS ----
    // v1.11.0: per-scenario notification preferences. Defaults chosen so
    // a healthy daily schedule is SILENT (Devin: "I don't want a
    // notification every day on success") while failures and explicit
    // triggers still surface.

    void buildNotifications() {
        sectionHost.removeAllViews();
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.small(this,
                "Defaults: scheduled-run success notifications are OFF"
                + " (a healthy daily pass stays silent). Failures,"
                + " manual/broadcast results, and test verdicts are ON."));
        sectionHost.addView(intro);
        sectionHost.addView(notifCard("Scheduled pass success",
                "Success verdict for the daily scheduled run.",
                "sched_ok"));
        sectionHost.addView(notifCard("Manual / broadcast pass success",
                "Success verdict for RUN PASS NOW and RUN_PASS broadcasts"
                + " (incl. Home Assistant).",
                "run_ok"));
        sectionHost.addView(notifCard("Failures & refusals",
                "Not provisioned, key mismatch, skipped or errored passes.",
                "fail"));
        sectionHost.addView(notifCard("Test actions",
                "Seed/purge verdicts from the Debug section.",
                "test"));
    }

    LinearLayout notifCard(String title, String desc, final String kind) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.title(this, title));
        c.addView(Ui.small(this, desc));
        android.widget.Switch sw = new android.widget.Switch(this);
        final boolean on = Ops.notifEnabled(this, kind);
        sw.setText(on ? "ON" : "OFF");
        sw.setChecked(on);
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = Ops.notifEnabled(MainActivity.this, kind);
                Ops.setNotifEnabled(MainActivity.this, kind, !now);
                logLine("notifications '" + kind + "': "
                        + (!now ? "ON" : "OFF"));
                buildNotifications();
            }
        });
        c.addView(sw);
        return c;
    }

    // ---- ABOUT ----
    // v1.11.0: placeholder section for the future public GitHub release —
    // repo/contact details land here; no internal-only references.

    void buildAbout() {
        sectionHost.removeAllViews();
        LinearLayout head = Ui.card(this);
        head.addView(Ui.headline(this, "Keep For Days"));
        head.addView(Ui.body(this,
                "Automatic message and call-history retention (SMS, MMS,"
                + " RCS, calls) for Android. Deletes messages and calls"
                + " older than your window, daily, on-device. The SMS"
                + " role always rests with your messaging app — this"
                + " app never takes it."));
        sectionHost.addView(head);

        LinearLayout src = Ui.card(this);
        src.addView(Ui.title(this, "Source code"));
        src.addView(Ui.body(this,
                "Open source (MIT). Source, releases, and issue"
                + " tracker:"));
        final String repoUrl = "https://github.com/devinslick/KeepForDays";
        src.addView(Ui.mono(this, repoUrl));
        Button rc = Ui.tonal(this, "COPY REPO URL");
        rc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.copyToClipboard(MainActivity.this, repoUrl,
                        "Repository URL copied");
            }
        });
        src.addView(rc);
        sectionHost.addView(src);

        LinearLayout sec = Ui.card(this);
        sec.addView(Ui.title(this, "Security"));
        sec.addView(Ui.body(this,
                "Fully offline — the app holds no INTERNET permission,"
                + " so it cannot reach the network. Every deletion"
                + " happens on-device: on your schedule, or on demand."));
        sectionHost.addView(sec);

        LinearLayout meta = Ui.card(this);
        meta.addView(Ui.title(this, "This build"));
        meta.addView(Ui.small(this,
                "package " + getPackageName() + " · version " + VER));
        sectionHost.addView(meta);
    }

    // ---- SCHEDULING ----

    void buildScheduling() {
        // v1.10.0 FIX: self-rebuild must REPLACE the card — appending
        // stacked a new "Daily schedule" card on every toggle/save
        // (Devin's "it adds a new daily schedule" was this bug, live).
        sectionHost.removeAllViews();
        schedInfo = null;
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "Daily schedule"));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(Ops.scheduleEnabled(this) ? "ON" : "OFF");
        sw.setChecked(Ops.scheduleEnabled(this));
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean target = !Ops.scheduleEnabled(MainActivity.this);
                boolean ok = Ops.setSchedule(MainActivity.this, target);
                logLine("schedule " + target + " daily "
                        + Ops.fmtHM(Ops.runHour(MainActivity.this),
                                Ops.runMinute(MainActivity.this))
                        + ": " + (ok ? "confirmed" : "NOT confirmed"));
                buildScheduling();
                refresh();
            }
        });
        card.addView(sw);

        // v1.10.2: run time via a TIME PICKER (HH:MM, minutes honored) —
        // picking saves + re-arms immediately (Ops.setRunTime re-arms a
        // live schedule).
        card.addView(Ui.small(this, "Daily run time:"));
        Button tp = Ui.tonal(this, "Run time: " + Ops.fmtHM(
                Ops.runHour(this), Ops.runMinute(this))
                + "  —  TAP TO CHANGE");
        tp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTimePicker(); }
        });
        card.addView(tp, Ui.match());
        schedInfo = Ui.body(this, "loading…");
        card.addView(schedInfo);
        card.addView(Ui.small(this, "A provisioned pass runs even while the"
                + " phone is locked or asleep. Force-stopping the app"
                + " suspends jobs until the app is next launched (OVERDUE"
                + " flag)."));
        sectionHost.addView(card);
    }

    /** v1.10.2: platform time picker for the daily run time (HH:MM). */
    void showTimePicker() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, Ops.runHour(this));
        cal.set(java.util.Calendar.MINUTE, Ops.runMinute(this));
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(
                this,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(android.widget.TimePicker view,
                                          int hourOfDay, int minute) {
                        Ops.setRunTime(MainActivity.this, hourOfDay, minute);
                        logLine("run time saved: " + Ops.fmtHM(
                                Ops.runHour(MainActivity.this),
                                Ops.runMinute(MainActivity.this)));
                        buildScheduling();
                        refresh();
                    }
                },
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE), true);
        dlg.setTitle("Daily run time");
        dlg.show();
    }

    // ---- DEBUG ----

    void buildDebug() {
        LinearLayout warn = Ui.card(this);
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(Ui.dp(this, 16));
        d.setColor(Color.argb(45, Color.red(Ui.accent(this)),
                Color.green(Ui.accent(this)), Color.blue(Ui.accent(this))));
        warn.setBackground(d);
        warn.addView(Ui.title(this, "Synthetic test data only"));
        warn.addView(Ui.small(this, "All rows carry address/number 555-0199,"
                + " tag [KFD-TEST], date 1969 (NEGATIVE epoch). Purge"
                + " criterion is structural: date < 2000-01-01 — real"
                + " messages and calls cannot match."));
        sectionHost.addView(warn);

        LinearLayout act = Ui.card(this);
        act.addView(Ui.title(this, "Test actions"));
        Button s1 = Ui.filled(this, "SEED 100 SMS (1969)");
        s1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest("seed-sms-100"); }
        });
        act.addView(s1, Ui.match());
        Button m1 = Ui.filled(this, "SEED 100 MMS (1969)");
        m1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest("seed-mms-100"); }
        });
        act.addView(m1, Ui.match());
        Button k1 = Ui.filled(this, "SEED 100 CALLS (1969)");
        k1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest("seed-calls-100"); }
        });
        act.addView(k1, Ui.match());
        Button pu = Ui.tonal(this, "PURGE TEST ROWS (date < 2000)");
        pu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest("purge"); }
        });
        act.addView(pu, Ui.match());
        debugInfo = Ui.body(this, "loading…");
        act.addView(debugInfo);
        sectionHost.addView(act);

        LinearLayout th = Ui.card(this);
        th.addView(Ui.title(this, "MMS render thread"));
        long tid = Ops.seedThreadId(this);
        th.addView(Ui.body(this, tid > 0
                ? "ARMED: " + tid + " — MMS seeds render in your messaging app."
                : "NOT ARMED — MMS seeds land threadless and are invisible"
                        + " in your messaging app (provisioned callers cannot"
                        + " allocate threads on this build)."));
        th.addView(Ui.mono(this,
                "adb shell \"content query --uri"
                + " content://mms-sms/threadID?recipient=555-0199\"\n"
                + "adb shell am broadcast --user 0 -n"
                + " com.messages.keepfordays/.BoundaryTestReceiver"
                + " -a com.messages.keepfordays.BTEST_MMSSEED"
                + " --el thread <tid> --ei count 5"));
        th.addView(Ui.small(this, "The broadcast also arms the thread into"
                + " prefs (one-time per device; the husk survives purges)."));
        sectionHost.addView(th);

        LinearLayout bc = Ui.card(this);
        bc.addView(Ui.title(this, "Boundary-tester broadcasts (adb)"));
        bc.addView(Ui.mono(this,
                "BTEST_SEED     --es tag T1 --ei count 2111\n"
                + "BTEST_DELBATCH --ei count K [--es table mms --el thread T]\n"
                + "BTEST_COUNT    --es addr 5550100\n"
                + "BTEST_LIST     --ei limit 200\n"
                + "RUN_PASS       (RetentionReceiver, no key)"));
        bc.addView(Ui.small(this, "Explicit-component only; provisioned"
                + "-gated. Deliver to"
                + " com.messages.keepfordays/.BoundaryTestReceiver."));
        sectionHost.addView(bc);
    }

    // ---- HELP ----

    void buildHelp() {
        helpCard("What this app does",
                "Deletes messages — SMS, MMS, and RCS — and call history"
                + " older than your retention window (default "
                + Ops.DEFAULT_DAYS + " days), daily at your scheduled"
                + " time, and on demand. Deletes are provider-side and"
                + " propagate to Google Messages' own database (and the"
                + " Phone app's call log), so the on-device history"
                + " always matches the window you chose. iPhone has"
                + " shipped this exact feature for years — Keep Messages"
                + " (30 days / 1 year / Forever) in Settings > Messages"
                + " — and over a billion active iPhones include it,"
                + " whether their owners know it or not. This brings"
                + " the same retention choice to Android.");
        helpCard("One-time provisioning",
                "Each data type has its own write gate, granted by one"
                + " adb command each:\n"
                + Ops.PROVISION_CMD + "\n"
                + Ops.PROVISION_CMD_CALL + "\n\nWithout provisioning"
                + " every pass refuses or skips loudly and deletes nothing"
                + " (safe by default).");
        helpCard("Permissions",
                "READ_SMS (+ receive/send) and READ_CALL_LOG are standard"
                + " runtime grants. There is no WRITE_SMS or WRITE_CALL_LOG"
                + " runtime permission — the write privileges are the appops"
                + " above, granted via adb only, because no user-facing"
                + " Android surface exposes them. Provisioning is per-type:"
                + " never granting READ_CALL_LOG means the app never touches"
                + " call history — a fully supported opt-out.");
        helpCard("Call history",
                "The call sweep covers the same window as messages,"
                + " including voicemail entries (they are rows in the same"
                + " call-log table). Incoming, outgoing, and missed calls"
                + " all age out together. Deleting a call-log row removes"
                + " it from the Phone app's history too — both read the"
                + " same provider. The dialer role always rests with your"
                + " Phone app — this app never takes it.");
        helpCard("RCS",
                "SMS and MMS work with any messaging app — they all read"
                + " the same Android message provider this app manages"
                + " (Google Messages is simply the most common default in"
                + " the US). RCS chats exist only in Google Messages on"
                + " Android today; they ride a relay — the carrier's own"
                + " RCS service where it still runs one, otherwise Jibe,"
                + " Google's RCS platform — which only queues messages"
                + " for delivery and deletes them once delivered. Chats"
                + " between Google Messages users are end-to-end"
                + " encrypted by default, and conversations sync to the"
                + " on-device provider, so provider-side passes cover RCS"
                + " through the same process as SMS/MMS.");
        helpCard("Safety invariants",
                "Retention window 1–" + Ops.MAX_DAYS + " days (default "
                + Ops.DEFAULT_DAYS + ", out-of-range refused). The SMS"
                + " role always rests with your messaging app. No network"
                + " permission. No accessibility service. No wireless"
                + " debugging. No messaging-app UI automation.");
        helpCard("Scheduling",
                "Armed daily jobs run even when the phone is locked or"
                + " asleep at your scheduled time. Force-stopping the app"
                + " suspends jobs until the app is next launched — the"
                + " Scheduling screen shows an OVERDUE flag when that"
                + " happens.");
        helpCard("Home Assistant — overview",
                "Home Assistant can trigger a retention pass remotely"
                + " (automations, buttons, voice). The pass runs the same"
                + " provisioned engine as RUN PASS NOW — locked/asleep is"
                + " fine — and posts the verdict as a notification. The"
                + " only gate is a shared secret you set here; a wrong or"
                + " missing key is refused loudly and deletes nothing.");
        helpCard("Home Assistant — setup (app side)",
                "1. Set the shared key: sidebar > Basic > Home Assistant"
                + " key > SAVE HA KEY.\n"
                + "2. Keep that key secret — anyone who can deliver a"
                + " notification to this phone WITH the key can trigger a"
                + " pass (it deletes messages).\n"
                + "3. This app must stay provisioned (see Advanced); an"
                + " unprovisioned pass refuses, deleting nothing.");
        helpCard("Home Assistant — firing the pass (HA side)",
                "Requires the HA companion app on this phone. Find the"
                + " exact service name in HA: Developer Tools > Actions,"
                + " search notify.mobile_app_ (current HA has no legacy"
                + " notify.<name> services — a bare notify.<name> call"
                + " fails with Action not found). Then call it with the"
                + " broadcast command:");
        LinearLayout haYaml = Ui.card(this);
        // v1.12.1: single source of truth — the copy button ships the
        // exact text the card displays.
        final String haCall =
                "service: notify.mobile_app_<name>\n"
                + "data:\n"
                + "  message: command_broadcast_intent\n"
                + "  data:\n"
                + "    intent_action: >-\n"
                + "      com.messages.keepfordays.action.RUN_RETENTION\n"
                + "    intent_package_name: com.messages.keepfordays\n"
                + "    intent_extras: \"key:<YOUR_SHARED_KEY>\"";
        haYaml.addView(Ui.mono(this, haCall));
        Button haCopy = Ui.tonal(this, "COPY SERVICE CALL");
        haCopy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Ui.copyToClipboard(MainActivity.this, haCall,
                        "Service call copied — edit <your_device>"
                        + " and <YOUR_SHARED_KEY>");
            }
        });
        haYaml.addView(haCopy);
        sectionHost.addView(haYaml);
        helpCard("Home Assistant — notes & troubleshooting",
                "intent_package_name is REQUIRED by the companion app (it"
                + " makes the broadcast explicit); intent_extras items are"
                + " comma-separated name:value pairs — here exactly one,"
                + " the key. If nothing happens: check Logs for 'ha:"
                + " REFUSED — key mismatch' (fix the key) or 'ha: refused"
                + " — no key configured' (set it under Basic); confirm the"
                + " companion app is current (command_broadcast_intent"
                + " support); an unprovisioned pass posts a refusal"
                + " notification instead of deleting.");
        helpCard("Notifications",
                "Configure when the app posts notifications (sidebar >"
                + " Notifications). Scheduled-run success alerts are OFF"
                + " by default — a healthy daily pass stays silent;"
                + " failures, manual/broadcast results, and test verdicts"
                + " are ON. Suppressed notifications are still written to"
                + " the event log.");
    }

    void helpCard(String t, String b) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.title(this, t));
        c.addView(Ui.body(this, b));
        sectionHost.addView(c);
    }

    // ---- LOGS ----

    void buildLogs() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "events.log (last 300 lines)"));
        Button r = Ui.tonal(this, "REFRESH");
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadLog(); }
        });
        Button ex = Ui.tonal(this, "EXPORT");
        ex.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { actExport(); }
        });
        LinearLayout br = Ui.row(this);
        br.addView(r);
        LinearLayout.LayoutParams bl = Ui.wrap();
        bl.setMargins(Ui.dp(this, 8), 0, 0, 0);
        ex.setLayoutParams(bl);
        br.addView(ex);
        card.addView(br);
        logView = Ui.mono(this, "loading…");
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 420)));
        sectionHost.addView(card);
        loadLog();
    }

    void loadLog() {
        if (logView == null) return;
        final TextView lv = logView;
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    File f = new File(getFilesDir(), "events.log");
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.FileReader(f));
                    java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
                    String line;
                    while ((line = r.readLine()) != null) lines.add(line);
                    r.close();
                    int from = Math.max(0, lines.size() - 300);
                    StringBuilder sb = new StringBuilder();
                    for (int i = from; i < lines.size(); i++) {
                        sb.append(lines.get(i)).append('\n');
                    }
                    out = sb.length() == 0 ? "(empty)" : sb.toString();
                } catch (Throwable t) {
                    out = "read failed: " + t;
                }
                final String fout = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (lv.getParent() == null) return;
                        lv.setText(fout);
                    }
                });
            }
        }).start();
    }

    // ---- shared plumbing ----

    boolean readSms() {
        try {
            return checkSelfPermission(android.Manifest.permission.READ_SMS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean readCallLog() {
        try {
            return checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    void logLine(final String m) {
        Log2.line(this, m);
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    void bg(Runnable r) {
        new Thread(r).start();
    }

    void actRunPass() {
        bg(new Runnable() {
            public void run() {
                String verdict = Ops.deletePass(MainActivity.this, "panel");
                Ops.setLastVerdict(MainActivity.this, "panel: " + verdict);
                // v1.11.0: panel success follows the run_ok preference.
                Ops.notifyKind(MainActivity.this, "run_ok",
                        "Retention pass complete (panel)", verdict);
                refresh();
            }
        });
    }

    void runTest(final String action) {
        bg(new Runnable() {
            public void run() {
                // v1.12.0: per-type — TestOps refuses per action (an
                // SMS seed needs SMS provisioning, a call seed needs
                // call provisioning); a central all-types gate would
                // wrongly block call tests on an sms-only device.
                if (!Ops.isProvisioned(MainActivity.this)
                        && !Ops.isCallProvisioned(MainActivity.this)) {
                    logLine("test REFUSED — not provisioned ("
                            + Ops.PROVISION_CMD + " / " + Ops.PROVISION_CMD_CALL + ")");
                    return;
                }
                String verdict = TestOps.run(MainActivity.this, action);
                Ops.setLastVerdict(MainActivity.this, "test: " + verdict);
                // v1.11.0: test verdicts follow the test preference.
                Ops.notifyKind(MainActivity.this, "test",
                        "Test action: " + action, verdict);
                refresh();
            }
        });
    }

    void actExport() {
        bg(new Runnable() {
            public void run() {
                try {
                    File f = new File(getFilesDir(), "events.log");
                    long size = f.length();
                    String name = "keep-for-days-events-"
                            + System.currentTimeMillis() + ".log";
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri u = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) {
                        logLine("export: insert failed");
                        return;
                    }
                    InputStream in = new FileInputStream(f);
                    OutputStream os = getContentResolver().openOutputStream(u);
                    byte[] buf = new byte[8192];
                    int n;
                    long written = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written += n;
                    }
                    os.flush();
                    os.close();
                    in.close();
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(u, done, null, null);
                    InputStream check = getContentResolver().openInputStream(u);
                    long bytes = 0;
                    while ((n = check.read(buf)) > 0) bytes += n;
                    check.close();
                    boolean verified = bytes == size;
                    Log2.line(MainActivity.this, "export: " + written
                            + " bytes to Download/" + name
                            + " verified=" + verified);
                    logLine("exported " + written + " bytes (verified="
                            + verified + ")");
                } catch (Throwable t) {
                    Log2.line(MainActivity.this, "export failed: " + t);
                    logLine("export failed: " + t);
                }
            }
        });
    }

    boolean jobScheduled() {
        try {
            JobScheduler js =
                    (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            return js != null && js.getPendingJob(Ops.JOB_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** bg status sweep; paints whichever section views exist. */
    void refresh() {
        bg(new Runnable() {
            public void run() {
                try {
                    if ("Home".equals(current)) buildHomeStatus();
                    final boolean sched = Ops.scheduleEnabled(MainActivity.this);
                    final boolean job = jobScheduled();
                    final long nextRun = Ops.armedForMs(MainActivity.this);
                    final long lastFire = Ops.lastFireMs(MainActivity.this);
                    final int runH = Ops.runHour(MainActivity.this);
                    final boolean firedLate = nextRun > 0
                            && System.currentTimeMillis() > nextRun + 2L * 3600000L;
                    final long tid = Ops.seedThreadId(MainActivity.this);
                    final int tSms = TestOps.testSmsCount(MainActivity.this);
                    final int tMms = TestOps.testMmsCount(MainActivity.this);
                    final int tCalls = TestOps.testCallCount(MainActivity.this);
                    final StringBuilder schedS = new StringBuilder();
                    schedS.append(sched ? "ON — daily "
                            + String.format(java.util.Locale.US, "%02d:00", runH)
                            : "OFF");
                    schedS.append(sched ? (job ? " (job pending)" : " (JOB MISSING)") : "");
                    schedS.append(sched ? "\nnext run armed for: "
                            + Ops.fmtTime(nextRun) : "");
                    schedS.append(sched && firedLate
                            ? "\nOVERDUE >2h (job suspended? force-stopped?)" : "");
                    schedS.append(lastFire > 0
                            ? "\nlast scheduled fire: " + Ops.fmtTime(lastFire) : "");
                    schedS.append("\nprovisioned passes run locked/asleep —"
                            + " no screen, no Wi-Fi needed.");
                    final StringBuilder dbg = new StringBuilder();
                    dbg.append("test rows in restricted view: sms ").append(tSms)
                       .append(" / mms ").append(tMms)
                       .append(" / calls ").append(tCalls)
                       .append(" (self-seeded rows are query-invisible;")
                       .append(" delete ret values are the truth)\n");
                    dbg.append("mms render thread: ")
                       .append(tid > 0 ? "ARMED " + tid : "NOT ARMED");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (schedInfo != null && schedInfo.getParent() != null) {
                                schedInfo.setText(schedS.toString());
                            }
                            if (debugInfo != null && debugInfo.getParent() != null) {
                                debugInfo.setText(dbg.toString());
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log2.line(MainActivity.this, "refresh failed: " + t);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sectionHost != null) refresh();
    }
}
