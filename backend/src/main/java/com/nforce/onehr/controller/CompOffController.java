package com.nforce.onehr.controller;

import com.nforce.onehr.dto.compoff.CompOffDtos.*;
import com.nforce.onehr.service.CompOffPolicyService;
import com.nforce.onehr.service.CompOffReportService;
import com.nforce.onehr.service.CompOffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Comp-Off. Employee self-service (any signed-in user), credit approval (leave:APPROVE), reports
 * (leave:VIEW), rules/adjustments/detection (leave:CONFIGURE:ALL) and the company OT / Comp-Off
 * switch (compensation_policy:CONFIGURE:ALL, Super Admin). Leave requests themselves go through
 * /api/leave with leave type COMP_OFF.
 */
@RestController
@RequestMapping("/api/comp-off")
@RequiredArgsConstructor
public class CompOffController {

    private static final String VIEW = "hasAuthority('leave:VIEW:TEAM')";
    private static final String APPROVE = "hasAuthority('leave:APPROVE:TEAM')";
    private static final String CONFIGURE = "hasAuthority('leave:CONFIGURE:ALL')";
    private static final String POLICY = "hasAuthority('compensation_policy:CONFIGURE:ALL')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CompOffService compOffService;
    private final CompOffPolicyService policyService;
    private final CompOffReportService reportService;

    // ---- every signed-in user
    @GetMapping("/policy")
    public PolicyDto policy() {
        return policyService.getPolicy();
    }

    @GetMapping("/me")
    public MyCompOff mine(Principal principal) {
        return compOffService.mine(principal.getName());
    }

    @PostMapping("/claims/{id}/choose")
    public ClaimDto choose(@PathVariable UUID id, @Valid @RequestBody ChooseRequest req, Principal principal) {
        return compOffService.choose(id, req.choice(), principal.getName());
    }

    // ---- company policy (Super Admin) and rules (HR)
    @GetMapping("/settings")
    @PreAuthorize(VIEW)
    public SettingsDto settings() {
        return policyService.getSettings();
    }

    @PutMapping("/policy")
    @PreAuthorize(POLICY)
    public SettingsDto updatePolicy(@Valid @RequestBody UpdatePolicyRequest req, Principal principal) {
        return policyService.updatePolicy(req, principal.getName());
    }

    @PutMapping("/settings")
    @PreAuthorize(CONFIGURE)
    public SettingsDto updateRules(@Valid @RequestBody UpdateRulesRequest req, Principal principal) {
        return policyService.updateRules(req, principal.getName());
    }

    // ---- detection, approvals, adjustments
    @PostMapping("/detect")
    @PreAuthorize(CONFIGURE)
    public DetectResult detect(@Valid @RequestBody DetectRequest req, Principal principal) {
        return compOffService.detect(req, principal.getName());
    }

    @GetMapping("/claims")
    @PreAuthorize(VIEW)
    public List<ClaimDto> claims(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 Principal principal) {
        return compOffService.claims(from, to, principal.getName());
    }

    @GetMapping("/approvals")
    @PreAuthorize(APPROVE)
    public List<CreditDto> approvals(Principal principal) {
        return compOffService.pendingApprovals(principal.getName());
    }

    @PostMapping("/credits/{id}/decision")
    @PreAuthorize(APPROVE)
    public CreditDto decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req, Principal principal) {
        return compOffService.decide(id, req, principal.getName());
    }

    @PostMapping("/adjustments")
    @PreAuthorize(CONFIGURE)
    public CreditDto adjust(@Valid @RequestBody AdjustRequest req, Principal principal) {
        return compOffService.adjust(req, principal.getName());
    }

    @GetMapping("/employees/{employeeId}/history")
    @PreAuthorize(VIEW)
    public List<EventDto> history(@PathVariable UUID employeeId, Principal principal) {
        return compOffService.history(employeeId, principal.getName());
    }

    // ---- reports
    @GetMapping("/report")
    @PreAuthorize(VIEW)
    public Report report(@RequestParam(required = false) UUID employeeId,
                         @RequestParam(required = false) UUID departmentId,
                         @RequestParam(required = false) UUID managerId,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         Principal principal) {
        return reportService.report(employeeId, departmentId, managerId, from, to, principal.getName());
    }

    @GetMapping("/credits")
    @PreAuthorize(VIEW)
    public List<CreditDto> credits(@RequestParam(required = false) UUID employeeId,
                                   @RequestParam(required = false) UUID departmentId,
                                   @RequestParam(required = false) UUID managerId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   Principal principal) {
        return reportService.credits(employeeId, departmentId, managerId, from, to, principal.getName());
    }

    @GetMapping("/report/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) UUID employeeId,
                                         @RequestParam(required = false) UUID departmentId,
                                         @RequestParam(required = false) UUID managerId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         Principal principal) {
        byte[] body = reportService.export(employeeId, departmentId, managerId, from, to, principal.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"comp-off-report.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(body);
    }
}
