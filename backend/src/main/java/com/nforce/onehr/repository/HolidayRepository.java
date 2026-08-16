package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByLocation_IdAndActiveTrue(UUID locationId);

    // Backs WorkingDayService's bulk computation — one query for every location across a team,
    // instead of one findByLocation_IdAndActiveTrue call per employee.
    List<Holiday> findByLocation_IdInAndActiveTrue(Collection<UUID> locationIds);

    List<Holiday> findByActiveTrueOrderByHolidayDateAsc();

    boolean existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(String holidayName, LocalDate holidayDate, UUID locationId);

    boolean existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(
            String holidayName, LocalDate holidayDate, UUID locationId, UUID excludeId);
}
