package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateHolidayRequest;
import com.nforce.onehr.dto.HolidayResponse;
import com.nforce.onehr.service.HolidayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @GetMapping("/my-location")
    public List<HolidayResponse> getHolidaysForMyLocation(Authentication authentication) {
        return holidayService.getHolidaysForMyLocation(authentication.getName());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<HolidayResponse> getHolidaysByLocation(@RequestParam UUID locationId) {
        return holidayService.getHolidaysByLocation(locationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public HolidayResponse createHoliday(@Valid @RequestBody CreateHolidayRequest req) {
        return holidayService.createHoliday(req);
    }
}
