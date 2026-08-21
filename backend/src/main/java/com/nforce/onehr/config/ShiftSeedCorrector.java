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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfills the "Regular Shift" onto any employee left without a shift assignment, and keeps
 * historical lateness figures in sync with whatever each employee's actual shift currently is.
 *
 * <p><b>No longer force-corrects "Regular Shift"'s own timings.</b> This corrector used to reset
 * "Regular Shift" back to a hardcoded 15:30-00:30 on every startup, from a time before Shift
 * Management existed as a real, admin-editable feature (see {@code OrgService}
 * create/update/toggle/delete-shift) — back then, "Regular Shift" only had a Flyway migration to
 * define it, and migrations on this shared dev DB have a known version-collision problem (see the
 * note in application-local.yml about V11's checksum) that silently dropped two dedicated
 * migrations (V100, V101) meant to fix its timing. That startup safety-net made sense when the
 * ONLY way to change a shift's hours was a migration. Now that Super Admins can edit any shift's
 * hours directly through the UI, forcibly reverting "Regular Shift" back to a stale hardcoded
 * value on every restart would silently undo a legitimate admin edit — exactly the kind of
 * "my assigned shift doesn't reflect after login" bug this corrector was meant to prevent, not
 * cause. If "Regular Shift"'s timing is ever wrong now, fix it through the Shift Management UI
 * (Organization Structure → Shifts) — not here.
 *
 * <p>The employee backfill exists because V95's "assign everyone the Regular Shift" UPDATE only
 * ran once, against whoever existed at that moment — any employee onboarded since (via a flow
 * that doesn't explicitly pick a shift) has a null {@code shift_id}, which silently falls back to
 * {@code AttendanceProperties.shiftStart} for lateness math instead of a real shift, producing
 * wildly wrong "Xh late" figures. This assigns them "Regular Shift" — whatever its current,
 * admin-configured timing actually is — as a sane default, same as it always did.
 *
 * <p>The lateness recompute exists because {@code Attendance.lateByMinutes} is computed once, at
 * check-in time, and stored — it is never re-derived afterwards. Every row checked in while an
 * employee had no shift assignment has a stale, wrong value baked in. This reruns the exact same
 * "minutes past shift-start + grace" math AttendanceService.checkIn uses, now that the employee
 * is guaranteed to have SOME shift, so historical "Xh late" figures match reality instead of
 * whatever fallback was live when the employee first punched in. AttendanceException.minutesLate
 * self-corrects afterwards — it is re-upserted from Attendance.lateByMinutes on every
 * exceptions-dashboard load.
 */
// Must run before StaleAttendanceSweeper's startup pass: the sweep's shift-end cutoff reads
// each employee's Shift.endTime, which this corrector's backfill may still be about to set.
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
@Slf4j
public class ShiftSeedCorrector implements ApplicationRunner {

    private static final String SHIFT_NAME = "Regular Shift";
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
            // Anchored to the record's own workDate (not compared as a bare LocalTime-of-day) so
            // an overnight shift's post-midnight check-in (e.g. 20:30-05:30 shift, 1:11 AM
            // check-in) is correctly measured as hours late instead of reading as "before"
            // shiftStart — see AttendanceService.checkIn / WebClockInService.recomputeDerivedFields.
            LocalDateTime shiftStartAt = LocalDateTime.of(record.getWorkDate(), shiftStart);
            LocalDateTime deadlineAt = shiftStartAt.plusMinutes(attendanceProperties.getLateGraceMinutes());
            LocalDateTime checkInAt = record.getCheckInAt();
            int lateByMinutes = checkInAt.isAfter(deadlineAt)
                    ? (int) Duration.between(deadlineAt, checkInAt).toMinutes()
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
