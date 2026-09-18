package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.ShiftVersion;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Value
public class ShiftResponse {
    UUID id;
    String name;
    String code;
    String description;
    // The version currently in effect (as of today) — same shape the P1 surface has always shown,
    // now resolved through Shift Versioning rather than read directly off the Shift row (which no
    // longer carries timing at all — see ShiftVersion's own Javadoc).
    LocalTime startTime;
    LocalTime endTime;
    Integer breakMinutes;
    // Every Shift Version has its own grace (see ShiftVersion.lateGraceMinutes/V168) — no single
    // global value applies across shifts anymore.
    Integer lateGraceMinutes;
    // Null when no future version is scheduled. Present only for a version whose effectiveFrom is
    // strictly after today — "at most one pending version per Shift" is enforced in OrgService.
    LocalDate pendingEffectiveFrom;
    LocalTime pendingStartTime;
    LocalTime pendingEndTime;
    Integer pendingBreakMinutes;
    Integer pendingLateGraceMinutes;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;
    // "Applicable Days" — see Shift.workingDays's own Javadoc. Always 7 entries or fewer, never
    // empty; a pre-existing Shift with no workingDays set (created before this field existed)
    // reads as all 7 days, matching the same default a brand-new Shift gets.
    List<String> workingDays;

    // flexible intentionally excluded from the P1 surface — see CreateShiftRequest's own comment.
    public static ShiftResponse from(Shift s, ShiftVersion currentVersion, ShiftVersion pendingVersionOrNull, long employeeCount) {
        return new ShiftResponse(s.getId(), s.getName(), s.getCode(), s.getDescription(),
                currentVersion.getStartTime(), currentVersion.getEndTime(), currentVersion.getBreakMinutes(),
                currentVersion.getLateGraceMinutes(),
                pendingVersionOrNull != null ? pendingVersionOrNull.getEffectiveFrom() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getStartTime() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getEndTime() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getBreakMinutes() : null,
                pendingVersionOrNull != null ? pendingVersionOrNull.getLateGraceMinutes() : null,
                s.isActive(), employeeCount, s.getCreatedAt(), workingDaysOf(s));
    }

    private static List<String> workingDaysOf(Shift s) {
        String workingDays = (s.getWorkingDays() == null || s.getWorkingDays().isBlank())
                ? Shift.ALL_WORKING_DAYS
                : s.getWorkingDays();
        return Arrays.stream(workingDays.split(",")).map(String::trim).toList();
    }
}
