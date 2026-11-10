package com.messages.keepfordays;

import android.content.BroadcastReceiver;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import java.util.ArrayList;

/**
 * TESTBED-ONLY boundary tester (bulk-delete divergence work; see the
 * repo README's asymmetry section). Maps the batch size at which a
 * provider-side delete stops propagating to Google Messages'
 * bugle_db/UI.
 *
 * All operations target ONE synthetic test thread (addr extra; default
 * 5550100) with rows dated >450d old — Devin's floor forbids touching
 * anything more recent, and no real rows are ever in scope (selections
 * are addr-scoped AND date-scoped).
 *
 * Explicit-component broadcasts only (no intent-filter). The gate is
 * PROVISIONING (v1.8.0): without the WRITE_SMS appop every write
 * silently no-ops at the provider, so these refuse loudly instead
 * (grant: adb shell appops set com.messages.keepfordays WRITE_SMS allow).
 * No role flips are needed anymore — nothing to orchestrate around.
 *
 *   SEED     --es addr 5550100 --ei count 2111 --es tag T1
 *       inserts count rows: body "KFDTEST <tag> s<seq> z<4digits>",
 *       type alternating 1/2, dates 500-600d back (all >450d), read/seen 1.
 *       Chunked applyBatch (200/op) with silent-no-op detection; verdict =
 *       attempted/inserted/re-queried.
 *   DELBATCH --es addr 5550100 --ei count K
 *       picks the K OLDEST rows of the addr (ORDER BY date LIMIT K) and
 *       deletes them in exactly ONE provider delete() call
 *       (_id IN (...)) — batch size K is the variable under test.
 *       Verdict = ret value + re-query truth + the _id range.
 *   COUNT    --es addr 5550100
 *       provider count for the addr + grand totals.
 */
public class BoundaryTestReceiver extends BroadcastReceiver {

    public static final String ACTION_SEED = "com.messages.keepfordays.BTEST_SEED";
    public static final String ACTION_DELBATCH = "com.messages.keepfordays.BTEST_DELBATCH";
    public static final String ACTION_COUNT = "com.messages.keepfordays.BTEST_COUNT";
    public static final String ACTION_LIST = "com.messages.keepfordays.BTEST_LIST";
    public static final String ACTION_MMSSEED = "com.messages.keepfordays.BTEST_MMSSEED";

