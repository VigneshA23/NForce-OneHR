package com.nforce.onehr.controller;

import com.nforce.onehr.dto.exceptions.PlaceholderCheckinRequest;
import com.nforce.onehr.dto.exceptions.PlaceholderCheckinResponse;
import com.nforce.onehr.service.ExceptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * TEMPORARY — delete this controller (and PlaceholderCheckinSeed's
 * entity/repository/service methods/migration) in the same PR that lands
 * FR-004 Attendance Management. Not part of the permanent Exception Dashboard API.
 */
@RestController
@RequestMapping("/api/exceptions/placeholder-checkins")
@RequiredArgsConstructor
public class PlaceholderCheckinController {

    private final ExceptionService exceptionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN','SUPER_ADMIN')")
    public PlaceholderCheckinResponse seed(@Valid @RequestBody PlaceholderCheckinRequest req, Principal principal) {
        return exceptionService.seedPlaceholderCheckin(req, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN','SUPER_ADMIN')")
    public List<PlaceholderCheckinResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effTo = to != null ? to : LocalDate.now();
        LocalDate effFrom = from != null ? from : effTo.minusDays(6);
        return exceptionService.listPlaceholderCheckins(effFrom, effTo);
    }
}
