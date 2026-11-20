package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * US-A04/A05/A03: the one nightly bench job. Uses the same @Scheduled mechanism
 * ({@code @EnableScheduling} on OneHrApplication) as the attendance penalty evaluation; the cron
 * is configurable the same way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BenchNightlyScheduler {

    private final BenchAgingService agingService;

    // ONEHR-729: also run once on startup, so a server that was down at 00:15 (typical outside prod)
    // still catches up on buckets and threshold notifications. Safe to repeat: notifiedAt dedupes
    // the notification and the snapshot is keyed by date.
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${app.bench.nightly.cron:0 15 0 * * *}")
    public void run() {
        try {
            agingService.recomputeAll();
        } catch (Exception e) {
            // Never let a scheduler failure escape into the scheduler thread; the next night retries.
            log.error("Scheduled bench aging/snapshot pass failed", e);
        }
    }
}
