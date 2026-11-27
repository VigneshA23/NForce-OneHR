package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualCreditResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualRunResult;
import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityPreview;
import com.nforce.onehr.dto.LeaveConfigDtos.CarryForwardTransferResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunResult;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.LeaveAccrualService;
import com.nforce.onehr.service.LeaveJobMonitor;
import com.nforce.onehr.service.LeaveOpeningBalanceService;
import com.nforce.onehr.service.LeaveTypeService;
import com.nforce.onehr.service.LeaveYearEndService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leave Configuration — Organization Masters / Organization Structure > Leave. Separate from leave
 * application/approval: every endpoint needs {@code leave_configuration}, never a {@code leave:*}
 * grant. VIEW reads configuration and balances, CONFIGURE changes policy (types, year-end run; leave
 * plans in {@link LeavePlanController}), EDIT adjusts an individual employee's balance.
 */
@RestController
@RequestMapping("/api/leave-config")
@RequiredArgsConstructor
public class LeaveConfigController {

    private final LeaveTypeService leaveTypeService;
    private final LeaveYearEndService leaveYearEndService;
    private final LeaveAccrualService leaveAccrualService;
    private final UserRepository userRepository;
    private final LeaveJobMonitor leaveJobMonitor;
    private final LeaveOpeningBalanceService openingBalanceService;

    // ── Leave types ──

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<LeaveTypeResponse> listTypes() {
        return leaveTypeService.listForAdmin();
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public LeaveTypeResponse createType(@Valid @RequestBody CreateLeaveTypeRequest req, Principal principal) {
        return leaveTypeService.create(req, principal.getName());
    }

    @PatchMapping("/types/{id}")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public LeaveTypeResponse updateType(@PathVariable UUID id, @Valid @RequestBody UpdateLeaveTypeRequest req,
                                        Principal principal) {
        return leaveTypeService.update(id, req, principal.getName());
    }

    @PatchMapping("/types/{id}/active")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public LeaveTypeResponse setActive(@PathVariable UUID id, @RequestBody Map<String, Boolean> body, Principal principal) {
        Boolean active = body.get("active");
        if (active == null) throw new IllegalArgumentException("active is required");
        return leaveTypeService.setActive(id, active, principal.getName());
    }

    @DeleteMapping("/types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public void deleteType(@PathVariable UUID id, Principal principal) {
        leaveTypeService.delete(id, principal.getName());
    }

    // ── Who a plan's type applies to (the rules themselves are saved with the plan, LV-01) ──

    /** How many current employees a set of "applies to" rules would cover (for the dialog's live count). */
    @PostMapping("/types/eligibility-preview")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public EligibilityPreview previewEligibility(@Valid @RequestBody EligibilityConfig config) {
        return leaveTypeService.previewEligibility(config);
    }

    // ── Year-end ──

    @GetMapping("/carry-forward/transfers")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<CarryForwardTransferResponse> transfers(@RequestParam int fromYear) {
        return leaveYearEndService.listTransfers(fromYear);
    }

    /** Runs year-end processing for an ended leave year now (the nightly job does the same; safe to repeat). */
    @PostMapping("/carry-forward/run")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public YearEndRunResult runYearEnd(@RequestParam int fromYear, Principal principal) {
        User actor = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        return leaveYearEndService.run(fromYear, actor.getId());
    }

    // ── Crediting ──

    /** Credits everything due now (the nightly job does the same; safe to repeat). */
    @PostMapping("/accrual/run")
    @PreAuthorize("hasAuthority('leave_configuration:CONFIGURE:ALL')")
    public AccrualRunResult runAccrual(Principal principal) {
        User actor = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        return leaveAccrualService.run(actor.getId());
    }

    /** LV-03 dry run: what crediting on {@code asOf} (default today) would post, credited or paused; writes nothing. */
    @GetMapping("/accrual/preview")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public com.nforce.onehr.dto.LeaveConfigDtos.AccrualPreview previewAccrual(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate asOf) {
        return leaveAccrualService.preview(asOf);
    }

    /** YE-07: when the nightly crediting and year-end jobs last ran, who ran them and what they did. */
    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public Map<String, LeaveJobMonitor.JobRun> jobs() {
        return leaveJobMonitor.lastRuns();
    }

    @GetMapping("/balances/{balanceId}/credits")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<AccrualCreditResponse> creditHistory(@PathVariable UUID balanceId) {
        return leaveTypeService.creditHistory(balanceId);
    }

    // ── Employee balances ──

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<Map<String, Object>> employees() {
        return leaveTypeService.employeeDirectory();
    }

    @GetMapping("/employees/{userId}/balances")
    @PreAuthorize("hasAuthority('leave_configuration:VIEW:ALL')")
    public List<LeaveBalanceResponse> employeeBalances(@PathVariable UUID userId, @RequestParam(required = false) Integer year) {
        return leaveTypeService.employeeBalances(userId, year);
    }

    // ── Opening balance upload (YE-02) ──

    /** Rows for the opening-balance template of the current or next leave year (the browser builds the .xlsx). */
    @GetMapping("/opening-balances")
    @PreAuthorize("hasAuthority('leave_configuration:EDIT:ALL')")
    public List<LeaveOpeningBalanceService.TemplateRow> openingBalanceTemplate(@RequestParam(required = false) Integer year) {
        return openingBalanceService.template(year);
    }

    /** Checks an uploaded file row by row and previews every change; saves nothing. */
    @PostMapping("/opening-balances/validate")
    @PreAuthorize("hasAuthority('leave_configuration:EDIT:ALL')")
    public LeaveOpeningBalanceService.Validation validateOpeningBalances(@RequestParam(required = false) Integer year,
                                                                         @RequestBody List<LeaveOpeningBalanceService.UploadRow> rows) {
        return openingBalanceService.validate(year, rows);
    }

    /** Saves a clean file (all rows or none); each change is audited like a manual adjustment. */
    @PostMapping("/opening-balances/apply")
    @PreAuthorize("hasAuthority('leave_configuration:EDIT:ALL')")
    public LeaveOpeningBalanceService.ApplyResult applyOpeningBalances(@RequestParam(required = false) Integer year,
                                                                       @RequestBody List<LeaveOpeningBalanceService.UploadRow> rows,
                                                                       Principal principal) {
        return openingBalanceService.apply(year, rows, principal.getName());
    }

    @PatchMapping("/balances/{balanceId}")
    @PreAuthorize("hasAuthority('leave_configuration:EDIT:ALL')")
    public LeaveBalanceResponse adjustBalance(@PathVariable UUID balanceId, @Valid @RequestBody BalanceAdjustmentRequest req,
                                              Principal principal) {
        return leaveTypeService.adjustBalance(balanceId, req, principal.getName());
    }
}
