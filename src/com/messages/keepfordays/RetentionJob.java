package com.messages.keepfordays;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** One-shot chained daily job (v1.5.5 chaining retained; v1.8.0 body):
 *  armed by Ops.armNextRun for the configured run hour; at every fire it
 *  RE-ARMS tomorrow's job FIRST (a failed run never orphans the chain),
 *  then runs the provider pass DIRECTLY — provisioned mode needs no
 *  screen, no a11y, no WADB, no Wi-Fi, so a locked/asleep 03:00 run just
 *  runs. (The v1.5.5–v1.7.x FSI/unlock-defer machinery is gone with the
 *  dance; UnlockReceiver is deleted.) NOTE: force-stop suspends jobs
 *  platform-wide until the app is next launched — the panel shows an
 *  OVERDUE flag. */
public class RetentionJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!Ops.scheduleEnabled(this)) {
            jobFinished(params, false);
            return false;
        }
        Ops.recordFire(this);
        Ops.armNextRun(this);      // chain tomorrow's run BEFORE the pass
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String verdict = Ops.deletePass(RetentionJob.this, "job");
                    Ops.setLastVerdict(RetentionJob.this, "job: " + verdict);
                    if (verdict.startsWith("SKIP")) {
                        Ops.notifyKind(RetentionJob.this, "fail",
                                "Scheduled pass skipped — not provisioned",
                                verdict + " — no messages were deleted.");
                    } else {
                        // v1.11.0: scheduled-run SUCCESS is a preference
                        // (default OFF — no notification every day).
                        Ops.notifyKind(RetentionJob.this, "sched_ok",
                                "Scheduled retention pass complete", verdict);
                    }
                } catch (Throwable t) {
                    Log2.line(RetentionJob.this, "job crashed: " + t);
                } finally {
                    jobFinished(params, false);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