    private static final long DAY_MS = 86400000L;

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_SEED.equals(action) || ACTION_DELBATCH.equals(action)
                || ACTION_COUNT.equals(action) || ACTION_LIST.equals(action)
                || ACTION_MMSSEED.equals(action)) {
            final PendingResult pr = goAsync();
            final Intent fi = intent;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!Ops.isProvisioned(context)) {
                            Log2.line(context, "BTEST REFUSED — not provisioned"
                                    + " (" + Ops.PROVISION_CMD + "); nothing done");
                            return;
                        }
                        if (ACTION_SEED.equals(action)) seed(context, fi);
                        else if (ACTION_DELBATCH.equals(action)) delBatch(context, fi);
                        else if (ACTION_LIST.equals(action)) list(context, fi);
                        else if (ACTION_MMSSEED.equals(action)) mmsSeed(context, fi);
                        else count(context, fi);
                    } catch (Throwable t) {
                        Log2.line(context, "BTEST crashed: " + t);
                    } finally {
                        pr.finish();
                    }
                }
            }).start();
        }
    }

    private static String addr(Intent i) {
        String a = i.getStringExtra("addr");
        return a == null || a.length() == 0 ? "5550100" : a;
    }

    // ---- MMS seed with shell-resolved thread (v1.8.1) -------------------

    /**
     * Provisioned callers CANNOT allocate threads on this build (live
     * 2026-11-06): the threadID URI is caller-gated (shell resolves it,
     * apps get an empty cursor -> "Unable to find or allocate a thread
     * ID"), the provider's own address-key path throws an identity bug
     * ("Package com.android.phone does not belong to <uid>"), and
     * thread_id updates no-op for non-default callers. Threadless mms
     * rows are invisible in Google Messages (no conversation). Resolve a
     * thread ONCE via shell and arm it here:
     *
     *   adb shell "content query --uri content://mms-sms/threadID?recipient=555-0199"
     *   am broadcast --user 0 -n com.messages.keepfordays/.BoundaryTestReceiver \
     *     -a com.messages.keepfordays.BTEST_MMSSEED --el thread 3915 --ei count 5
     *
     * Rows are pre-2000-dated (purge-cleanable). The restricted view
     * hides self-seeded rows from the app — verify via shell, purge via
     * the panel button (raw WHERE) or BTEST-free TestOps purge.
     */
    private void mmsSeed(Context c, Intent i) {
        long thread = i.getLongExtra("thread", -1L);
        int n = i.getIntExtra("count", 5);
        if (thread <= 0) {
            Log2.line(c, "BTEST MMSSEED REFUSED — no/--el thread <tid>"
                    + " (shell-resolve content://mms-sms/threadID?recipient=555-0199)");
            return;
        }
        if (n < 1 || n > 10000) {
            Log2.line(c, "BTEST MMSSEED REFUSED — count must be 1..10000 (got " + n + ")");
            return;
        }
        Log2.line(c, "BTEST MMSSEED start: thread=" + thread + " count=" + n);
        Ops.setSeedThreadId(c, thread);   // v1.8.2: arm the panel path too
        String v = TestOps.seedMmsThread(c, n, thread);
        Log2.line(c, "BTEST MMSSEED verdict: " + v);
    }

    // ---- COUNT ----------------------------------------------------------

    private void count(Context c, Intent i) {
        String a = addr(i);
        Log2.line(c, "BTEST count: addr=" + a + " rows=" + countAddr(c, a)
                + " | smsTotal=" + countSel(c, Telephony.Sms.CONTENT_URI, null, null)
                + " mmsTotal=" + countSel(c, Telephony.Mms.CONTENT_URI, null, null));
    }

    static int countAddr(Context c, String a) {
        return countSel(c, Telephony.Sms.CONTENT_URI, "address=?",
                new String[]{a});
    }

    static int countSel(Context c, Uri uri, String sel, String[] args) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{"_id"}, sel, args, null);
            return cur == null ? -1 : cur.getCount();
        } catch (Throwable t) {
            Log2.line(c, "BTEST count failed: " + t);
            return -2;
        } finally {
            if (cur != null) cur.close();
        }
    }

    // ---- SEED -----------------------------------------------------------

    private void seed(Context c, Intent i) {
        final String a = addr(i);
        String tag = i.getStringExtra("tag");
        if (tag == null || tag.length() == 0) tag = "T1";
        int count = i.getIntExtra("count", 0);
        if (count < 1 || count > 20000) {
            Log2.line(c, "BTEST SEED REFUSED — count must be 1..20000 (got " + count + ")");
            return;
        }
        Log2.line(c, "BTEST SEED start: addr=" + a + " count=" + count + " tag=" + tag);
        long t0 = System.currentTimeMillis();
        long base = t0 - 500L * DAY_MS;             // 500d back …
        long span = 100L * DAY_MS;                  // … spread over 100d (all >450d old)
        java.util.Random rnd = new java.util.Random(4711L);
        final String fTag = tag;
        final long fBase = base, fSpan = span, fCount = count;
        int inserted = 0, failed = 0;
        final int CHUNK = 200;
        for (int i0 = 0; i0 < count; i0 += CHUNK) {
            int end = Math.min(i0 + CHUNK, count);
            ArrayList<ContentProviderOperation> ops =
                    new ArrayList<ContentProviderOperation>();
            for (int j = i0; j < end; j++) {
                ops.add(ContentProviderOperation
                        .newInsert(Telephony.Sms.CONTENT_URI)
                        .withValues(rowValues(fTag, fBase, fSpan, fCount,
                                j, rnd.nextInt(9000)))
                        .build());
            }
            try {
                c.getContentResolver().applyBatch("sms", ops);
                inserted += end - i0;
            } catch (Throwable t) {
                Log2.line(c, "BTEST SEED chunk " + i0 + ".." + (end - 1)
                        + " applyBatch failed (" + t + ") — per-row retry");
                for (int j = i0; j < end; j++) {
                    try {
                        Uri u = c.getContentResolver().insert(Telephony.Sms.CONTENT_URI,
                                rowValues(fTag, fBase, fSpan, fCount, j, rnd.nextInt(9000)));
                        if (u == null || "0".equals(u.getLastPathSegment())) {
                            failed++;        // provider silent no-op — never trust the ret
                        } else {
                            inserted++;
                        }
                    } catch (Throwable ignore) {
                        failed++;
                    }
                }
            }
        }
        int q = countAddr(c, a);
        Log2.line(c, "BTEST SEED done: attempted=" + count + " ok=" + inserted
                + " failed=" + failed + " requery=" + q + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        if (q != count) {
            Log2.line(c, "BTEST SEED WARNING — requery != attempted; do NOT proceed"
                    + " with DELBATCH until this is understood");
        }
    }

    private static ContentValues rowValues(String tag, long base, long span,
                                           long count, int j, int z) {
        long date = base + (long) (span * (j / (double) count));
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, "5550100");
        v.put(Telephony.Sms.PERSON, (String) null);
        v.put(Telephony.Sms.DATE, date);
        v.put(Telephony.Sms.DATE_SENT, date);
        v.put(Telephony.Sms.TYPE, (j % 2 == 0) ? Telephony.Sms.MESSAGE_TYPE_INBOX
                : Telephony.Sms.MESSAGE_TYPE_SENT);
        v.put(Telephony.Sms.BODY, "KFDTEST " + tag + " s" + j + " z" + (1000 + z));
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        v.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE);
        return v;
    }

    // ---- DELBATCH -------------------------------------------------------

    /** v2: --es table sms|mms (default sms); scope by --el thread <thread_id>
     *  (both tables) or --es addr <a> (sms only — mms has no address column
     *  in its main table). Deletes exactly K OLDEST matching rows in ONE
     *  provider delete() call — batch size K is the variable under test. */
    private void delBatch(Context c, Intent i) {
        String table = i.getStringExtra("table");
        boolean isMms = "mms".equals(table);
        android.net.Uri uri = isMms ? Telephony.Mms.CONTENT_URI
                : Telephony.Sms.CONTENT_URI;
        String scopeSel;
        String[] scopeArgs;
        long thread = i.getLongExtra("thread", -1L);
        String a = addr(i);
        if (thread > 0) {
            scopeSel = "thread_id=?";
            scopeArgs = new String[]{String.valueOf(thread)};
        } else if (isMms) {
            Log2.line(c, "BTEST DELBATCH REFUSED — mms needs --el thread (no addr col)");
            return;
        } else {
            scopeSel = "address=?";
            scopeArgs = new String[]{a};
        }
        int k = i.getIntExtra("count", 0);
        if (k < 1 || k > 20000) {
            Log2.line(c, "BTEST DELBATCH REFUSED — count must be 1..20000 (got " + k + ")");
            return;
        }
        int have = countSel(c, uri, scopeSel, scopeArgs);
        if (k > have) {
            Log2.line(c, "BTEST DELBATCH REFUSED — count " + k + " > rows present " + have);
            return;
        }
        ArrayList<Long> ids = new ArrayList<Long>();
        ArrayList<String> bodies = new ArrayList<String>();
        Cursor cur = null;
        String[] proj = isMms ? new String[]{"_id"} : new String[]{"_id", "body"};
        try {
            cur = c.getContentResolver().query(uri,
                    proj, scopeSel,
                    scopeArgs, "date ASC, _id ASC LIMIT " + k);
            while (cur.moveToNext()) {
                ids.add(cur.getLong(0));
                if (!isMms) bodies.add(cur.getString(1));
            }
        } catch (Throwable t) {
            Log2.line(c, "BTEST DELBATCH select failed: " + t);
            return;
        } finally {
            if (cur != null) cur.close();
        }
        if (ids.size() != k) {
            Log2.line(c, "BTEST DELBATCH REFUSED — selected " + ids.size() + " != " + k);
            return;
        }
        StringBuilder sb = new StringBuilder("_id IN (");
        for (int j = 0; j < ids.size(); j++) {
            if (j > 0) sb.append(',');
            sb.append(ids.get(j));
        }
        sb.append(')');
        Log2.line(c, "BTEST DELBATCH: table=" + (isMms ? "mms" : "sms") + " scope=" + scopeSel
                + ":" + scopeArgs[0] + " K=" + k + " ids " + ids.get(0) + ".."
                + ids.get(ids.size() - 1)
                + (isMms ? "" : " first=[" + bodies.get(0)
                + "] last=[" + bodies.get(bodies.size() - 1) + "]"));
        int before = countSel(c, uri, scopeSel, scopeArgs);
        int ret;
        try {
            ret = c.getContentResolver().delete(uri,
                    sb.toString() + " AND " + scopeSel, scopeArgs);
        } catch (Throwable t) {
            Log2.line(c, "BTEST DELBATCH delete THREW: " + t);
            return;
        }
        int after = countSel(c, uri, scopeSel, scopeArgs);
        Log2.line(c, "BTEST DELBATCH verdict: K=" + k + " ret=" + ret
                + " before=" + before + " after=" + after
                + " truthDeleted=" + (before - after));
    }

    // ---- LIST (provider inventory) --------------------------------------

    /** Dumps the newest `limit` rows of each table with the columns that
     *  matter for RCS-mirror identification (thread_id, sub_id, address,
     *  type). MMS addresses live in a per-id addr URI — dumped for the
     *  first 40 mms rows only. */
    private void list(Context c, Intent i) {
        int limit = i.getIntExtra("limit", 200);
        if (limit < 1 || limit > 1000) limit = 200;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                    null, null, null, "date DESC LIMIT " + limit);
            int n = 0;
            while (cur != null && cur.moveToNext()) {
                if (n == 0) {
                    StringBuilder cn = new StringBuilder("BTEST LIST sms cols:");
                    for (int j = 0; j < cur.getColumnCount() && j < 24; j++) {
                        cn.append(' ').append(cur.getColumnName(j));
                    }
                    Log2.line(c, cn.toString());
                }
                long id = cur.getLong(cur.getColumnIndexOrThrow("_id"));
                long tid = cur.getLong(cur.getColumnIndexOrThrow("thread_id"));
                String date = cur.getString(cur.getColumnIndexOrThrow("date"));
                String addrC = cur.getString(cur.getColumnIndexOrThrow("address"));
                int sub = cur.getInt(cur.getColumnIndex("sub_id"));
                int type = cur.getInt(cur.getColumnIndexOrThrow("type"));
                String body = cur.getString(cur.getColumnIndexOrThrow("body"));
                if (body != null && body.length() > 28) {
                    body = body.substring(0, 28);
                }
                Log2.line(c, "BTEST sms id=" + id + " t=" + tid + " d=" + date
                        + " sub=" + sub + " ty=" + type + " a=" + addrC
                        + " b=[" + body + "]");
                n++;
            }
            Log2.line(c, "BTEST LIST sms rows dumped: " + n);
        } catch (Throwable t) {
            Log2.line(c, "BTEST LIST sms failed: " + t);
        } finally {
            if (cur != null) cur.close();
        }
        try {
            cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                    null, null, null, "date DESC LIMIT " + limit);
            int n = 0;
            while (cur != null && cur.moveToNext()) {
                if (n == 0) {
                    StringBuilder cn = new StringBuilder("BTEST LIST mms cols:");
                    for (int j = 0; j < cur.getColumnCount() && j < 24; j++) {
                        cn.append(' ').append(cur.getColumnName(j));
                    }
                    Log2.line(c, cn.toString());
                }
                long id = cur.getLong(cur.getColumnIndexOrThrow("_id"));
                long tid = cur.getLong(cur.getColumnIndexOrThrow("thread_id"));
                String date = cur.getString(cur.getColumnIndexOrThrow("date"));
                int mtype = cur.getInt(cur.getColumnIndex("m_type"));
                int mbox = cur.getInt(cur.getColumnIndex("msg_box"));
                int sub = cur.getInt(cur.getColumnIndex("sub_id"));
                Log2.line(c, "BTEST mms id=" + id + " t=" + tid + " d=" + date
                        + " sub=" + sub + " m_type=" + mtype + " msg_box=" + mbox
                        + (n < 40 ? " addr=" + mmsAddr(c, id) : ""));
                n++;
            }
            Log2.line(c, "BTEST LIST mms rows dumped: " + n);
        } catch (Throwable t) {
            Log2.line(c, "BTEST LIST mms failed: " + t);
        } finally {
            if (cur != null) cur.close();
        }
    }

    private static String mmsAddr(Context c, long id) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    android.net.Uri.parse("content://mms/" + id + "/addr"),
                    new String[]{"address"}, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Throwable ignore) {
        } finally {
            if (cur != null) cur.close();
        }
        return "?";
    }
}
