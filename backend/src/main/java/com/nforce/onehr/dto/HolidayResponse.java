package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class HolidayResponse {
    private UUID id;
    private String holidayName;
    private LocalDate holidayDate;
    private String locationId;
    private String locationName;
    private boolean active;
}
