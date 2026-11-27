package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One employee's non-working days for leave: the active holidays of their Location and the weekly offs of
 * the Weekly Off Policy governing each date (Saturday/Sunday when none is assigned), resolved per date
 * through {@link WorkingDayService}'s shared batch resolver. Used by the leave rules (LV-02) and the day
 * count (LV-07).
 */
@Component
@RequiredArgsConstructor
public class LeaveCalendar {

    private final EmployeeRepository employeeRepository;
    private final HolidayRepository holidayRepository;
    private final WorkingDayService workingDayService;

    /** The employee's calendar; holidays are loaded once, weekly offs resolved per date. */
    public interface Days {
        /** The holiday's name, or null. */
        String holiday(LocalDate date);

        boolean weeklyOff(LocalDate date);

        default boolean nonWorking(LocalDate date) {
            return holiday(date) != null || weeklyOff(date);
        }

        /** The nearest working day before {@code date} (at most a year back), or null. */
        default LocalDate previousWorkingDay(LocalDate date) {
            LocalDate d = date.minusDays(1);
            for (int i = 0; i < 366 && nonWorking(d); i++) d = d.minusDays(1);
            return nonWorking(d) ? null : d;
        }

        /** The nearest working day after {@code date} (at most a year ahead), or null. */
        default LocalDate nextWorkingDay(LocalDate date) {
            LocalDate d = date.plusDays(1);
            for (int i = 0; i < 366 && nonWorking(d); i++) d = d.plusDays(1);
            return nonWorking(d) ? null : d;
        }
    }

    @Transactional(readOnly = true)
    public Days forEmployee(UUID employeeUserId) {
        Map<LocalDate, String> holidays = employeeRepository.findById(employeeUserId)
                .map(Employee::getLocation)
                .map(location -> holidayRepository.findByLocation_IdAndActiveTrueOrderByHolidayDateAsc(location.getId())
                        .stream().collect(Collectors.toMap(Holiday::getHolidayDate, Holiday::getHolidayName, (a, b) -> a)))
                .orElse(Map.of());
        WorkingDayService.WeeklyOffBatchResolver offDays = workingDayService.weeklyOffDaysResolver(List.of(employeeUserId));
        return new Days() {
            @Override
            public String holiday(LocalDate date) {
                return holidays.get(date);
            }

            @Override
            public boolean weeklyOff(LocalDate date) {
                return offDays.offDaysOn(employeeUserId, date).contains(date.getDayOfWeek());
            }
        };
    }
}
