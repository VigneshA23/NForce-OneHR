package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.RejectLeaveRequestRequest;
import com.nforce.onehr.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @GetMapping("/types")
    public List<LeaveTypeResponse> listTypes() {
        return leaveService.listTypes();
    }

    @GetMapping("/balances")
    public List<LeaveBalanceResponse> myBalances(Principal principal) {
        return leaveService.listMyBalances(principal.getName());
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse submitRequest(@Valid @RequestBody CreateLeaveRequestRequest req, Principal principal) {
        return leaveService.submitRequest(req, principal.getName());
    }

    @GetMapping("/requests/mine")
    public List<LeaveRequestResponse> myRequests(Principal principal) {
        return leaveService.listMyRequests(principal.getName());
    }

    @GetMapping("/approvals")
    public List<LeaveRequestResponse> pendingApprovals(Principal principal) {
        return leaveService.listPendingApprovals(principal.getName());
    }

    @GetMapping("/team")
    public List<LeaveRequestResponse> team(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return leaveService.listTeamLeave(principal.getName(), from, to);
    }

    /** Approved leave org-wide overlapping [from, to] — HR/Super Admin's "On Leave" KPI. */
    @GetMapping("/organization")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public List<LeaveRequestResponse> organization(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return leaveService.listOrgLeave(from, to);
    }

    /** Approved leave for the caller's current peers — My Team: Peers view (ONEHR-73). */
    @GetMapping("/peers")
    public List<LeaveRequestResponse> peers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return leaveService.listPeerLeave(principal.getName(), from, to);
    }

    @PostMapping("/requests/{id}/approve")
    public LeaveRequestResponse approve(@PathVariable UUID id, Principal principal) {
        return leaveService.approve(id, principal.getName());
    }

    @PostMapping("/requests/{id}/reject")
    public LeaveRequestResponse reject(@PathVariable UUID id,
                                        @Valid @RequestBody RejectLeaveRequestRequest req,
                                        Principal principal) {
        return leaveService.reject(id, req.getReason(), principal.getName());
    }
}
