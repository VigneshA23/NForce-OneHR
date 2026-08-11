package com.nforce.onehr.config;

import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

/**
 * Forces the seeded "Regular Shift" back to its intended 3:00 PM – 12:30 AM window on every
 * startup, regardless of Flyway state.
 *
 * Why this bypasses the normal migration convention: this app runs against a shared dev
 * database where Flyway version numbers have collided before (see the note in
 * application-local.yml about V11's checksum), and {@code validate-on-migrate: false} makes
 * such collisions silent — a migration whose version number is already recorded (by someone
 * else's differently-numbered, differently-contented migration on this same shared DB) is
 * skipped with no error. Two dedicated migrations for this exact shift-timing change
 * (V100, V101) were silently skipped this way. Since this one row is small, well-known, and
 * idempotent to reassert, a startup-time correction sidesteps the version-collision problem
 * entirely instead of playing whack-a-mole with migration numbers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShiftSeedCorrector implements ApplicationRunner {

    private static final String SHIFT_NAME = "Regular Shift";
    private static final LocalTime INTENDED_START = LocalTime.of(15, 0);
    private static final LocalTime INTENDED_END = LocalTime.of(0, 30);

    private final ShiftRepository shiftRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        shiftRepository.findByName(SHIFT_NAME).ifPresent(shift -> {
            if (!INTENDED_START.equals(shift.getStartTime()) || !INTENDED_END.equals(shift.getEndTime())) {
                log.warn("Correcting '{}' shift timings from {}-{} to {}-{} (out-of-band DB edit or a "
                                + "Flyway version collision on the shared dev DB — see ShiftSeedCorrector's Javadoc)",
                        SHIFT_NAME, shift.getStartTime(), shift.getEndTime(), INTENDED_START, INTENDED_END);
                shift.setStartTime(INTENDED_START);
                shift.setEndTime(INTENDED_END);
                shiftRepository.save(shift);
            }
        });
    }
}
