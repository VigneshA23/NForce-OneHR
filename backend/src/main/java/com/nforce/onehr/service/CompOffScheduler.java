package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Nightly: find yesterday's eligible Comp-Off days, expire lapsed credits, warn about ones about to lapse. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompOffScheduler {

    private final CompOffService compOffService;
    private final CompOffPolicyService policyService;

    @Scheduled(cron = "${app.comp-off.cron:0 15 1 * * *}")
    public void run() {
        LocalDate today = LocalDate.now();
        try {
            if (policyService.compOffEnabled()) {
                // Look back a few days so late-corrected attendance is still picked up; detection is idempotent.
                compOffService.detect(today.minusDays(3), today.minusDays(1), null, null);
            }
        } catch (Exception e) {
            log.error("Scheduled Comp-Off detection failed", e);
        }
        try {
            int expired = compOffService.expireAndNotify(today);
            if (expired > 0) log.info("Expired {} Comp-Off credit(s)", expired);
        } catch (Exception e) {
            log.error("Scheduled Comp-Off expiry failed", e);
        }
    }
}
