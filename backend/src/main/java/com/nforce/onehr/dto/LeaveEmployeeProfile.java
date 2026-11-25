package com.nforce.onehr.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * The employee facts leave crediting and "applies to" rules need (V314), loaded in one query
 * instead of whole Employee graphs. {@code employed} = user active and not deleted.
 */
public record LeaveEmployeeProfile(UUID userId, String gender, UUID departmentId, UUID locationId, String employmentType,
                                   LocalDate joiningDate, LocalDate lastWorkingDay, boolean active, Instant deletedAt) {

    public boolean employed() {
        return active && deletedAt == null;
    }
}
