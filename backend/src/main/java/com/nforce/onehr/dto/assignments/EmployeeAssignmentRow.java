package com.nforce.onehr.dto.assignments;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** One row of the Employee Assignments table (ONEHR-108). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeAssignmentRow {

    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private String departmentName;
    private String locationName;

    // The employee's own effective timezone (Location.timezone) — shown alongside shift start/
    // end so a viewer in a different timezone (e.g. India HR looking at a US employee) can tell
    // what timezone the configured shift time is actually in, rather than assuming their own.
    // See AttendanceService.zoneIdFor for the same resolution used at attendance-calculation time.
    private String employeeTimezone;

    private UUID shiftId;
    private String shiftName;
    private LocalTime shiftStartTime;
    private LocalTime shiftEndTime;
    // The CURRENTLY-effective EmployeeShiftAssignment's own effectiveFrom (resolved via
    // EmployeeShiftAssignmentResolver, as of today — never Employee.shift, a best-effort display
    // cache) — null when the employee has no effective assignment at all (NO_SHIFT_ASSIGNED).
    // Powers "Active since <date>" in the UI.
    private LocalDate shiftEffectiveSince;
    // A SCHEDULED future assignment (effectiveFrom strictly after today), if one exists — at most
    // one ever does (see EmployeeAssignmentService#assignShift). Null when there is none. Powers
    // "Scheduled — Effective from <date>" in the UI, distinct from the currently-effective shift
    // above (which remains active until this date arrives).
    private UUID pendingShiftId;
    private String pendingShiftName;
    private LocalDate pendingShiftEffectiveFrom;
    private UUID weeklyOffPolicyId;
    private String weeklyOffPolicyName;
    private UUID penalisationPolicyId;
    private String penalisationPolicyName;
}
