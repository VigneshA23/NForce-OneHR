package com.nforce.onehr.dto.allocation;

import com.nforce.onehr.entity.ProjectStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-B08: one of the caller's OWN current or upcoming project allocations — a read-only projection
 * carrying only what the My Project Allocations section shows. Deliberately has no employee id/name
 * (it is always the caller), no capacity totals and no warning fields.
 *
 * <p>{@code startDate}/{@code endDate} are the dates in force: the allocation's range (its end being the
 * {@code actualEndDate} when released early), clipped to the project's current dates, or the project's own
 * dates when the allocation no longer overlaps them — the same dates a manager sees for the project. {@code projectName}/{@code clientName} are null when the
 * project or its client is missing or untagged — the UI renders a placeholder, never an error.
 */
@Getter
@Builder
@AllArgsConstructor
public class MyAllocationResponse {

    public enum Status { CURRENT, UPCOMING }

    private final UUID id;
    private final String projectName;
    private final String clientName;
    private final Integer capacityPercent;
    private final LocalDate startDate;
    private final LocalDate endDate;
    /** The project's status as shown today (ACTIVE / ON_HOLD / CLOSED); an ACTIVE project past its end date counts as CLOSED. Null if the project row is missing. */
    private final ProjectStatus projectStatus;
    private final Status status;
}
