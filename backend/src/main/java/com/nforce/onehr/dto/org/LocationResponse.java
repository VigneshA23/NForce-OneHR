package com.nforce.onehr.dto.org;

import com.nforce.onehr.entity.Location;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class LocationResponse {
    UUID id;
    String name;
    String city;
    String state;
    String country;
    String holidayRegion;
    boolean active;
    long employeeCount;
    LocalDateTime createdAt;

    public static LocationResponse from(Location l, long employeeCount) {
        return new LocationResponse(l.getId(), l.getName(), l.getCity(), l.getState(), l.getCountry(),
                l.getHolidayRegion(), l.isActive(), employeeCount, l.getCreatedAt());
    }
}
