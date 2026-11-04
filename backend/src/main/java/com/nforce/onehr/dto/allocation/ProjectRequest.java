package com.nforce.onehr.dto.allocation;

import com.nforce.onehr.entity.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-B01/US-B02/US-B03: create or update a project. {@code status} defaults to ACTIVE on create when omitted. */
@Getter @Setter
public class ProjectRequest {

    @NotBlank(message = "Project code is required")
    @Size(max = 50, message = "Project code must be at most 50 characters")
    private String code;

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must be at most 255 characters")
    private String name;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    private LocalDate endDate;

    /** US-B03: the Manager who allocates and releases resources on this project. */
    private UUID ownerEmployeeId;

    private UUID departmentId;

    /** US-B02: optional client / cost-center tag. */
    private UUID clientId;

    private ProjectStatus status;
}
