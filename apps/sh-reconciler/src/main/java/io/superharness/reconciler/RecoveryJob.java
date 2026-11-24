package io.superharness.reconciler;

import io.superharness.runengine.RunLifecycle;
import io.superharness.runengine.ScheduledTurns;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RecoveryJob {
    private final RunLifecycle runs;

    private final io.superharness.persistence.Knowledge knowledge;

    private final ScheduledTurns schedules;

    RecoveryJob(RunLifecycle runs, io.superharness.persistence.Knowledge knowledge, ScheduledTurns schedules) {
        this.schedules = schedules;
        this.knowledge = knowledge;
        this.runs = runs;
    }

    @Scheduled(fixedDelay = 5000)
    void runSchedules() { schedules.dispatchDue(); }

    @Scheduled(fixedDelay = 60000)
    void expireMemory() {
        knowledge.pruneExpiredMemories();
    }

    @Scheduled(fixedDelayString = "${sh.recovery.scan-ms:5000}")
    void reconcile() {
        for (int i = 0; i < 50 && runs.reapOne(); i++) {
            /* one short transaction per run */
        }
    }
}
