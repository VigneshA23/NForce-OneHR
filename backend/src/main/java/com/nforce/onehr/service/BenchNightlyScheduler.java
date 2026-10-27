package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
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
