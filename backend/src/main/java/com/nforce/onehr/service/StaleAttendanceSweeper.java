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
 * Closes any attendance session left open past its own shift's natural end, org-wide. Mirrors
 * {@link PenaltyEvaluationScheduler}'s pattern (this codebase's first scheduled job) — same
 * "log and let the next run retry" failure handling.
 *
 * <p>{@link AttendanceService#autoCloseIfStale} already closes a stale session lazily, the next
 * time that specific employee's own {@code getToday}/{@code checkIn} runs — but an employee who
 * simply never comes back (resignation, extended leave, a forgotten account) would otherwise
 * leave that session open, and visibly "still checked in," indefinitely. Hourly is frequent
 * enough that a forgotten checkout is closed the same shift-day it went stale, without polling
 * so often it's pointless — attendance state doesn't change faster than that.
 *
 * <p>Also runs once at startup (implements {@link ApplicationRunner}, same pattern as {@link
 * com.nforce.onehr.config.ShiftSeedCorrector}) — a fix that only ever closed sessions lazily
 * would leave every already-stale record from before this deploy sitting open until either its
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
            attendanceService.closeAllStaleOpenSessions();
        } catch (Exception e) {
            // Never let a scheduled-job (or startup) failure surface as an unhandled exception —
            // log and let the next scheduled run retry.
            log.error("Stale attendance session sweep failed", e);
        }
    }
}
