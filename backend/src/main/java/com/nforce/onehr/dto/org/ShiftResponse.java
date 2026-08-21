package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Shift;
import lombok.Value;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Value
public class ShiftResponse {
    UUID id;
    String name;
    String code;
    String description;
    LocalTime startTime;
    LocalTime endTime;
    boolean flexible;
    Integer breakMinutes;
    List<String> workingDays;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;

    public static ShiftResponse from(Shift s, long employeeCount) {
        List<String> days = (s.getWorkingDays() == null || s.getWorkingDays().isBlank())
                ? List.of()
                : Arrays.stream(s.getWorkingDays().split(",")).map(String::trim).toList();
        return new ShiftResponse(s.getId(), s.getName(), s.getCode(), s.getDescription(),
                s.getStartTime(), s.getEndTime(), s.isFlexible(), s.getBreakMinutes(), days,
                s.isActive(), employeeCount, s.getCreatedAt());
    }
}
