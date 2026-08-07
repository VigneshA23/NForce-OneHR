package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByLocation_IdAndActiveTrue(UUID locationId);

    List<Holiday> findByActiveTrueOrderByHolidayDateAsc();

    boolean existsByHolidayNameAndHolidayDateAndLocation_Id(String holidayName, LocalDate holidayDate, UUID locationId);
}
