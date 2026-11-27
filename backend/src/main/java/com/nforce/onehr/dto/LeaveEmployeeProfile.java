package com.nforce.onehr.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * The employee facts leave crediting and "applies to" rules need (V314), loaded in one query
 * instead of whole Employee graphs. {@code employed} = user active and not deleted. LV-06 adds marital
 * status, the designation's grade and the probation / confirmation dates.
 */
public record LeaveEmployeeProfile(UUID userId, String gender, UUID departmentId, UUID locationId, String employmentType,
                                   LocalDate joiningDate, LocalDate lastWorkingDay, boolean active, Instant deletedAt,
                                   String maritalStatus, String grade, LocalDate probationEndDate, LocalDate confirmationDate) {

    /** Without the LV-06 facts. */
    public LeaveEmployeeProfile(UUID userId, String gender, UUID departmentId, UUID locationId, String employmentType,
                                LocalDate joiningDate, LocalDate lastWorkingDay, boolean active, Instant deletedAt) {
        this(userId, gender, departmentId, locationId, employmentType, joiningDate, lastWorkingDay, active, deletedAt,
                null, null, null, null);
    }

    /**
     * On probation on {@code date}: before the confirmation date, or — with no confirmation date on file — on or
     * before the probation end date. No dates at all = not on probation.
     */
    public boolean onProbation(LocalDate date) {
        if (confirmationDate != null) return date.isBefore(confirmationDate);
        return probationEndDate != null && !date.isAfter(probationEndDate);
    }

    /** The first day after probation, or null when it isn't known. */
    public LocalDate probationOver() {
        if (confirmationDate != null) return confirmationDate;
        return probationEndDate != null ? probationEndDate.plusDays(1) : null;
    }

    public boolean employed() {
        return active && deletedAt == null;
    }
}
