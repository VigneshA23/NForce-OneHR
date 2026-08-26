package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.service.AttendanceRequestService;
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
import java.util.UUID;

/** Work From Home / Partial Day requests. See AttendanceRequestService for the flow's semantics. */
@RestController
@RequestMapping("/api/attendance/requests")
@RequiredArgsConstructor
public class AttendanceRequestController {

    private final AttendanceRequestService attendanceRequestService;

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<AttendanceRequestResponse> submit(
            @Valid @RequestBody CreateAttendanceRequest req, Principal principal) {
        AttendanceRequestResponse created = attendanceRequestService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<AttendanceRequestResponse> mine(Principal principal) {
        return attendanceRequestService.listMine(principal.getName());
    }

    /** "View Available Balance" — hours already committed this month vs. the 2h/month cap. */
    @GetMapping("/partial-day-balance")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public AttendanceRequestService.PartialDayBalance partialDayBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Principal principal) {
        return attendanceRequestService.getPartialDayBalance(principal.getName(), date);
    }

    /** WFH's "Remaining balance" line — days used this month vs. the enforced 2-day/month cap. */
    @GetMapping("/wfh-balance")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public AttendanceRequestService.WfhBalance wfhBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Principal principal) {
        return attendanceRequestService.getWfhBalance(principal.getName(), date);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<AttendanceRequestResponse> pending(Principal principal) {
        return attendanceRequestService.listPendingForApprover(principal.getName());
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AttendanceRequestResponse approve(@PathVariable UUID id,
                                              @RequestBody(required = false) ApproveRegularizationRequest req,
                                              Principal principal) {
        String comment = req != null ? req.getComment() : null;
        return attendanceRequestService.approve(id, comment, principal.getName());
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public AttendanceRequestResponse reject(@PathVariable UUID id,
                                             @Valid @RequestBody RejectRegularizationRequest req,
                                             Principal principal) {
        return attendanceRequestService.reject(id, req.getComment(), principal.getName());
    }
}
