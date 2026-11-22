package io.superharness.reconciler;

import io.superharness.runengine.RunLifecycle;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RecoveryJob {
    private final RunLifecycle runs;

    RecoveryJob(RunLifecycle runs) {
        this.runs = runs;
    }

    @Scheduled(fixedDelayString = "${sh.recovery.scan-ms:5000}")
    void reconcile() {
        for (int i = 0; i < 50 && runs.reapOne(); i++) {
            /* one short transaction per run */
        }
    }
}
