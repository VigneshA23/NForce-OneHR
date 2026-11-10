package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-C04 — recomputes LOP days for the pay cycle (resolved via payGroupId + referenceDate) containing referenceDate. */
@Getter
@Setter
public class RecomputeLopRequest {

    @NotNull
    private UUID employeeId;

    @NotNull
    private UUID payGroupId;

    /** Defaults to today when omitted. */
    private LocalDate referenceDate;

    /**
     * US-C22/US-C23 — the {@code PayrollRun} this recompute is for, so review-item decisions
     * (rejected unpaid-leave requests, no-attendance dates marked LOP) are reflected in the
     * result. Null for the standalone {@code /api/payroll/lop/recompute} admin endpoint, which
     * isn't tied to any run — same computation as before this story in that case.
     */
    private UUID runId;
}
