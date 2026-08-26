package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Two org-wide, hourly attendance sweeps that neither checkOut nor a lazy per-employee check can
 * fully cover on their own. Mirrors {@link PenaltyEvaluationScheduler}'s pattern (this codebase's
 * first scheduled job) — same "log and let the next run retry" failure handling.
 *
 * <ol>
 * <li>Flags any session left open past its own workday/grace window as Missing Check-Out, never
 * fabricating a checkout time or worked-hours figure. {@link
 * AttendanceService#flagMissingCheckoutIfStale} already does this lazily, the next time that
 * specific employee's own {@code getToday}/{@code checkIn} runs — but an employee who simply
 * never comes back (resignation, extended leave, a forgotten account) would otherwise leave that
 * session open, and visibly "still checked in," indefinitely.
 * <li>Finalizes HALF_DAY (or confirms PRESENT/LATE) for a day that closed well before its own
 * shift ended and never reopened — see {@link AttendanceService#finalizeStatusPastShiftEnd} for
 * why {@code closeSession} deliberately leaves that judgment open at checkout time itself.
 * </ol>
 *
 * <p>Hourly is frequent enough that either case is resolved the same shift-day it went stale,
 * without polling so often it's pointless — attendance state doesn't change faster than that.
 *
 * <p>Also runs once at startup (implements {@link ApplicationRunner}, same pattern as {@link
 * com.nforce.onehr.config.ShiftSeedCorrector}) — a fix that only ever ran lazily/hourly would
 * leave every already-eligible record from before this deploy sitting unresolved until either its
 * own employee happened to touch attendance again or the next hourly tick, whichever came first.
 */
// Runs after ShiftSeedCorrector (see its own @Order) so the sweep's shift-end cutoff always
// reads already-corrected Shift.endTime values, never a stale one mid-correction.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleAttendanceSweeper implements ApplicationRunner {

    private final AttendanceService attendanceService;

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    @Scheduled(cron = "${app.attendance.stale-session-sweep.cron:0 0 * * * *}")
    public void scheduledSweep() {
        sweep();
    }

    private void sweep() {
        try {
            attendanceService.flagAllStaleOpenSessionsAsMissingCheckout();
        } catch (Exception e) {
            // Never let a scheduled-job (or startup) failure surface as an unhandled exception —
            // log and let the next scheduled run retry.
            log.error("Stale attendance session sweep failed", e);
        }
        try {
            attendanceService.finalizeStatusPastShiftEnd();
        } catch (Exception e) {
            log.error("Past-shift-end status finalization sweep failed", e);
        }
    }
}
