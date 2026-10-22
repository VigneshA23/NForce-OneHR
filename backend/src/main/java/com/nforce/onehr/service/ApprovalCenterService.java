package com.nforce.onehr.service;

import com.nforce.onehr.dto.ApprovalItemDto;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.dto.helpcontent.ApprovalAttemptDto;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the unified Approval Center queue for one caller.
 *
 * <p>Lifted verbatim out of {@code ApprovalCenterController} so the assistant's approval counts come
 * from the same code as the Approval Center screen and the Super Admin dashboard's pending-approvals
 * donut, rather than from a second, hand-maintained copy of these role branches - a copy that had
 * already drifted (HR/Super Admin expense claims counted at the manager stage only, and document
 * reviews left out entirely). Read-only: every call here is a list method.
 */
@Service
@RequiredArgsConstructor
public class ApprovalCenterService {

    private final LeaveService leaveService;
    private final RegularizationService regularizationService;
    private final ExpenseService expenseService;
    private final AssetService assetService;
    private final AttendanceRequestService attendanceRequestService;
    private final OvertimeRequestService overtimeRequestService;
    private final HelpContentService helpContentService;
    private final UserRepository userRepo;
    private final EffectivePermissionService effectivePermissionService;

    /**
     * All pending approval items visible to the caller. Each module's queue is included when the
     * caller holds that module's APPROVE permission at TEAM or wider — seeded (V225) to exactly
     * Manager/HR Admin/Super Admin, the old role gate — and the module's own service narrows it
     * (assigned / own reports, or everything for an ALL holder). So a custom role granted, say,
     * overtime:APPROVE gets the overtime queue and nothing else.
     * - Manager: LEAVE (own reports), REGULARIZATION (own reports), EXPENSE at MANAGER stage (own reports), ASSET_REQUEST (own reports)
     * - HR Admin / Super Admin: LEAVE (all), REGULARIZATION (all), EXPENSE at either stage (all —
     *   see ExpenseService.pendingForFinalApprover for why this includes MANAGER-stage claims
     *   too), ASSET_REQUEST (all)
     */
    public List<ApprovalItemDto> pendingApprovals(String email) {
        User actor = userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<String> granted = effectivePermissionService.coverage(actor.getRoles());
        // FAQs & Guides approval is relationship-based (the author's manager chain), so its queue
        // stays on the role gate plus the hr_help_content:APPROVE:ALL fallback below.
        Set<String> roleCodes = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        boolean isAdmin = roleCodes.contains("HR_ADMIN") || roleCodes.contains("SUPER_ADMIN");
        boolean isManager = roleCodes.contains("MANAGER");

        List<ApprovalItemDto> items = new ArrayList<>();

        // Leave — TEAM: own reports; ALL (HR/SA): all pending, regardless of direct-report relationship
        if (granted.contains("leave:APPROVE:TEAM")) {
            leaveService.listPendingApprovals(email).stream()
                    .map(this::leaveToApprovalItem).forEach(items::add);
        }
        // Regularization — TEAM: requests assigned to them; ALL (HR/SA): all pending
        if (granted.contains("regularization:APPROVE:TEAM")) {
            regularizationService.listPendingForApprover(email).stream()
                    .map(this::regularizationToApprovalItem).forEach(items::add);
        }
        if (granted.contains("expenses:APPROVE:ALL")) {
            // Expense — HR/SA see claims at both stages now (SUBMITTED and MANAGER_APPROVED), so
            // tag each item with its ACTUAL stage rather than hardcoding "FINAL" — the frontend
            // routes the approve/reject click through managerApprove/managerReject vs
            // finalApprove/finalReject based on this field (ApprovalsPage.tsx approveItem/
            // rejectItem), and a still-SUBMITTED claim must go through the manager-stage endpoint
            // (which already allows HR/SA to act on it — see requireCurrentManagerOf) since
            // finalApprove/finalReject reject anything not already MANAGER_APPROVED. Every
            // SUBMITTED claim is in here, so the manager-stage list below adds nothing for an
            // HR/SA who is also a Manager.
            expenseService.pendingForFinalApprover(email).stream()
                    .map(c -> expenseToApprovalItem(c, "SUBMITTED".equals(c.getStatus()) ? "MANAGER" : "FINAL"))
                    .forEach(items::add);
        } else {
            // Expense — manager stage only
            if (granted.contains("expenses:APPROVE:TEAM")) {
                expenseService.pendingForManager(email).stream()
                        .map(c -> expenseToApprovalItem(c, "MANAGER")).forEach(items::add);
            }
            // A Workflow Studio stage can name a custom role; its holders get just the claims waiting
            // at that stage (ExpenseService filters by stage). Only asked for holders of an active
            // custom role — a system-role-only user without expenses:APPROVE:ALL can't be one.
            boolean holdsCustomRole = actor.getRoles().stream().anyMatch(r -> !r.isSystem() && r.isActive());
            if (holdsCustomRole && expenseService.hasFinalApproverQueue(email)) {
                expenseService.pendingForFinalApprover(email).stream()
                        .map(c -> expenseToApprovalItem(c, "FINAL")).forEach(items::add);
            }
        }
        // Asset requests (V242) — APPROVE:ALL (HR/SA): every PENDING request (APPROVED ones are
        // fulfilled from the HR Assets page, not approved here); APPROVE:TEAM (Manager): their own
        // reports' PENDING requests. A Manager who is also HR/SA used to also get APPROVED ones here
        // via the role-code path; they now get the same PENDING-only list as any other HR/SA.
        if (granted.contains("assets:APPROVE:ALL")) {
            assetService.listPendingForApprover(email).stream()
                    .filter(r -> "PENDING".equals(r.getStatus()))
                    .map(this::assetRequestToApprovalItem).forEach(items::add);
        } else if (granted.contains("assets:APPROVE:TEAM")) {
            assetService.listPendingForApprover(email).stream()
                    .map(this::assetRequestToApprovalItem).forEach(items::add);
        }
        // WFH / Partial Day — TEAM: requests assigned to them; ALL (HR/SA): all pending
        if (granted.contains("attendance_requests:APPROVE:TEAM")) {
            attendanceRequestService.listPendingForApprover(email).stream()
                    .map(this::attendanceRequestToApprovalItem).forEach(items::add);
        }
        // Overtime — TEAM: requests assigned to them; ALL (HR/SA): all pending
        if (granted.contains("overtime:APPROVE:TEAM")) {
            overtimeRequestService.listPendingForApprover(email).stream()
                    .map(this::overtimeToApprovalItem).forEach(items::add);
        }
        // hr_help_content:APPROVE:ALL (V238, Super Admin by default) is the approval fallback —
        // a custom role granted it gets the queue too; managers approve their own reports' content.
        if (isManager || isAdmin || granted.contains("hr_help_content:APPROVE:ALL")) {
            // FAQs & Guides — attempts resolved to the caller; Super Admin has blanket
            // fallback-authority visibility, same convention as every other request type here.
            helpContentService.listPendingApprovalsForApprover(email).stream()
                    .map(this::helpContentToApprovalItem).forEach(items::add);
        }
        // Each queue is fetched at most once, so (unlike the old overlapping Manager + Admin
        // branches) there are no duplicates to drop.
        return items;
    }

