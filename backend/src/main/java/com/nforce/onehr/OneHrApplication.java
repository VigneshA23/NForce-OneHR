package com.nforce.onehr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling backs PenaltyEvaluationScheduler (Section 45) — the first/only @Scheduled
// job in this codebase, so this app previously had no scheduling infrastructure at all.
@EnableScheduling
@SpringBootApplication
public class OneHrApplication {
    public static void main(String[] args) {
        SpringApplication.run(OneHrApplication.class, args);
    }
}
