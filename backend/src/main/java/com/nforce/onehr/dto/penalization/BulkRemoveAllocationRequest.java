package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BulkRemoveAllocationRequest {
    @NotEmpty
    private List<UUID> employeeUserIds;
}
