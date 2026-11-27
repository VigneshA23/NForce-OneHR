package com.nforce.onehr.controller;

import com.nforce.onehr.dto.LeaveConfigDtos.AdjustYearEndLineRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.StartYearEndRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunDetail;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunLineDto;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunSummary;
import com.nforce.onehr.service.LeaveYearEndWizardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** LV-05: the leave year-end wizard (Preview → Adjust → Confirm, rollback within the window). */
@RestController
@RequestMapping("/api/leave-config/year-end")
@RequiredArgsConstructor
public class LeaveYearEndController {

    private final LeaveYearEndWizardService wizardService;

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<YearEndRunSummary> runs(@RequestParam int fromYear) {
        return wizardService.runs(fromYear);
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public YearEndRunDetail run(@PathVariable UUID runId) {
        return wizardService.detail(runId);
    }

    /** A fresh preview of the year (replaces the previous preview). */
    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public YearEndRunDetail preview(@Valid @RequestBody StartYearEndRequest req, Principal principal) {
        return wizardService.preview(req.fromYear(), principal.getName());
    }

    @PatchMapping("/runs/{runId}/lines/{lineId}")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public YearEndRunLineDto adjust(@PathVariable UUID runId, @PathVariable UUID lineId,
                                    @Valid @RequestBody AdjustYearEndLineRequest req, Principal principal) {
        return wizardService.adjust(runId, lineId, req, principal.getName());
    }

    @PostMapping("/runs/{runId}/confirm")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public YearEndRunDetail confirm(@PathVariable UUID runId, Principal principal) {
        return wizardService.confirm(runId, principal.getName());
    }

    @PostMapping("/runs/{runId}/rollback")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public YearEndRunDetail rollback(@PathVariable UUID runId, Principal principal) {
        return wizardService.rollback(runId, principal.getName());
    }
}
