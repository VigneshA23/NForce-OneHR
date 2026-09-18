package com.nforce.onehr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

/**
 * Section 45's scheduler: verifies it is a thin, correctly-configured trigger over the existing
 * evaluation pipeline — never a second engine — and that a downstream failure never escapes the
 * scheduled-job thread (which would otherwise silently kill future scheduled runs).
 */
@ExtendWith(MockitoExtension.class)
class PenaltyEvaluationSchedulerTest {

    @Mock private ExceptionService exceptionService;

    private PenaltyEvaluationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PenaltyEvaluationScheduler(exceptionService);
        ReflectionTestUtils.setField(scheduler, "lookbackDays", 60);
    }

    @Test
    void run_delegatesToExceptionService_withConfiguredLookback() {
        scheduler.run();

        verify(exceptionService, times(1)).runScheduledPenaltyEvaluation(60);
        // Section-45-era pipeline plus the later attendance-exception-notification step (see
        // ExceptionService#notifyUnnotifiedExceptions) — these are the only two calls this
        // scheduler ever makes onto exceptionService.
        verify(exceptionService, times(1)).notifyUnnotifiedExceptions();
        verifyNoMoreInteractions(exceptionService);
    }

    @Test
    void run_notifyUnnotifiedExceptionsThrows_doesNotPropagate_andPenaltyEvaluationStillRan() {
        doThrow(new RuntimeException("email provider unavailable")).when(exceptionService).notifyUnnotifiedExceptions();

        // Must not throw — same "never silently disable future scheduled runs" guarantee as the
        // penalty-evaluation branch, and in its own try/catch so a notification failure can never
        // suppress the (already-completed) penalty evaluation above it, or vice versa.
        scheduler.run();

        verify(exceptionService).runScheduledPenaltyEvaluation(60);
        verify(exceptionService).notifyUnnotifiedExceptions();
    }

    @Test
    void run_usesWhicheverLookbackIsConfigured_notHardcoded() {
        ReflectionTestUtils.setField(scheduler, "lookbackDays", 45);

        scheduler.run();

        verify(exceptionService).runScheduledPenaltyEvaluation(45);
    }

    @Test
    void run_exceptionServiceThrows_doesNotPropagate() {
        doThrow(new RuntimeException("db unavailable")).when(exceptionService).runScheduledPenaltyEvaluation(anyInt());

        // Must not throw — a scheduled-job failure is logged and swallowed, not propagated,
        // so it never silently disables all future @Scheduled runs.
        scheduler.run();

        verify(exceptionService).runScheduledPenaltyEvaluation(60);
        // Its own independent try/catch — a penalty-evaluation failure must never suppress the
        // notification step that follows it.
        verify(exceptionService).notifyUnnotifiedExceptions();
    }

    @Test
    void run_calledTwiceInARow_bothInvokeTheSamePipeline_noSecondEngineIntroduced() {
        scheduler.run();
        scheduler.run();

        // Idempotency itself is guaranteed downstream (ExceptionService's isNew/duplicate guards,
        // see MultiPolicyAssignmentIsolationTest / AttendancePenaltyEvaluationServiceTest) — this
        // only proves the scheduler always re-enters the one existing pipeline, never a second one.
        verify(exceptionService, times(2)).runScheduledPenaltyEvaluation(60);
        verify(exceptionService, times(2)).notifyUnnotifiedExceptions();
    }
}
