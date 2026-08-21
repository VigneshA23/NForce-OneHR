package com.nforce.onehr.config;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Map;

/**
 * One-time correction for the 3 overnight shifts V123 seeded via a raw SQL {@code INSERT}
 * (US Night Shift, UK Evening Shift, APAC Evening Shift) — their {@code start_time}/{@code
 * end_time} read back from Postgres shifted by exactly +5:30 (e.g. US Night Shift's intended
 * 20:30 read as 02:00) with this app's current {@code hibernate.jdbc.time_zone: UTC} setting.
 *
 * <p>Root cause: that setting was added specifically so Hibernate uses a fixed UTC {@code
 * Calendar} for {@code java.sql.Time}/{@code Timestamp} JDBC binding on BOTH read and write,
 * instead of the JVM's default zone (IST locally) — see the application.yml comment next to it.
 * That fix is correct and symmetric for any row Hibernate itself wrote (the same UTC-calendar
 * conversion applies going in and coming out, canceling out) — confirmed by "Regular Shift"
 * (corrected via Hibernate save in the old {@link ShiftSeedCorrector}) and any shift created
 * through the Shift Management UI ({@code OrgService#createShift}) both reading back correctly.
 * But a row inserted via raw SQL bypasses that Hibernate-mediated write entirely — Postgres
 * stores the literal, un-shifted value — so the FIRST time Hibernate reads it, the one-sided
 * UTC-calendar conversion introduces a spurious +5:30 that was never "written in" to cancel out.
 *
 * <p>Fixes it the same way "Regular Shift" was historically kept correct: re-save the intended
 * value through Hibernate once, so read and write are symmetric from then on. Runs once per
 * startup, but is idempotent — it only writes when the stored value still doesn't match, so it
 * has no effect once corrected (including if a Super Admin later edits these shifts again
 * through the UI, since a real edit already goes through this same Hibernate-symmetric path).
 *
 * <p>Not a general fix for the underlying quirk — any FUTURE raw-SQL migration seeding a shift's
 * start_time/end_time would hit the same issue. Prefer creating shifts through the Shift
 * Management UI/API instead of a migration; if a migration truly must seed one, add its name and
 * intended times here rather than relying on Flyway alone.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationSeededShiftTimeFix implements ApplicationRunner {

    private static final Map<String, LocalTime[]> INTENDED = Map.of(
            "US Night Shift", new LocalTime[] { LocalTime.of(20, 30), LocalTime.of(5, 30) },
            "UK Evening Shift", new LocalTime[] { LocalTime.of(18, 30), LocalTime.of(3, 30) },
            "APAC Evening Shift", new LocalTime[] { LocalTime.of(17, 30), LocalTime.of(2, 30) }
    );

    private final ShiftRepository shiftRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        INTENDED.forEach((name, times) -> shiftRepository.findByName(name).ifPresent(shift -> {
            LocalTime intendedStart = times[0];
            LocalTime intendedEnd = times[1];
            if (!intendedStart.equals(shift.getStartTime()) || !intendedEnd.equals(shift.getEndTime())) {
                log.warn("Correcting migration-seeded '{}' shift timings from {}-{} to {}-{} "
                                + "(raw-SQL insert read back shifted by the JDBC UTC time_zone setting — "
                                + "see MigrationSeededShiftTimeFix's Javadoc)",
                        name, shift.getStartTime(), shift.getEndTime(), intendedStart, intendedEnd);
                shift.setStartTime(intendedStart);
                shift.setEndTime(intendedEnd);
                shiftRepository.save(shift);
            }
        }));
    }
}
