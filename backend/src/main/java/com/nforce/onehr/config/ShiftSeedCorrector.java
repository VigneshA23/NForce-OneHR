package com.nforce.onehr.config;

import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forces the seeded "Regular Shift" back to its intended 3:30 PM – 12:30 AM window on every
 * startup, regardless of Flyway state, and backfills it onto any employee left without a
 * shift assignment.
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
 *
 * V101 itself moved the intended start to 15:00, but this constant was changed back to 15:30
 * without a matching migration — leaving this corrector and V101 disagreeing on every restart.
 * 15:30 is confirmed correct; V117 brings the DB back in line with it.
 *
 * The employee backfill exists because V95's "assign everyone the Regular Shift" UPDATE only
 * ran once, against whoever existed at that moment — any employee onboarded since has a null
 * {@code shift_id}, which silently falls back to {@code AttendanceProperties.shiftStart}
 * (9:30 AM) for lateness math instead of the real 3:30 PM shift, producing wildly wrong
 * "Xh late" figures.
 *
 * The lateness recompute exists because {@code Attendance.lateByMinutes} is computed once, at
 * check-in time, and stored — it is never re-derived afterwards. Every row checked in while an
 * employee had no shift assignment (or while the shift row itself was mid-correction above) has
 * a stale, wrong value baked in. This reruns the exact same "minutes past shift-start + grace"
 * math AttendanceService.checkIn uses, now that the real shift is guaranteed to be in place, so
 * historical "Xh late" figures match the 3:30 PM shift instead of whatever was live when the
 * employee first punched in. AttendanceException.minutesLate self-corrects afterwards — it is
 * re-upserted from Attendance.lateByMinutes on every exceptions-dashboard load.
 */
// Must run before StaleAttendanceSweeper's startup pass: the sweep's shift-end cutoff reads
// each employee's Shift.endTime, which this corrector may still be about to fix.
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
@Slf4j
public class ShiftSeedCorrector implements ApplicationRunner {

    private static final String SHIFT_NAME = "Regular Shift";
    private static final LocalTime INTENDED_START = LocalTime.of(15, 30);
    private static final LocalTime INTENDED_END = LocalTime.of(0, 30);
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendanceProperties attendanceProperties;

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
            backfillUnassignedEmployees(shift);
            recomputeLateArrivals(shift);
        });
    }

    private void backfillUnassignedEmployees(Shift shift) {
        List<Employee> unassigned = employeeRepository.findByShiftIsNull();
        if (unassigned.isEmpty()) {
            return;
        }
        log.warn("Assigning '{}' shift to {} employee(s) with no shift set (onboarded after V95's "
                + "one-time backfill)", SHIFT_NAME, unassigned.size());
        unassigned.forEach(employee -> employee.setShift(shift));
        employeeRepository.saveAll(unassigned);
    }

    private void recomputeLateArrivals(Shift defaultShift) {
        Map<UUID, Shift> shiftByEmployeeId = new HashMap<>();
        for (Employee employee : employeeRepository.findAll()) {
            shiftByEmployeeId.put(employee.getUserId(),
                    employee.getShift() != null ? employee.getShift() : defaultShift);
        }

        List<Attendance> all = attendanceRepository.findAll();
        List<Attendance> toFix = new java.util.ArrayList<>();
        for (Attendance record : all) {
            // Every checked-in day carries a lateByMinutes figure ("Arrival: Xh late" on the
            // Attendance page) regardless of how the day ended up classified — a HALF_DAY
            // (short-hours) record still needs its arrival time corrected, so status is not
            // used to filter which rows get recomputed. Only PRESENT/LATE rows get their
            // *status* flipped below; HALF_DAY keeps its status (it overrides LATE — see
            // AttendanceService.checkOut) but still gets the corrected lateByMinutes.
            if (record.getCheckInAt() == null) {
                continue;
            }
            Shift employeeShift = shiftByEmployeeId.get(record.getEmployeeUserId());
            LocalTime shiftStart = employeeShift != null ? employeeShift.getStartTime() : attendanceProperties.getShiftStart();
            LocalTime deadline = shiftStart.plusMinutes(attendanceProperties.getLateGraceMinutes());
            LocalTime checkInTime = record.getCheckInAt().toLocalTime();
            int lateByMinutes = checkInTime.isAfter(deadline)
                    ? (int) Duration.between(deadline, checkInTime).toMinutes()
                    : 0;

            if (record.getLateByMinutes() == null || lateByMinutes != record.getLateByMinutes()) {
                record.setLateByMinutes(lateByMinutes);
                if (STATUS_PRESENT.equals(record.getStatus()) || STATUS_LATE.equals(record.getStatus())) {
                    record.setStatus(lateByMinutes > 0 ? STATUS_LATE : STATUS_PRESENT);
                }
                toFix.add(record);
            }
        }

        if (!toFix.isEmpty()) {
            log.warn("Recomputing lateByMinutes for {} attendance record(s) against the corrected shift timing",
                    toFix.size());
            attendanceRepository.saveAll(toFix);
        }
    }
}
