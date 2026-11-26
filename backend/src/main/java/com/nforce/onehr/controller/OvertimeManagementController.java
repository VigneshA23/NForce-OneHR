package com.nforce.onehr.controller;

import com.nforce.onehr.dto.overtime.OtDtos.*;
import com.nforce.onehr.service.OtAdjustmentService;
import com.nforce.onehr.service.OtCalculationService;
import com.nforce.onehr.service.OtConfigService;
import com.nforce.onehr.service.OtPayrollSummaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HR/Admin overtime management: attendance-driven calculation and review, rate rules and settings,
 * adjustments, and the payroll OT summary. Review/run actions need overtime:APPROVE:ALL; rates,
 * settings and adjustment approval need overtime:CONFIGURE:ALL.
 */
@RestController
@RequestMapping("/api/overtime-management")
@RequiredArgsConstructor
public class OvertimeManagementController {

    private static final String REVIEW = "hasAuthority('overtime:APPROVE:ALL')";
    private static final String CONFIGURE = "hasAuthority('overtime:CONFIGURE:ALL')";

    private final OtCalculationService calculationService;
    private final OtConfigService configService;
    private final OtAdjustmentService adjustmentService;
    private final OtPayrollSummaryService payrollSummaryService;

    // ---- calculation & review
    @PostMapping("/calculations/run")
    @PreAuthorize(REVIEW)
    public RunResult run(@Valid @RequestBody RunRequest req, Principal principal) {
        return calculationService.run(req, principal.getName());
    }

    @GetMapping("/calculations")
    @PreAuthorize(REVIEW)
    public List<CalculationDto> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID employeeId) {
        return calculationService.list(from, to, status, departmentId, employeeId);
    }

    @PostMapping("/calculations/decision")
    @PreAuthorize(REVIEW)
    public Map<String, Integer> decide(@Valid @RequestBody DecisionRequest req, Principal principal) {
        return Map.of("updated", calculationService.decide(req, principal.getName()));
    }

    @GetMapping("/calculations/{id}/history")
    @PreAuthorize(REVIEW)
    public List<HistoryDto> history(@PathVariable UUID id) {
        return calculationService.history(id);
    }

    // ---- adjustments
    @PostMapping("/calculations/{id}/adjust")
    @PreAuthorize(REVIEW)
    public ResponseEntity<AdjustmentDto> adjust(@PathVariable UUID id, @Valid @RequestBody AdjustRequest req,
                                                Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adjustmentService.request(id, req, principal.getName()));
    }

    @GetMapping("/adjustments")
    @PreAuthorize(REVIEW)
    public List<AdjustmentDto> adjustments(@RequestParam(required = false) UUID calculationId) {
        return adjustmentService.list(calculationId);
    }

    @PostMapping("/adjustments/{id}/decision")
    @PreAuthorize(CONFIGURE)
    public AdjustmentDto decideAdjustment(@PathVariable UUID id, @Valid @RequestBody AdjustmentDecisionRequest req,
                                          Principal principal) {
        return adjustmentService.decide(id, req, principal.getName());
    }

    // ---- settings & rate rules
    @GetMapping("/settings")
    @PreAuthorize(REVIEW)
    public SettingsDto settings() {
        return configService.getSettings();
    }

    @PutMapping("/settings")
    @PreAuthorize(CONFIGURE)
    public SettingsDto updateSettings(@Valid @RequestBody UpdateSettingsRequest req, Principal principal) {
        return configService.updateSettings(req, principal.getName());
    }

    @GetMapping("/rate-rules")
    @PreAuthorize(REVIEW)
    public List<RateRuleDto> rules() {
        return configService.listRules();
    }

    @PostMapping("/rate-rules")
    @PreAuthorize(CONFIGURE)
    public ResponseEntity<RateRuleDto> createRule(@Valid @RequestBody RateRuleRequest req, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createRule(req, principal.getName()));
    }

    @PutMapping("/rate-rules/{id}")
    @PreAuthorize(CONFIGURE)
    public RateRuleDto updateRule(@PathVariable UUID id, @Valid @RequestBody RateRuleRequest req, Principal principal) {
        return configService.updateRule(id, req, principal.getName());
    }

    @DeleteMapping("/rate-rules/{id}")
    @PreAuthorize(CONFIGURE)
    public ResponseEntity<Void> deactivateRule(@PathVariable UUID id, Principal principal) {
        configService.deactivateRule(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    // ---- payroll integration
    @GetMapping("/payroll/{runId}/summary")
    @PreAuthorize(REVIEW)
    public PayrollOtSummary payrollSummary(@PathVariable UUID runId) {
        return payrollSummaryService.summary(runId);
    }
}
