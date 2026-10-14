package com.nforce.onehr.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AuditService#afterCommit}: a compound operation's audit entry is written only if its
 * transaction commits — a rolled-back attempt must leave no entry behind.
 */
class AuditServiceAfterCommitTest {

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWhenThereIsNoTransaction() {
        AtomicInteger runs = new AtomicInteger();

        AuditService.afterCommit(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void waitsForTheCommit_insideATransaction() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AuditService.afterCommit(runs::incrementAndGet);
        assertEquals(0, runs.get());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, runs.get());
    }

    @Test
    void neverRuns_whenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AuditService.afterCommit(runs::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertEquals(0, runs.get());
    }
}
