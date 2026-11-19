package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** The full catalog's ids in the desired order — SalaryComponentService#reorder assigns
 * displayOrder = index in one transaction, matching Keka's "Order of components" screen (§3.8). */
@Data
public class ReorderSalaryComponentsRequest {

    @NotEmpty
    private List<UUID> orderedIds;
}
