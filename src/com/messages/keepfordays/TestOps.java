package com.messages.keepfordays;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;

import java.util.ArrayList;

/**
 * v1.7.0 TEST DATA — synthetic messages for exercising the retention
 * pass against real volume (Devin, 2026-11-05: "we've already deleted
 * all of the messages I'm comfortable deleting").
 *
 * DISCRIMINATORS — every synthetic row carries ALL THREE, and the
 * purge criterion is STRUCTURAL (no real message can predate 2000):
 *   1. address 555-0199 (reserved fictional range, groups as one thread)
 *   2. body tagged [KFD-TEST ...] / m_type test marker
 *   3. date = 1969-01-01 UTC (NEGATIVE epoch ms) — falls back to
 *      1970-01-02 (+86400000) if the provider rejects negatives;
 *      purge/counts use "date < 2000-01-01" so BOTH bases are covered.
 *
 * PRIVILEGES (v1.8.0): writes need the WRITE_SMS appop — provisioning
 * makes seed/purge/run direct, dance-free, any time (no role window:
 * the app never holds the SMS role at all now). v1.12.0: call seeds
 * need their own pair (WRITE_CALL_LOG appop + READ_CALL_LOG);
 * provisioning is per-type — an SMS skip never blocks a call seed.
 *
 * SAFETY: purge touches ONLY rows dated before 2000-01-01 UTC. No
 * real message can carry such a date (SMS did not exist until 1992;
 * this device's real traffic is 2023+). Seed inserts never update or
 * delete anything. The retention pass itself will remove seeded rows
 * on its next run — that is the point (end-to-end test of deletePass).
 */
final class TestOps {

    private TestOps() { }

    static final String TEST_ADDR = "555-0199";
    static final String TEST_TAG = "[KFD-TEST]";
    /** 1969-01-01T00:00:00Z in ms — negative on purpose. */
    static final long DATE_1969_MS = -31536000000L;
    /** 1970-01-02T00:00:00Z — positive fallback if negatives are rejected. */
    static final long DATE_1970_MS = 86400000L;
    /** Purge/count cutoff: 2000-01-01T00:00:00Z. SMS ms / MMS s.
     *  CallLog dates are ms — same constant as SMS applies. */
    static final long CUT_SMS_MS = 946684800000L;
    static final long CUT_MMS_S = 946684800L;
    static final long CUT_CALL_MS = CUT_SMS_MS;

    // ---- counts (READ_SMS / READ_CALL_LOG; mirrors Ops.countRows) ----

    static int testSmsCount(Context c) {
        return Ops.countRows(c, Telephony.Sms.CONTENT_URI,
                "date < ?", new String[]{String.valueOf(CUT_SMS_MS)});
    }

    static int testMmsCount(Context c) {
        return Ops.countRows(c, Telephony.Mms.CONTENT_URI,
                "date < ?", new String[]{String.valueOf(CUT_MMS_S)});
    }

    /** v1.12.0. Returns -2 quietly when READ_CALL_LOG is missing —
     *  every refresh tick calls this, and a thrown SecurityException
     *  here would spam events.log for opt-out users. The banner
     *  explains the grant; no per-tick log line. */
    static int testCallCount(Context c) {
        if (!Ops.readCallLogGranted(c)) return -2;
        return Ops.countRows(c, android.provider.CallLog.Calls.CONTENT_URI,
                "date < ?", new String[]{String.valueOf(CUT_CALL_MS)});
    }

    // ---- the action dispatcher (provisioned mode; no role window) ----

    /** Runs the test action. Returns the verdict string. */
    static String run(Context c, String action) {
        long t0 = System.currentTimeMillis();
        try {
            if ("seed-sms-100".equals(action)) return seedSms(c, 100, t0);
            if ("seed-sms-1000".equals(action)) return seedSms(c, 1000, t0);
            if ("seed-mms-100".equals(action)) return seedMms(c, 100, t0);
            if ("seed-mms-1000".equals(action)) return seedMms(c, 1000, t0);
            if ("seed-calls-100".equals(action)) return seedCalls(c, 100, t0);
            if ("seed-calls-1000".equals(action)) return seedCalls(c, 1000, t0);
            if ("purge".equals(action)) return purge(c, t0);
            return "unknown test action: " + action;
        } catch (Throwable t) {
            return "test action '" + action + "' CRASHED: " + t
                    + " — partial rows may remain (PURGE cleans them)";
        }
    }

