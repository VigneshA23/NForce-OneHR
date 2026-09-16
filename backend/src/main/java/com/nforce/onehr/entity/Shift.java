package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "shifts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shift {

    // All 7 days, Monday-first, in the same comma-separated form workingDays itself is stored in
    // — the single source of truth both a brand-new Shift's default (OrgService#createShift) and
    // a pre-existing Shift's "never set" read-back (ShiftResponse#from) derive from, so the two
    // never risk independently drifting out of sync.
    public static final String ALL_WORKING_DAYS =
            Arrays.stream(DayOfWeek.values()).map(Enum::name).collect(Collectors.joining(","));

    // The organization's default/seeded shift, still offered as an ordinary pickable option
    // wherever any other Shift is (Add User's shift dropdown, Bulk-Edit Team Assignment, etc.).
    // No longer auto-assigned to a new employee who doesn't explicitly pick a Shift (ONEHR-355
    // follow-up) — a shift-less employee is now a valid, permanent state (see
    // AttendanceInterpretationService's NO_SHIFT_ASSIGNED handling), never silently backfilled
    // onto this shift just to satisfy a schema invariant that no longer exists. Looked up by this
    // stable name (where still referenced) — never by a hardcoded id, since ids differ per
    // environment/seed run.
    //
    // Originally seeded (V95) and referred to throughout the codebase as "Regular Shift"; renamed
    // to the canonical "Default Shift" here in V161, which also renames the existing row in place
    // (same id, so every employee's shift_id assignment is untouched) on any environment that
    // still has the old name. See V161's own comment for the exact rename rules.
    public static final String DEFAULT_SHIFT_NAME = "Default Shift";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(unique = true, length = 30)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Timing (start/end/break) is NOT here — it lives on ShiftVersion, effective-dated, resolved
    // via ShiftVersionResolver. This entity is purely the logical identity: the thing an Employee
    // is assigned to, the row that appears once in the Shifts tab, the thing name/code/active
    // validation applies to — see ShiftVersion's own Javadoc for the full rationale (a mutable
    // timing field here would let editing an in-use Shift silently reinterpret already-recorded
    // Attendance/Exceptions/Penalties for historical dates).
    @Column(nullable = false)
    @Builder.Default
    private boolean flexible = false;

    // "Applicable Days" in the UI — comma-separated java.time.DayOfWeek names, e.g.
    // "MONDAY,TUESDAY", same convention as WeeklyOffPolicy.offDays. Set at creation (see
    // OrgService#createShift; defaults to ALL_WORKING_DAYS) and optionally replaced immediately
    // by updateShift — never versioned/effective-dated, unlike startTime/endTime/etc, since
    // nothing below ever reads it (see OrgService#applyApplicableDaysUpdate's own comment).
    // Purely a display/record attribute — deliberately NOT read by ShiftDayPolicy,
    // WorkingDayService, or any other workday/weekly-off computation, which all remain sourced
    // exclusively from the employee's own WeeklyOffPolicy; wiring this into any of them would
    // reintroduce the exact conflict this field was once pulled off the API surface for. Null
    // only for a shift created before this field existed (treated as "all 7 days" on read — see
    // ShiftResponse#from).
    @Column(name = "working_days", length = 60)
    private String workingDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
