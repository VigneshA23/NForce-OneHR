package com.nforce.onehr.dto.attendance;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Result of {@link com.nforce.onehr.service.WorkingDayService} for one employee over a date
 * range: the exact set of dates that count as a working day, after applying the joining-date
 * clamp, weekly-off policy, location holidays, and approved leave. Exposed as a set (not just a
 * count) so per-day callers — e.g. the Team Punctuality daily chart — can tell which specific
 * dates in the range were working days for this employee.
 */
@Getter
@Builder
public class WorkingDaySchedule {

    private UUID employeeUserId;

    @Builder.Default
    private Set<LocalDate> workingDates = Set.of();

    public int getExpectedWorkingDays() {
        return workingDates.size();
    }
}