    // ── Mappers ───────────────────────────────────────────

    private ApprovalItemDto leaveToApprovalItem(LeaveRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("LEAVE")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .leaveTypeName(r.getLeaveTypeName())
                .leaveStartDate(r.getStartDate())
                .leaveEndDate(r.getEndDate())
                .leaveTotalDays(r.getTotalDays())
                .leaveHalfDay(r.isHalfDay())
                .leaveReason(r.getEmployeeReason())
                .build();
    }

    private ApprovalItemDto regularizationToApprovalItem(RegularizationResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("REGULARIZATION")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getAttendanceDate())
                .requestedCheckIn(r.getRequestedCheckIn())
                .requestedCheckOut(r.getRequestedCheckOut())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto expenseToApprovalItem(ExpenseClaimResponse c, String stage) {
        return ApprovalItemDto.builder()
                .id(c.getId().toString())
                .requestType("EXPENSE")
                .employeeUserId(c.getEmployeeUserId())
                .employeeName(c.getEmployeeName())
                .createdAt(c.getCreatedAt())
                .expenseCategoryName(c.getCategoryName())
                .expenseAmount(c.getAmount())
                .expenseDate(c.getExpenseDate())
                .businessPurpose(c.getBusinessPurpose())
                .receiptUrl(c.getReceiptUrl())
                .approvalStage(stage)
                .build();
    }

    private ApprovalItemDto assetRequestToApprovalItem(AssetRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("ASSET_REQUEST")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt())
                .requestedCategoryName(r.getCategoryName())
                .assetRequestReason(r.getReason())
                .assetRequestStatus(r.getStatus())
                .build();
    }

    private ApprovalItemDto attendanceRequestToApprovalItem(AttendanceRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType(r.getRequestType()) // "WFH" or "PARTIAL_DAY"
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getRequestDate())
                .partialDayHours(r.getPartialDayHours())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto overtimeToApprovalItem(OvertimeRequestResponse r) {
        return ApprovalItemDto.builder()
                .id(r.getId().toString())
                .requestType("OVERTIME")
                .employeeUserId(r.getEmployeeUserId())
                .employeeName(r.getEmployeeName())
                .createdAt(r.getCreatedAt() != null
                        ? r.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant() : null)
                .attendanceDate(r.getWorkDate())
                .requestedCheckIn(r.getRequestedStart())
                .requestedCheckOut(r.getRequestedEnd())
                .regularizationReason(r.getReason())
                .build();
    }

    private ApprovalItemDto helpContentToApprovalItem(ApprovalAttemptDto a) {
        return ApprovalItemDto.builder()
                .id(a.getId().toString())
                .requestType("HELP_CONTENT")
                .employeeUserId(a.getSubmittedByUserId())
                .employeeName(a.getSubmittedByName())
                .createdAt(a.getSubmittedAt())
                .helpContentId(a.getContentId().toString())
                .helpContentType(a.getContentType())
                .helpContentTitle(a.getSnapshotTitle())
                .helpContentDescription(a.getSnapshotDescription())
                .helpContentBody(a.getSnapshotBody())
                .helpContentCategory(a.getSnapshotCategory())
                .helpContentAttemptNumber(a.getAttemptNumber())
                .helpContentModifiedSincePrevious(a.isModifiedSincePrevious())
                .build();
    }
}
