package com.nforce.onehr.controller;

import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.RejectLeaveRequestRequest;
import com.nforce.onehr.entity.LeaveRequestAttachment;
import com.nforce.onehr.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    /** Active leave types for the leave request form. Configuration lives at /api/leave-config. */
    @GetMapping("/types")
    public List<LeaveTypeResponse> listTypes() {
        return leaveService.listTypes();
    }

    /** The active leave types the caller may apply for (their "applies to" rules), for the request form. */
    @GetMapping("/types/mine")
    public List<LeaveTypeResponse> myTypes(Principal principal) {
        return leaveService.listMyTypes(principal.getName());
    }

    /** The caller's balances for the current leave year, or {@code ?year=} the next one (read-only expected view). */
    @GetMapping("/balances")
    public List<LeaveBalanceResponse> myBalances(@RequestParam(required = false) Integer year, Principal principal) {
        return leaveService.listMyBalances(principal.getName(), year);
    }

    /** Balances of the caller's current direct reports — My Team roster — for the current or ({@code ?year=}) next leave year. */
    @PreAuthorize("hasAuthority('leave:VIEW:TEAM')")
    @GetMapping("/balances/team")
    public List<LeaveBalanceResponse> teamBalances(@RequestParam(required = false) Integer year, Principal principal) {
        return leaveService.listTeamBalances(principal.getName(), year);
    }

    @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse submitRequest(@Valid @RequestBody CreateLeaveRequestRequest req, Principal principal) {
        return leaveService.submitRequest(req, principal.getName());
    }

    /** LV-02: the same request as multipart — part "request" (JSON) plus an optional file part "attachment". */
    @PostMapping(value = "/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse submitRequestWithAttachment(@Valid @RequestPart("request") CreateLeaveRequestRequest req,
                                                            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
                                                            Principal principal) {
        return leaveService.submitRequest(req, attachment, principal.getName());
    }

    /** LV-02: a request's attachment — the requester, or whoever may decide the request. */
    @GetMapping("/requests/{id}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, Principal principal) {
        LeaveRequestAttachment a = leaveService.getAttachment(id, principal.getName());
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(a.getContentType());
        } catch (IllegalArgumentException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.getFileName()).build().toString())
                .contentType(mediaType)
                .body(a.getData());
    }

    @GetMapping("/requests/mine")
    public List<LeaveRequestResponse> myRequests(Principal principal) {
        return leaveService.listMyRequests(principal.getName());
    }

    /** Edit a still-pending leave request — own requests only, PENDING status only (enforced in the service). */
    @PatchMapping(value = "/requests/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LeaveRequestResponse updateRequest(@PathVariable UUID id,
                                               @Valid @RequestBody CreateLeaveRequestRequest req,
                                               Principal principal) {
        return leaveService.update(id, req, principal.getName());
    }

    /** LV-02: edit as multipart, optionally replacing the attachment. */
    @PatchMapping(value = "/requests/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LeaveRequestResponse updateRequestWithAttachment(@PathVariable UUID id,
                                                            @Valid @RequestPart("request") CreateLeaveRequestRequest req,
                                                            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
                                                            Principal principal) {
        return leaveService.update(id, req, attachment, principal.getName());
    }

    @PreAuthorize("hasAuthority('leave:APPROVE:TEAM')")
    @GetMapping("/approvals")
    public List<LeaveRequestResponse> pendingApprovals(Principal principal) {
        return leaveService.listPendingApprovals(principal.getName());
    }

    @PreAuthorize("hasAuthority('leave:VIEW:TEAM')")
    @GetMapping("/team")
    public List<LeaveRequestResponse> team(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return leaveService.listTeamLeave(principal.getName(), from, to);
    }

    /** Approved leave org-wide overlapping [from, to] — HR/Super Admin's "On Leave" KPI. */
    @GetMapping("/organization")
    @PreAuthorize("hasAuthority('leave:VIEW:ALL')")
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

    @PreAuthorize("hasAuthority('leave:APPROVE:TEAM')")
    @PostMapping("/requests/{id}/approve")
    public LeaveRequestResponse approve(@PathVariable UUID id, Principal principal) {
        return leaveService.approve(id, principal.getName());
    }

    @PreAuthorize("hasAuthority('leave:APPROVE:TEAM')")
    @PostMapping("/requests/{id}/reject")
    public LeaveRequestResponse reject(@PathVariable UUID id,
                                        @Valid @RequestBody RejectLeaveRequestRequest req,
                                        Principal principal) {
        return leaveService.reject(id, req.getReason(), principal.getName());
    }
}
