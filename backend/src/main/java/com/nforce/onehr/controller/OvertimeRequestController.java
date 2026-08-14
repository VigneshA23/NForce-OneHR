package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.CreateOvertimeRequest;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.service.OvertimeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/attendance/overtime")
@RequiredArgsConstructor
public class OvertimeRequestController {

    private final OvertimeRequestService overtimeRequestService;

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<OvertimeRequestResponse> submit(
            @Valid @RequestBody CreateOvertimeRequest req, Principal principal) {
        OvertimeRequestResponse created = overtimeRequestService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<OvertimeRequestResponse> mine(Principal principal) {
        return overtimeRequestService.listMine(principal.getName());
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<OvertimeRequestResponse> pending(Principal principal) {
        return overtimeRequestService.listPendingForApprover(principal.getName());
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public OvertimeRequestResponse approve(@PathVariable UUID id,
                                            @RequestBody(required = false) ApproveRegularizationRequest req,
                                            Principal principal) {
        String comment = req != null ? req.getComment() : null;
        return overtimeRequestService.approve(id, comment, principal.getName());
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public OvertimeRequestResponse reject(@PathVariable UUID id,
                                           @Valid @RequestBody RejectRegularizationRequest req,
                                           Principal principal) {
        return overtimeRequestService.reject(id, req.getComment(), principal.getName());
    }
}
