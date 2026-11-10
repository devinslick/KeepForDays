# Keep For Days

`com.messages.keepfordays` — automated message-retention enforcement
for Android: deletes SMS/MMS/RCS messages and call-log entries older
than a configurable window (default 30 days, any integer 1–36500
accepted). No root, no default-SMS-role flips, no accessibility
service, no wireless debugging, no network permission — the app is
fully offline, and the default-SMS role **always rests with your
messaging app** (Google Messages in the reference deployment).

Developed and live-verified on a Pixel 9 running GrapheneOS /
Android 17.

## How it works — provisioned mode

The SMS/MMS provider write gate is the **WRITE_SMS AppOps op** — the
default-SMS role was only ever the standard vehicle for setting it.
One-time shell provisioning hands the app permanent write privilege
without ever holding the role:

    adb shell appops set com.messages.keepfordays WRITE_SMS allow
    # plus the READ_SMS runtime grant (in-app permission flow)

Verified live on GrapheneOS/Android 17: role-less provider deletes
land (`ret=100`, rows 100→0), inserts land, cross-creator deletes
land, and the role stays untouched throughout. The appop persists
across reboots and app updates. The app self-checks provisioning
(`checkOpNoThrow("android:write_sms")`) before every write path and
refuses loudly — deleting nothing — naming the fix command when it is
missing.

### Query/delete asymmetry (platform fact)

The provider QUERY surface for a non-default caller is a restricted
view: rows inserted by non-default callers are query-INVISIBLE to it
(creator-based, age-irrelevant). The DELETE path has no such filter
and hits the base table. Production targets — rows created by the real
default messaging app — ARE visible to the restricted view. Handled in
code:

- the retention pass is TWO-PHASE: chunked deletes for the visible set
  (bounded batches keep the messaging app's reconciliation smooth),
  then an UNCONDITIONAL raw-WHERE sweep per table whose return values
  are the truth for the invisible remainder — "nothing older" is only
  claimed when BOTH phases deleted zero;
- `TestOps.purge` uses a raw-WHERE delete — it can never see its own
  provisioned-mode seeds through the restricted view (delete return
  values are authoritative; re-queries undercount self-seeded rows);
- status-row test counts read the restricted view (may undercount
  self-seeded test rows — known cosmetics).

## Call history

The same retention policy and actions cover the call log
(`CallLog.Calls`, voicemail rows included). Provisioning is PER-TYPE:
the call sweep runs only when the `WRITE_CALL_LOG` appop is allowed
AND `READ_CALL_LOG` is granted; a missing pair SKIPS calls entirely
(sms/mms unaffected) — never a partial delete. Never granting
`READ_CALL_LOG` is the supported opt-out: the app then never touches
call history.

Note: `WRITE_CALL_LOG` is a runtime-class permission — its appop mode
mirrors the runtime grant, so granting the permission (not merely
setting the appop) is what actually provisions it:

    adb shell pm grant com.messages.keepfordays android.permission.READ_CALL_LOG
    adb shell pm grant com.messages.keepfordays android.permission.WRITE_CALL_LOG

## Safety invariants (the contract)

1. **Window 1–36500 days, default 30.** Any integer in range is
   accepted; out-of-range values are refused loudly (a 0/negative
   window would delete everything immediately).
2. **The SMS role rests with your messaging app — permanently.** The
   app never holds the role. The SMS-role manifest quartet
   (deliver receivers, respond-via-message service, send-to activity)
   stays INTACT but INERT, so a future role-hold fallback needs no
   re-derivation of the qualification requirements.
3. **No standing privileged surfaces.** No a11y service, no WADB
   client, no INTERNET/FSI/SYSTEM_ALERT_WINDOW permissions, no role
   flips — nothing for device-management or accessibility-posture
   tooling to defend, and nothing that trips SMS-guard class tooling.
4. **Deletion is provider-side and two-phase.** Phase 1: the visible
   set (restricted view — real, default-app-created rows) via chunked
   500-row `_id` batches. Phase 2: an unconditional raw-WHERE sweep
   per table for rows the restricted view cannot see. SMS date is ms,
   MMS date is SECONDS — the app converts; the post-delete re-query
   plus raw delete return values are truth; a poisoned batch ends that
   table's sweep without losing the verdict. Provider deletes
   propagate to the messaging app's own DB/UI (verified with Google
   Messages up to 4200 rows in one call; larger volumes reconcile
   within minutes to ~2h).
5. **No messaging-app UI automation, ever.** Provider-row deletion
   only.

### RCS reality

RCS exists only in Google Messages on Android today. RCS chats ride a
relay — the carrier's own RCS service where it still runs one,
otherwise Jibe (Google's RCS platform; AT&T, T-Mobile, and Verizon
moved onto it in 2023-2024, and it hosts most carriers worldwide). The
relay only queues messages for delivery and deletes them once
delivered; chats between Google Messages users are end-to-end
encrypted by default. Conversations sync to the on-device provider —
the same store this app manages — so provider-side passes cover RCS
through the same process as SMS/MMS. RCS provider rows are maintained
by the messaging app; this app never touches its UI.

## Triggers

- `RUN_PASS` — explicit-component broadcast, no key
  (adb/orchestrator use):

      am broadcast --user 0 -n com.messages.keepfordays/.RetentionReceiver \
        -a com.messages.keepfordays.action.RUN_PASS

