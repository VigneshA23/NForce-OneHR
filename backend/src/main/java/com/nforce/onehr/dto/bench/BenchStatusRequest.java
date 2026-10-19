package com.nforce.onehr.dto.bench;

import com.nforce.onehr.entity.BenchReasonCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-A01: mark an employee as On Bench. {@code startDate} defaults to today when omitted. */
@Getter @Setter
public class BenchStatusRequest {

    @NotNull(message = "Employee is required")
    private UUID employeeId;

    @NotNull(message = "Reason is required")
    private BenchReasonCode reasonCode;

    private LocalDate startDate;
}
