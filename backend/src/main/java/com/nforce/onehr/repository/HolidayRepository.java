package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByLocation_IdAndActiveTrueOrderByHolidayDateAsc(UUID locationId);

    // Backs WorkingDayService's bulk computation — one query for every location across a team,
    // instead of one findByLocation_IdAndActiveTrueOrderByHolidayDateAsc call per employee.
    List<Holiday> findByLocation_IdInAndActiveTrue(Collection<UUID> locationIds);

    List<Holiday> findByActiveTrueOrderByHolidayDateAsc();

    boolean existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(String holidayName, LocalDate holidayDate, UUID locationId);

    boolean existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(
            String holidayName, LocalDate holidayDate, UUID locationId, UUID excludeId);

    // Backs OrgService.deleteLocation — holidays.location_id is NOT NULL, so unlike the
    // Employee/Asset FKs it can't just be nulled out; these rows are location-owned calendar
    // config (not employee attendance/audit history), so deleting them alongside their location
    // is the schema-compatible way to permanently remove a location with 0 current employees.
    @Modifying
    @Query("DELETE FROM Holiday h WHERE h.location.id = :locationId")
    void deleteByLocationId(@Param("locationId") UUID locationId);
}