- `RUN_RETENTION` — in the intent-filter, gated by a shared-secret
  `key` extra (Home Assistant companion; see below). Wrong or missing
  key = refused loudly, nothing deleted.

Both refuse loudly when not provisioned.

## Home Assistant

The HA companion app can fire the pass remotely via the
`command_broadcast_intent` notification command. Find your service
name in HA (Developer Tools → Actions, search `notify.mobile_app_`),
then:

```yaml
service: notify.mobile_app_<name>
data:
  message: command_broadcast_intent
  data:
    intent_action: >-
      com.messages.keepfordays.action.RUN_RETENTION
    intent_package_name: com.messages.keepfordays
    intent_extras: "key:<YOUR_SHARED_KEY>"
```

`intent_package_name` is REQUIRED by the companion app (it makes the
broadcast explicit so it reaches the manifest receiver); the shared
key is set in the app (sidebar → Basic → Home Assistant key). Current
HA has no legacy `notify.<name>` services — a bare `notify.<name>`
call fails with "Action not found".

## Scheduling

Deterministic daily run hour (panel), self-re-arming one-shot
JobScheduler jobs. The pass runs DIRECTLY at the hour — provisioned
mode needs no screen, no Wi-Fi, no listener, so a locked or asleep
03:00 run just runs. Force-stop suspends jobs platform-wide until the
app is next launched (the panel shows an OVERDUE flag when that
happens).

## Panel reference

Status: provisioning verdict + fix command, last result, schedule +
next/last fire, HA key, test-row counts, sms/mms/call totals vs
older-than-window.

Buttons:
- `RUN PASS NOW` — provisioned retention pass
- `TEST: SEED 100 SMS (1969)` / `TEST: SEED 100 MMS (1969)` —
  synthetic rows, direct (no role window)
- `TEST: PURGE TEST ROWS (only rows dated <2000)` — raw-WHERE purge
- `SCHEDULE: TOGGLE`, `SAVE RUN HOUR`, `SAVE RETENTION DAYS`,
  `SAVE HA KEY`, `EXPORT EVENTS.LOG`

Navigation drawer: Home (status + action-needed banner + RUN PASS),
Basic (window + HA key), Advanced (provisioning detail + invariants),
Scheduling (toggle + run hour + next/last), Debug (synthetic seed/
purge + broadcast reference), Help, Logs (events.log tail + export).
Onboarding wizard on first run, skippable at every step.

## TestOps — synthetic test data

Every synthetic row carries ALL of: address `555-0199`, body tag
`[KFD-TEST]`, and date 1969-01-01 UTC (negative epoch ms — accepted by
the provider on this build; a 1970 fallback exists). The purge
criterion is STRUCTURAL: `date < 2000-01-01` — no real message can
predate 2000.

**MMS seeds need a THREAD.** Provisioned callers cannot allocate
conversations on this build: the `threadID` URI is caller-gated (shell
resolves it, apps get an empty cursor), the provider's address-in-
header path dies on an identity bug ("Package com.android.phone does
not belong to <uid>"), and `thread_id` updates no-op for non-default
callers. A threadless mms row lands in the provider but has NO
conversation → the messaging app renders nothing. With a thread,
everything renders (negative `date_sent`, `m_size`/`ct_cls` are all
innocent — proven by a variant ladder). Seed UI-visible MMS rows:

    # resolve the thread ONCE per device (the husk survives purges):
    adb shell "content query --uri content://mms-sms/threadID?recipient=555-0199"
    # seed (render-verified live); this also ARMS the thread into the
    # app's prefs — afterwards the panel's own "TEST: SEED 100 MMS"
    # button renders too, no broadcast needed per round:
    adb shell am broadcast --user 0 -n com.messages.keepfordays/.BoundaryTestReceiver \
      -a com.messages.keepfordays.BTEST_MMSSEED --el thread <tid> --ei count 5
    # purge via the panel button (raw-WHERE delete, date<2000 —
    # self-seeded rows are query-invisible; return values authoritative)

For larger SMS volumes the `BoundaryTestReceiver` (explicit broadcasts:
`BTEST_SEED` / `BTEST_DELBATCH` / `BTEST_COUNT` / `BTEST_LIST`, addr
5550100) is provisioned-gated; self-seeded rows are query-invisible
(see the asymmetry above) — clean them with a raw-WHERE delete.

## Build & signing

Gradle-free: `sh build.sh` (JDK 17 + `platforms;android-34` +
`build-tools;34.0.0`; sources are no-lambda Java for bootclasspath
builds). The build greps `classes.dex` for a version marker — bump
`versionCode`/`versionName` (manifest), `VER_NAME`/`VER_CODE`
(build.sh) and `VER` (MainActivity) together.

CI (`.github/workflows/build.yaml`) builds and signs every push and
publishes a rolling release tagged `keep-for-days-v<ver>`.

**Devices only update in place over the CI signature.** The signing
cert SHA-256 is `267b7543…db56f2`; the first signature installed
defines the update channel, so a locally-generated debug keystore can
never replace it. The CI keystore is kept in a private vault; to sign
an in-place-update artifact locally, export it and run
`KSTORE=<keystore> KSTOREPASS=<pw> sh build.sh`. After install: launch
once, verify `appops get com.messages.keepfordays WRITE_SMS` = allow,
and `logcat -s KeepForDays:V` for the start marker.

## License

MIT — see [LICENSE](LICENSE). © Devin Slick
