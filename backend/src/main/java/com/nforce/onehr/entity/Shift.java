package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shifts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shift {

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

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // Stable, non-name-based identity marker for the organization's Default Shift (see
    // DEFAULT_SHIFT_NAME's own Javadoc) — set once by migration V232 (backfilled onto the row
    // whose name was "Default Shift" at that time) and never written by the ordinary Shift
    // create/update API, so no other shift can ever acquire it through normal use. A DB-level
    // partial unique index caps this to at most one TRUE row. OrgService's rename/deactivate/
    // delete guards check this flag in addition to (never instead of) the legacy name match — see
    // OrgService#isDefaultShift — so renaming this row away still leaves it fully protected,
    // unlike the name-only check that preceded this column.
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
