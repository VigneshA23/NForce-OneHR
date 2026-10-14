package com.nforce.onehr.controller;

import com.nforce.onehr.dto.bench.BenchDtos.AgingConfigRequest;
import com.nforce.onehr.dto.bench.BenchDtos.AllocationOption;
import com.nforce.onehr.dto.bench.BenchDtos.AgingConfigResponse;
import com.nforce.onehr.dto.bench.BenchDtos.BenchCost;
import com.nforce.onehr.dto.bench.BenchDtos.BenchDashboard;
import com.nforce.onehr.dto.bench.BenchDtos.BenchListRow;
import com.nforce.onehr.dto.bench.BenchStatusRequest;
import com.nforce.onehr.dto.bench.BenchStatusResponse;
import com.nforce.onehr.dto.bench.EndBenchRequest;
import com.nforce.onehr.dto.bench.TeamBenchResponse;
import com.nforce.onehr.service.BenchAgingService;
import com.nforce.onehr.service.BenchReportService;
import com.nforce.onehr.service.BenchStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/bench-status")
@RequiredArgsConstructor
public class BenchStatusController {

    private static final String HR = "hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')";

    private final BenchStatusService service;
    private final BenchReportService reportService;
    private final BenchAgingService agingService;

    /** US-A01: mark an employee as On Bench. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(HR)
    public BenchStatusResponse start(@Valid @RequestBody BenchStatusRequest req, Principal principal) {
        return service.start(principal.getName(), req);
    }

    /** US-A06: end a bench episode — with an allocation, or with a close-out reason. */
    @PatchMapping("/{id}/end")
    @PreAuthorize(HR)
    public BenchStatusResponse end(@PathVariable UUID id, @RequestBody(required = false) EndBenchRequest req,
                                    Principal principal) {
        return service.end(principal.getName(), id, req == null ? new EndBenchRequest() : req);
    }

    /** US-A02: the caller's own benched direct reports. The team is derived from the principal, never a request parameter. */
    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<TeamBenchResponse> team(Principal principal) {
        return service.teamBench(principal.getName());
    }

    /** US-A04/US-A08: the active bench list, filterable by aging bucket and skills (AND/OR). */
    @GetMapping
    @PreAuthorize(HR)
    public List<BenchListRow> list(@RequestParam(required = false) String bucket,
                                    @RequestParam(required = false) Set<UUID> skillIds,
                                    @RequestParam(defaultValue = "OR") String mode) {
        return reportService.list(bucket, skillIds, mode);
    }

    /** US-A06: allocations the employee of this episode could be linked to when ending bench. */
    @GetMapping("/{id}/allocations")
    @PreAuthorize(HR)
    public List<AllocationOption> allocationOptions(@PathVariable UUID id) {
        return reportService.allocationOptions(id);
    }

    /** US-A03: headcount, bench %, bucket counts and the 90-day trend. */
    @GetMapping("/dashboard")
    @PreAuthorize(HR)
    public BenchDashboard dashboard() {
        return reportService.dashboard();
    }

    /** US-A13: aggregate bench cost by compensation band. */
    @GetMapping("/cost")
    @PreAuthorize(HR)
    public BenchCost cost() {
        return reportService.cost();
    }

    @GetMapping("/aging-config")
    @PreAuthorize(HR)
    public AgingConfigResponse agingConfig() {
        return agingService.getConfig();
    }

    /** US-A04/US-A05: replace the aging buckets and the notification threshold. */
    @PutMapping("/aging-config")
    @PreAuthorize(HR)
    public AgingConfigResponse saveAgingConfig(@Valid @RequestBody AgingConfigRequest req) {
        return agingService.saveConfig(req);
    }
}
