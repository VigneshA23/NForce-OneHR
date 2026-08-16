package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateHolidayRequest;
import com.nforce.onehr.dto.HolidayResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final LocationRepository locationRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * HR Admin + Super Admin. Creates a holiday for a specific location.
     */
    @Transactional
    public HolidayResponse createHoliday(CreateHolidayRequest req) {
        Location location = locationRepository.findById(req.getLocationId())
                .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        String name = req.getHolidayName().trim();

        if (holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrue(name, req.getHolidayDate(), location.getId())) {
            throw new IllegalArgumentException(
                    "A holiday named '" + name + "' already exists for this location on " + req.getHolidayDate());
        }

        Holiday holiday = Holiday.builder()
                .holidayName(name)
                .holidayDate(req.getHolidayDate())
                .location(location)
                .build();

        holiday = holidayRepository.save(holiday);
        return toResponse(holiday);
    }

    /**
     * HR Admin + Super Admin. Updates an existing holiday's name/date/location.
     */
    @Transactional
    public HolidayResponse updateHoliday(UUID id, CreateHolidayRequest req) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found"));
        Location location = locationRepository.findById(req.getLocationId())
                .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        String name = req.getHolidayName().trim();

        if (holidayRepository.existsByHolidayNameAndHolidayDateAndLocation_IdAndActiveTrueAndIdNot(
                name, req.getHolidayDate(), location.getId(), id)) {
            throw new IllegalArgumentException(
                    "A holiday named '" + name + "' already exists for this location on " + req.getHolidayDate());
        }

        holiday.setHolidayName(name);
        holiday.setHolidayDate(req.getHolidayDate());
        holiday.setLocation(location);

        holiday = holidayRepository.save(holiday);
        return toResponse(holiday);
    }

    /**
     * HR Admin + Super Admin. Soft-deletes a holiday (active = false) — same
     * pattern as every other entity in this codebase; never a hard delete.
     */
    @Transactional
    public void deleteHoliday(UUID id) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found"));
        holiday.setActive(false);
        holidayRepository.save(holiday);
    }

    /**
     * Returns active holidays for the given location. Empty list if none exist.
     */
    @Transactional(readOnly = true)
    public List<HolidayResponse> getHolidaysByLocation(UUID locationId) {
        return holidayRepository.findByLocation_IdAndActiveTrue(locationId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * HR Admin + Super Admin. Returns active holidays across every location,
     * date-ascending. Used when no specific locationId is requested — an HR
     * Admin's own Employee.location (if any) should not limit what they can see.
     */
    @Transactional(readOnly = true)
    public List<HolidayResponse> listAllHolidays() {
        return holidayRepository.findByActiveTrueOrderByHolidayDateAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Any authenticated employee. Resolves the caller's own location from their
     * Employee record and returns only that location's active holidays.
     */
    @Transactional(readOnly = true)
    public List<HolidayResponse> getHolidaysForMyLocation(String email) {
        Employee employee = employeeRepository.findByUser_Email(email)
                .orElseThrow(() -> new IllegalArgumentException("No employee record found for the current user"));
        if (employee.getLocation() == null) {
            return List.of();
        }
        return getHolidaysByLocation(employee.getLocation().getId());
    }

    private HolidayResponse toResponse(Holiday holiday) {
        return HolidayResponse.builder()
                .id(holiday.getId())
                .holidayName(holiday.getHolidayName())
                .holidayDate(holiday.getHolidayDate())
                .locationId(holiday.getLocation().getId().toString())
                .locationName(holiday.getLocation().getName())
                .active(holiday.isActive())
                .build();
    }
}
