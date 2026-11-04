package com.nforce.onehr.dto.allocation;

import com.nforce.onehr.entity.ProjectStatus;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProjectResponse {
    private UUID id;
    private String code;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private UUID ownerEmployeeId;
    private String ownerName;
    private UUID departmentId;
    private String departmentName;
    private UUID clientId;
    private String clientName;
    private ProjectStatus status;
}
