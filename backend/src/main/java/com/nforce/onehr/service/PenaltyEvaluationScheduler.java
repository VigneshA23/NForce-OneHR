package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Section 45: the first/only background job in this codebase. Before this class, Penalization
 * Policy evaluation ran exclusively inside {@link ExceptionService#getExceptionsForCaller} — a
 * synchronous side effect of loading the Exceptions/Penalties dashboard, with no scheduler
 * anywhere (confirmed by a full-codebase search for {@code @Scheduled}/{@code @EnableScheduling}).
 * This job runs the exact same detection/evaluation pipeline
 * ({@link ExceptionService#runScheduledPenaltyEvaluation}) — no second engine, no duplicate
 * detection logic — so penalties are still produced even on a day nobody opens that dashboard.
 * {@link AttendancePenaltyEvaluationService}'s existing duplicate guard makes re-running this (or
 * a dashboard load re-detecting the same occurrence) safe — no double penalties.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PenaltyEvaluationScheduler {

    private final ExceptionService exceptionService;

    @Value("${app.attendance.penalty-evaluation.lookback-days:60}")
    private int lookbackDays;

    // Once daily, well after the day's attendance is settled. Configurable so ops can move it
    // without a redeploy, same convention as RegularizationService's @Value-backed windows.
    @Scheduled(cron = "${app.attendance.penalty-evaluation.cron:0 0 2 * * *}")
    public void run() {
        log.info("Running scheduled attendance penalty evaluation (lookback={} days)", lookbackDays);
        try {
            exceptionService.runScheduledPenaltyEvaluation(lookbackDays);
        } catch (Exception e) {
            // Never let a scheduled-job failure surface as an unhandled exception in the
            // scheduler thread — log and let the next scheduled run retry.
            log.error("Scheduled attendance penalty evaluation failed", e);
        }
    }
}
