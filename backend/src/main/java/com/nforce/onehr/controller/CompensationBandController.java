package com.nforce.onehr.controller;

import com.nforce.onehr.dto.bench.CompensationBandDtos.AssignmentRequest;
import com.nforce.onehr.dto.bench.CompensationBandDtos.AssignmentResponse;
import com.nforce.onehr.dto.bench.CompensationBandDtos.BandRequest;
import com.nforce.onehr.dto.bench.CompensationBandDtos.BandResponse;
import com.nforce.onehr.service.CompensationBandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** US-A13 inputs: average-CTC bands and employee band assignments (labels only — no salaries). */
@RestController
@RequestMapping("/api/compensation-bands")
@RequiredArgsConstructor
public class CompensationBandController {

    private final CompensationBandService service;

    @GetMapping
    @PreAuthorize("hasAuthority('compensation:VIEW:ALL')")
    public List<BandResponse> bands() {
        return service.bands();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('compensation:CREATE:ALL')")
    @ResponseStatus(HttpStatus.CREATED)
    public BandResponse addBand(@Valid @RequestBody BandRequest req) {
        return service.addBand(req);
    }

    @GetMapping("/assignments")
    @PreAuthorize("hasAuthority('compensation:VIEW:ALL')")
    public List<AssignmentResponse> assignments() {
        return service.assignments();
    }

    @PutMapping("/assignments")
    @PreAuthorize("hasAuthority('compensation:EDIT:ALL')")
    public void assign(@Valid @RequestBody AssignmentRequest req) {
        service.assign(req);
    }
}
