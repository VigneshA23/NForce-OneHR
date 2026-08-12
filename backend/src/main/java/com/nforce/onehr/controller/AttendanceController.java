package com.nforce.onehr.controller;

import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.ApproverOptionDto;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.attendance.BulkApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.BulkRegularizationResultResponse;
import com.nforce.onehr.dto.attendance.BulkRejectRegularizationRequest;
import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.PenaltyCancelRequest;
import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.service.AttendancePenaltyService;
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
import java.util.ArrayList;
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
    private final AttendancePenaltyService attendancePenaltyService;

    // Punching (and viewing your own punches) is an Employee-only action — Manager/HR Admin/
    // Super Admin get oversight endpoints (/day, /team, /employee/{id}) instead, never a punch
    // clock of their own.

    @GetMapping("/today")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public TodayAttendanceResponse today(Principal principal) {
        return attendanceService.getToday(principal.getName());
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasRole('EMPLOYEE')")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceResponse checkIn(Principal principal) {
        return attendanceService.checkIn(principal.getName());
    }

    @PostMapping("/check-out")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public AttendanceResponse checkOut(Principal principal) {
        return attendanceService.checkOut(principal.getName());
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('EMPLOYEE')")
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

    @GetMapping("/team-month")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceResponse> teamMonth(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return attendanceService.getMonthForMyTeam(principal.getName(), from, to);
    }

    /** Avg. Work Hours Leaderboard, ranked desc — direct reports only (ONEHR-106). */
    @GetMapping("/team-effort")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<TeamEffortEntry> teamEffort(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return attendanceService.getTeamEffort(principal.getName(), from, to);
    }

    /** Late Arrivals, Least Hours Worked, and Frequent Breaks — direct reports only (ONEHR-107). */
    @GetMapping("/team-negligence")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public TeamNegligenceResponse teamNegligence(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return attendanceService.getTeamNegligence(principal.getName(), from, to);
    }

    /** On-Time Leaderboard — "on time" == PRESENT — direct reports only. */
    @GetMapping("/team-punctuality")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public TeamPunctualityResponse teamPunctuality(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return attendanceService.getTeamPunctuality(principal.getName(), from, to);
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

    // ── Regularization: submit + view own requests (Employee only — same reasoning as punching) ──

    @PostMapping("/regularization")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<RegularizationResponse> submitRegularization(
            @Valid @RequestBody CreateRegularizationRequest req, Principal principal) {
        RegularizationResponse created = regularizationService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/regularization/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
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

    /**
     * "View Regularization History" — Manager (direct report only), HR Admin, or Super Admin.
     * Backs the read-only history modal opened from the Penalties table's kebab menu.
     */
    @GetMapping("/regularization/history")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<RegularizationResponse> regularizationHistory(
            @RequestParam UUID employeeUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate attendanceDate,
            Principal principal) {
        return regularizationService.getHistoryForManager(principal.getName(), employeeUserId, attendanceDate);
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

    /**
     * Same reviewer scoping as /pending, but every status — backs the Pending Approvals
     * screen's All/Pending/Approved/Rejected status tabs (status filtering itself happens
     * client-side over this one list, same pattern as the My Requests month filter).
     */
    @GetMapping("/regularization/for-approver")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<RegularizationResponse> regularizationsForApprover(Principal principal) {
        return regularizationService.listForApprover(principal.getName());
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

    /**
     * Bulk approve — each id is processed independently via the same {@link RegularizationService#approve}
     * used by the single-item endpoint, so one item's failure (e.g. already decided by another
     * approver, or not at a stage this actor can act on) doesn't block the rest of the batch.
     */
    @PostMapping("/regularization/bulk-approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public BulkRegularizationResultResponse bulkApprove(@Valid @RequestBody BulkApproveRegularizationRequest req,
                                                         Principal principal) {
        List<UUID> succeeded = new ArrayList<>();
        List<BulkRegularizationResultResponse.BulkFailureDto> failed = new ArrayList<>();
        for (UUID id : req.getIds()) {
            try {
                regularizationService.approve(id, req.getComment(), principal.getName());
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(BulkRegularizationResultResponse.BulkFailureDto.builder()
                        .id(id).reason(e.getMessage()).build());
            }
        }
        return BulkRegularizationResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /** Bulk reject — same per-item independence as {@link #bulkApprove}. */
    @PostMapping("/regularization/bulk-reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public BulkRegularizationResultResponse bulkReject(@Valid @RequestBody BulkRejectRegularizationRequest req,
                                                        Principal principal) {
        List<UUID> succeeded = new ArrayList<>();
        List<BulkRegularizationResultResponse.BulkFailureDto> failed = new ArrayList<>();
        for (UUID id : req.getIds()) {
            try {
                regularizationService.reject(id, req.getComment(), principal.getName());
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(BulkRegularizationResultResponse.BulkFailureDto.builder()
                        .id(id).reason(e.getMessage()).build());
            }
        }
        return BulkRegularizationResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    // ── Attendance Penalties: Regularize & Cancel Penalties (Manager scope; HR/Super Admin too) ──

    /**
     * An empty list here means no configured Penalization Policy section currently matches
     * anything in range for this scope — the expected, correct result, not a bug — see
     * {@code ExceptionService.upsertException} for where evaluation actually happens.
     */
    @GetMapping("/penalties")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendancePenaltyResponse> penalties(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String discrepancyType,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String search,
            Principal principal) {
        return attendancePenaltyService.list(principal.getName(), from, to, status, discrepancyType, department, location, search);
    }

    /**
     * Bulk cancel — each id is independently re-validated server-side (exists, still a direct
     * report, still cancellable, no active regularization) rather than trusting the frontend's
     * "cancellable" flag; one id's failure doesn't affect the rest of the batch.
     */
    @PostMapping("/penalties/cancel")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public PenaltyCancelResultResponse cancelPenalties(@Valid @RequestBody PenaltyCancelRequest req, Principal principal) {
        return attendancePenaltyService.cancelBulk(principal.getName(), req.getPenaltyIds(), req.getReason());
    }
}