    // ---- seeding ----

    static String seedSms(Context c, int n, long t0) throws Exception {
        if (!Ops.isProvisioned(c)) {
            return "SKIP: not provisioned (" + Ops.PROVISION_CMD + ") — nothing seeded";
        }
        long base = DATE_1969_MS;
        int ok = 0;
        Throwable negFail = null;
        try {
            ok = insertSmsBatch(c, n, base);
        } catch (Throwable t) {
            negFail = t;
        }
        String note = "";
        if (negFail != null) {
            Log2.line(c, "seed: 1969 (negative ms) rejected: " + negFail
                    + " — retrying at 1970-01-02 (+86400000)");
            base = DATE_1970_MS;
            ok = insertSmsBatch(c, n, base);
            note = " (1969 negative rejected — used 1970 base)";
        }
        String v = "seeded sms " + ok + "/" + n + " at "
                + java.text.DateFormat.getDateTimeInstance().format(
                        new java.util.Date(base))
                + " addr " + TEST_ADDR + " in "
                + (System.currentTimeMillis() - t0) + "ms" + note
                + "; test rows now sms " + testSmsCount(c)
                + " / mms " + testMmsCount(c);
        Log2.line(c, "TEST: " + v);
        return v;
    }

    private static int insertSmsBatch(Context c, int n, long base)
            throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ops.add(ContentProviderOperation.newInsert(Telephony.Sms.CONTENT_URI)
                    .withValue(Telephony.Sms.ADDRESS, TEST_ADDR)
                    .withValue(Telephony.Sms.DATE, base + i * 1000L)
                    .withValue(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                    .withValue(Telephony.Sms.READ, 1)
                    .withValue(Telephony.Sms.BODY, TEST_TAG
                            + " synthetic sms #" + (i + 1) + " of " + n
                            + " — dated 1969, safe to delete")
                    .build());
        }
        return c.getContentResolver().applyBatch("sms", ops).length;
    }

    // ---- v1.12.0 call-log seeding ----

    /** Synthetic call rows for the same purge exercise. CallLog has no
     *  body column — the discriminators are the NUMBER (555-0199) and
     *  CACHED_NAME ([KFD-TEST] ...), date 1969 (purge criterion stays
     *  structural: date < 2000-01-01). Calls are not conversation rows —
     *  no thread resolution needed; the call-log UI shows them directly. */
    static String seedCalls(Context c, int n, long t0) {
        if (!Ops.isCallProvisioned(c)) {
            return "SKIP: calls not provisioned (" + Ops.PROVISION_CMD_CALL
                    + ") — nothing seeded";
        }
        long base = DATE_1969_MS;
        int ok = 0;
        Throwable negFail = null;
        try {
            ok = insertCallRows(c, n, base);
        } catch (Throwable t) {
            negFail = t;
        }
        String note = "";
        if (negFail != null) {
            Log2.line(c, "seed: 1969 (negative ms) rejected by call log: "
                    + negFail + " — retrying at 1970-01-02");
            base = DATE_1970_MS;
            try {
                ok = insertCallRows(c, n, base);
            } catch (Throwable t) {
                return "seed calls FAILED at fallback base: " + t;
            }
            note = " (1969 negative rejected — used 1970 base)";
        }
        String v = "seeded calls " + ok + "/" + n + " at "
                + java.text.DateFormat.getDateTimeInstance().format(
                        new java.util.Date(base))
                + " number " + TEST_ADDR + " in "
                + (System.currentTimeMillis() - t0) + "ms" + note
                + "; test rows now sms " + testSmsCount(c)
                + " / mms " + testMmsCount(c)
                + " / calls " + testCallCount(c);
        Log2.line(c, "TEST: " + v);
        return v;
    }

    private static int insertCallRows(Context c, int n, long base)
            throws Exception {
        int ok = 0;
        for (int i = 0; i < n; i++) {
            ContentValues v = new ContentValues();
            v.put(android.provider.CallLog.Calls.NUMBER, TEST_ADDR);
            v.put(android.provider.CallLog.Calls.CACHED_NAME, TEST_TAG
                    + " synthetic call #" + (i + 1) + " of " + n);
            v.put(android.provider.CallLog.Calls.DATE, base + i * 1000L);
            v.put(android.provider.CallLog.Calls.DURATION, 60L);
            v.put(android.provider.CallLog.Calls.TYPE,
                    (i % 2 == 0) ? android.provider.CallLog.Calls.INCOMING_TYPE
                            : android.provider.CallLog.Calls.OUTGOING_TYPE);
            v.put(android.provider.CallLog.Calls.NEW, 0);
            Uri u = c.getContentResolver().insert(
                    android.provider.CallLog.Calls.CONTENT_URI, v);
            if (u != null && !"0".equals(u.getLastPathSegment())) ok++;
        }
        return ok;
    }

    static String seedMms(Context c, int n, long t0) {
        // v1.8.2: use the persisted thread (armed once via BTEST_MMSSEED);
        // without it the rows land threadless and render nowhere.
        return seedMmsImpl(c, n, t0, Ops.seedThreadId(c));
    }

    /** v1.8.1: thread-armed variant (BTEST_MMSSEED). Provisioned callers
     *  cannot allocate threads (see BoundaryTestReceiver.mmsSeed) — a
     *  shell-resolved thread id makes the rows conversation-linked and
     *  Google-Messages-visible. threadId -1 = legacy threadless behavior
     *  (rows land but render nowhere). */
    static String seedMmsThread(Context c, int n, long threadId) {
        return seedMmsImpl(c, n, System.currentTimeMillis(), threadId);
    }

    private static String seedMmsImpl(Context c, int n, long t0, long threadId) {
        if (!Ops.isProvisioned(c)) {
            return "SKIP: not provisioned (" + Ops.PROVISION_CMD + ") — nothing seeded";
        }
        long base = DATE_1969_MS;
        int ok = 0;
        Throwable negFail = null;
        try {
            ok = insertMmsRows(c, n, base, threadId);
        } catch (Throwable t) {
            negFail = t;
        }
        String note = "";
        if (negFail != null) {
            Log2.line(c, "seed: 1969 (negative s) rejected: " + negFail
                    + " — retrying at 1970 base");
            base = DATE_1970_MS;
            ok = insertMmsRows(c, n, base, threadId);
            note = " (1969 negative rejected — used 1970 base)";
        }
        String v = "seeded mms " + ok + "/" + n + " (date in SECONDS, base "
                + (base / 1000L) + ", thread " + (threadId > 0 ? threadId : "NONE")
                + ") addr " + TEST_ADDR + " in "
                + (System.currentTimeMillis() - t0) + "ms" + note
                + "; restricted-view counts now sms " + testSmsCount(c)
                + " / mms " + testMmsCount(c)
                + (threadId > 0 ? "" : " — threadless rows are INVISIBLE in"
                    + " your messaging app (no thread armed: run BTEST_MMSSEED"
                    + " --el thread <tid> once; it persists)");
        Log2.line(c, "TEST: " + v);
        return v;
    }

    /**
     * Minimal MMS rows that GOOGLE MESSAGES CAN RENDER. Learned live
     * 2026-11-06 (Devin: seeded MMS "never appeared" in Messages while
     * seeded SMS did): the mms table has NO address column — recipients
     * live in the per-message addr subtable (content://mms/<id>/addr),
     * and the conversation link is thread_id. A header-only row is
     * invisible to the UI no matter what. Recipe per row:
     *   1. resolve the canonical thread via the PUBLIC SDK helper
     *      Threads.getOrCreateThreadId(ctx, TEST_ADDR) — same thread
     *      the SMS seed lands in (provider auto-links SMS by address);
     *   2. header insert with thread_id + the pdu fields real rows
     *      carry (m_id, m_cls=personal, ct_t, tr_id);
     *   3. addr row type 130 = PduHeaders.FROM (0x82). NOTE: the old
     *      code used 137 — that is CC, not FROM (real inbox rows carry
     *      130 from-originator + 137/151), and it inserted NO addr row
     *      at all on-device (silent no-op) leaving thread NULL too;
     *   4. readbacks: threadOk/addrOk counted per row and logged —
     *      no more silent half-inserts.
     * Parts (bodies) are still omitted — Messages may render a blank
     * bubble; visibility + retention-delete is what we test here.
     */
    private static int insertMmsRows(Context c, int n, long base, long forcedThreadId) {
        int ok = 0, addrOk = 0, threadOk = 0, partsOk = 0;
        long threadId = forcedThreadId;
        if (threadId <= 0) {
            try {
                threadId = Telephony.Threads.getOrCreateThreadId(c, TEST_ADDR);
            } catch (Throwable t) {
                Log2.line(c, "seed: getOrCreateThreadId failed (provisioned"
                        + " callers cannot allocate threads — rows will be"
                        + " threadless/invisible; arm a thread via"
                        + " BTEST_MMSSEED): " + t);
            }
        }
        for (int i = 0; i < n; i++) {
            // Header mirrors the REAL rendered rows on this device
            // (live diff 2026-11-06 vs _id 15521, Google-stack text
            // chat rows): m_type=132, ct_t=text/plain (NOT
            // multipart/related), text_only=1, date_sent==date,
            // seen=1, sub="" — and m_id/m_cls/tr_id are NULL on real
            // rows (dropped; 1.7.2/3 guessed a classic MMSC shape).
            ContentValues h = new ContentValues();
            h.put(Telephony.Mms.DATE, (base + i * 1000L) / 1000L);  // SECONDS
            h.put("date_sent", (base + i * 1000L) / 1000L);
            h.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX);
            h.put(Telephony.Mms.READ, 1);
            h.put("seen", 1);
            h.put(Telephony.Mms.MESSAGE_TYPE, 132);  // real inbox rows (Google stack)
            h.put(Telephony.Mms.CONTENT_TYPE, "text/plain");          // ct_t
            h.put("text_only", 1);
            h.put("sub", "");
            h.put("sub_cs", 106);
            if (threadId > 0) h.put(Telephony.Mms.THREAD_ID, threadId);
            Uri u = c.getContentResolver().insert(Telephony.Mms.CONTENT_URI, h);
            if (u == null) continue;
            ok++;
            try {
                ContentValues a = new ContentValues();
                a.put(Telephony.Mms.Addr.ADDRESS, TEST_ADDR);
                a.put(Telephony.Mms.Addr.TYPE, 130);   // PduHeaders.FROM
                a.put(Telephony.Mms.Addr.CHARSET, 106);   // UTF-8
                Uri au = c.getContentResolver().insert(
                        Uri.withAppendedPath(u, "addr"), a);
                if (au != null) addrOk++;
            } catch (Throwable t) {
                Log2.line(c, "seed: mms addr row failed (header kept): " + t);
            }
            // text part — mirrors Google Messages' own text-MMS shape
            // (live-verified 2026-11-06: real row = single part, ct
            // text/plain, name=body, chset=106, seq=0, no smil). The
            // bubble LOADER requires parts: header-only rows are
            // skipped silently (rendered as an empty thread).
            try {
                ContentValues p = new ContentValues();
                p.put("seq", 0);
                p.put("ct", "text/plain");
                p.put("name", "body");
                p.put("chset", 106);
                p.put("text", TEST_TAG + " synthetic mms #" + (i + 1)
                        + " of " + n + " — dated 1969, safe to delete");
                Uri pu = c.getContentResolver().insert(
                        Uri.withAppendedPath(u, "part"), p);
                if (pu != null) partsOk++;
            } catch (Throwable t) {
                Log2.line(c, "seed: mms part row failed: " + t);
            }
            // thread readback: insert-time value may be stripped by the
            // provider; if so try the app-side update (role grants it)
            if (threadId > 0) {
                try {
                    android.database.Cursor cur = c.getContentResolver().query(
                            u, new String[]{Telephony.Mms.THREAD_ID},
                            null, null, null);
                    long tNow = -1;
                    if (cur != null) {
                        if (cur.moveToFirst()) tNow = cur.getLong(0);
                        cur.close();
                    }
                    if (tNow != threadId) {
                        ContentValues tv = new ContentValues();
                        tv.put(Telephony.Mms.THREAD_ID, threadId);
                        c.getContentResolver().update(u, tv, null, null);
                        cur = c.getContentResolver().query(
                                u, new String[]{Telephony.Mms.THREAD_ID},
                                null, null, null);
                        if (cur != null) {
                            if (cur.moveToFirst() && cur.getLong(0) == threadId) {
                                tNow = threadId;
                            }
                            cur.close();
                        }
                    }
                    if (tNow == threadId) threadOk++;
                } catch (Throwable t) {
                    Log2.line(c, "seed: thread readback failed: " + t);
                }
            }
        }
        Log2.line(c, "seed: mms detail rows=" + ok + " addrOk=" + addrOk + " partsOk=" + partsOk
                + " threadOk=" + threadOk + " (thread " + threadId + ")");
        return ok;
    }

    // ---- purge (structural: pre-2000 only) ----

    /**
     * v1.8.0: RAW-WHERE delete, not chunked. The provider QUERY surface
     * for a non-default caller is a RESTRICTED VIEW (live-verified on
     * GOS/A17 2026-11-06: rows inserted by non-default callers are
     * query-INVISIBLE to provisioned apps, creator-based, age-irrelevant
     * — the DELETE path has no such filter and hits the base table). A
     * count-then-select purge would therefore never see or remove its
     * own provisioned-mode seeds; a raw WHERE delete does. The pre-2000
     * structural criterion keeps the selection tiny and safe (volumes
     * are test-scale; the retention pass itself stays chunked — its
     * production targets are real Messages-created rows, which ARE
     * visible to the restricted view).
     */
    static String purge(Context c, long t0) {
        boolean smsOk = Ops.isProvisioned(c);
        boolean callOk = Ops.isCallProvisioned(c);
        if (!smsOk && !callOk) {
            return "SKIP: not provisioned (" + Ops.PROVISION_CMD + " / "
                    + Ops.PROVISION_CMD_CALL + ") — nothing purged";
        }
        int smsRet = 0, mmsRet = 0, callRet = 0;
        String skip = "";
        if (smsOk) {
            try {
                smsRet = c.getContentResolver().delete(Telephony.Sms.CONTENT_URI,
                        "date < ?", new String[]{String.valueOf(CUT_SMS_MS)});
            } catch (Throwable t) {
                Log2.line(c, "purge: sms delete THREW: " + t);
                smsRet = -1;
            }
            try {
                mmsRet = c.getContentResolver().delete(Telephony.Mms.CONTENT_URI,
                        "date < ?", new String[]{String.valueOf(CUT_MMS_S)});
            } catch (Throwable t) {
                Log2.line(c, "purge: mms delete THREW: " + t);
                mmsRet = -1;
            }
        } else {
            skip = "; sms/mms SKIPPED — not provisioned (" + Ops.PROVISION_CMD + ")";
        }
        if (callOk) {
            try {
                callRet = c.getContentResolver().delete(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        "date < ?", new String[]{String.valueOf(CUT_CALL_MS)});
            } catch (Throwable t) {
                Log2.line(c, "purge: call delete THREW: " + t);
                callRet = -1;
            }
        } else {
            skip = skip + "; calls SKIPPED — not provisioned ("
                    + Ops.PROVISION_CMD_CALL + ")";
        }
        String v = "purged test rows (raw WHERE, ret is authoritative): sms " + smsRet
                + ", mms " + mmsRet + ", calls " + callRet + " in "
                + (System.currentTimeMillis() - t0) + "ms; restricted-view counts now "
                + testSmsCount(c) + " / " + testMmsCount(c) + " / " + testCallCount(c)
                + " (self-seeded rows are query-invisible — ret values are the truth)"
                + skip;
        Log2.line(c, "TEST: " + v);
        return v;
    }
}
