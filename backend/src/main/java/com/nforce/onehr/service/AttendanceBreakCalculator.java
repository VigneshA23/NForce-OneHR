package com.nforce.onehr.service;

import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * PN-05: total break minutes for one employee/day, for the Excess Break rule. Same definition the
 * Today's Timings panel uses (AttendanceService#computeBreakMinutes): the sum of the gaps between
 * consecutive closed punch sessions, across both entry points (normal Check-In/Out and Web
 * Check-In/Out), each gap floored at zero because the two kinds of session can overlap in real time.
 * Kept as a leaf component (repositories only) so the exception pipeline can use it without
 * depending on AttendanceService.
 */
@Component
@RequiredArgsConstructor
public class AttendanceBreakCalculator {

    private final AttendancePunchRepository attendancePunchRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;

    /** A session; {@code out} is null while it is still open. */
    private record Session(LocalDateTime in, LocalDateTime out) {}

    public int breakMinutes(UUID employeeUserId, UUID attendanceRecordId, LocalDate workDate) {
        List<Session> sessions = new ArrayList<>();
        if (attendanceRecordId != null) {
            attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId)
                    .forEach(p -> sessions.add(new Session(p.getCheckInAt(), p.getCheckOutAt())));
        }
        webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeUserId, workDate)
                .forEach(r -> sessions.add(new Session(r.getRequestedCheckIn(), r.getCheckedOutAt())));
        sessions.removeIf(s -> s.in() == null);
        sessions.sort(Comparator.comparing(Session::in));

        int minutes = 0;
        for (int i = 0; i < sessions.size() - 1; i++) {
            LocalDateTime gapStart = sessions.get(i).out();
            LocalDateTime gapEnd = sessions.get(i + 1).in();
            if (gapStart != null && gapEnd != null) {
                minutes += Math.max(0, (int) Duration.between(gapStart, gapEnd).toMinutes());
            }
        }
        return minutes;
    }
}
