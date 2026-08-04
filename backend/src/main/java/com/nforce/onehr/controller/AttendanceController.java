package com.nforce.onehr.controller;

import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.ApproverOptionDto;
import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.RegularizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Check-in and check-out take no request body by design — the timestamp is generated
 * server-side and a client-supplied time is never accepted.
 */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final RegularizationService regularizationService;

    @GetMapping("/today")
    public TodayAttendanceResponse today(Principal principal) {
        return attendanceService.getToday(principal.getName());
    }

    @PostMapping("/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceResponse checkIn(Principal principal) {
        return attendanceService.checkIn(principal.getName());
    }

    @PostMapping("/check-out")
    public AttendanceResponse checkOut(Principal principal) {
        return attendanceService.checkOut(principal.getName());
    }

    @GetMapping("/me")
    public List<AttendanceResponse> myHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return attendanceService.getMyHistory(principal.getName(), from, to);
    }

    /** Own punch for a single date, or 204 if none — backs the regularization form's auto-fill. */
    @GetMapping("/punch/{date}")
    public ResponseEntity<AttendanceResponse> punchForDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal principal) {
        AttendanceResponse punch = attendanceService.getPunchForDate(principal.getName(), date);
        return punch != null ? ResponseEntity.ok(punch) : ResponseEntity.noContent().build();
    }

    /** Every check-in/check-out session for a single day, e.g. to show a lunch-break gap. */
    @GetMapping("/punches/{date}")
    public List<PunchResponse> punchesForDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal principal) {
        return attendanceService.getPunches(principal.getName(), date);
    }

    @GetMapping("/day")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceResponse> day(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.getDayForAll(date);
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceResponse> team(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal principal) {
        return attendanceService.getDayForMyTeam(principal.getName(), date);
    }

    @GetMapping("/employee/{userId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceResponse> employeeHistory(
            @PathVariable UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        // A plain Manager is confined to their direct reports; HR/Super Admin may view anyone.
        boolean privileged = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_HR_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
        return attendanceService.getEmployeeHistory(
                userId, from, to, authentication.getName(), !privileged);
    }

    // ── Regularization: submit + view own requests (any authenticated user) ──────

    @PostMapping("/regularization")
    public ResponseEntity<RegularizationResponse> submitRegularization(
            @Valid @RequestBody CreateRegularizationRequest req, Principal principal) {
        RegularizationResponse created = regularizationService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/regularization/mine")
    public List<RegularizationResponse> myRegularizations(Principal principal) {
        return regularizationService.listMine(principal.getName());
    }

    /** Edit a still-pending request — owner only, PENDING only (enforced in the service). */
    @PatchMapping("/regularization/{id}")
    public RegularizationResponse updateRegularization(@PathVariable UUID id,
                                                        @Valid @RequestBody CreateRegularizationRequest req,
                                                        Principal principal) {
        return regularizationService.update(id, req, principal.getName());
    }

    /** Selectable approvers for the "assign to manager" dropdown — any authenticated user. */
    @GetMapping("/regularization/approvers")
    public List<ApproverOptionDto> approvers() {
        return regularizationService.listApprovers();
    }

    /** Super Admin: full history org-wide, with optional filters. */
    @GetMapping("/regularization/all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<RegularizationResponse> allRegularizations(
            @RequestParam(required = false) UUID employeeUserId,
            @RequestParam(required = false) UUID approverUserId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status) {
        return regularizationService.listAll(employeeUserId, approverUserId, departmentId, month, status);
    }

    // ── Regularization: review (Manager sees assigned requests; HR/Super Admin see all) ──

    @GetMapping("/regularization/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<RegularizationResponse> pendingRegularizations(Principal principal) {
        return regularizationService.listPendingForApprover(principal.getName());
    }

    @PatchMapping("/regularization/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public RegularizationResponse approve(@PathVariable UUID id,
                                          @RequestBody(required = false) ApproveRegularizationRequest req,
                                          Principal principal) {
        String comment = req != null ? req.getComment() : null;
        return regularizationService.approve(id, comment, principal.getName());
    }

    @PatchMapping("/regularization/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public RegularizationResponse reject(@PathVariable UUID id,
                                         @Valid @RequestBody RejectRegularizationRequest req,
                                         Principal principal) {
        return regularizationService.reject(id, req.getComment(), principal.getName());
    }
}
