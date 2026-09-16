package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class CreateShiftRequest {

    @NotBlank(message = "Shift name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 30)
    private String code;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private Integer breakMinutes;

    // Minutes past startTime forgiven before a punch counts as LATE — every Shift has its own
    // (see ShiftVersion.lateGraceMinutes/V168); null defaults to 10, matching the pre-migration
    // global default so an admin who doesn't touch this field gets identical behavior to before.
    private Integer lateGraceMinutes;

    // flexible intentionally remains off the P1 surface (P2 — no shift in P1 has anything but a
    // fixed start/end).
    //
    // workingDays ("Applicable Days" in the UI) — java.time.DayOfWeek names, e.g.
    // ["MONDAY", "TUESDAY"]. Purely a display/record attribute of the Shift itself: it is NOT
    // consumed by any weekly-off/workday decision (WeeklyOffPolicy remains the sole source of
    // truth there — see OrgService's own comment) and must never become one, to avoid the exact
    // conflict this field was previously pulled off the API surface for. Optional: null/omitted
    // defaults to all 7 days (see OrgService#createShift); an explicitly empty list is rejected —
    // at least one applicable day is always required.
    private List<String> workingDays;
}
