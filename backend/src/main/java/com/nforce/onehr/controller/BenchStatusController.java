package com.nforce.onehr.controller;

import com.nforce.onehr.dto.bench.BenchStatusRequest;
import com.nforce.onehr.dto.bench.BenchStatusResponse;
import com.nforce.onehr.dto.bench.EndBenchRequest;
import com.nforce.onehr.dto.bench.TeamBenchResponse;
import com.nforce.onehr.service.BenchStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bench-status")
@RequiredArgsConstructor
public class BenchStatusController {

    private final BenchStatusService service;

    /** US-A01: mark an employee as On Bench. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public BenchStatusResponse start(@Valid @RequestBody BenchStatusRequest req, Principal principal) {
        return service.start(principal.getName(), req);
    }

    /** US-A01: end a bench episode (manual close). */
    @PatchMapping("/{id}/end")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public BenchStatusResponse end(@PathVariable UUID id, @RequestBody(required = false) EndBenchRequest req,
                                    Principal principal) {
        return service.end(principal.getName(), id, req);
    }

    /** US-A02: the caller's own benched direct reports. The team is derived from the principal, never a request parameter. */
    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<TeamBenchResponse> team(Principal principal) {
        return service.teamBench(principal.getName());
    }
}
