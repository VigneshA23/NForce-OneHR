package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.ArrearsResponse;
import com.nforce.onehr.service.ArrearsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Keka's "Arrears" step — read-only (see Arrears' own doc comment for how rows are created).
 * HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/arrears")
@RequiredArgsConstructor
public class ArrearsController {

    private final ArrearsService arrearsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<ArrearsResponse> list(@PathVariable UUID runId) {
        return arrearsService.listForRun(runId);
    }
}
