package com.nforce.onehr.controller;

import com.nforce.onehr.dto.exceptions.ExceptionResponse;
import com.nforce.onehr.service.ExceptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/exceptions")
@RequiredArgsConstructor
public class ExceptionController {

    private final ExceptionService exceptionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN','SUPER_ADMIN','MANAGER')")
    public List<ExceptionResponse> listExceptions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        LocalDate effTo = to != null ? to : LocalDate.now();
        LocalDate effFrom = from != null ? from : effTo.minusDays(6);
        return exceptionService.getExceptionsForCaller(principal.getName(), effFrom, effTo);
    }
}
