package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.service.PayrollRunReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C22/US-C23 (ONEHR-579), Phase 6. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/runs/{runId}/review")
@RequiredArgsConstructor
public class PayrollRunReviewController {

    private final PayrollRunReviewService payrollRunReviewService;

    @GetMapping("/leave-applied")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayrollRunReviewItemResponse> listLeaveApplied(@PathVariable UUID runId) {
        return payrollRunReviewService.listLeaveApplied(runId);
    }

    @PostMapping("/leave-applied/decide")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public BulkDecideLeaveReviewResponse bulkDecideLeaveApplied(@PathVariable UUID runId,
            @Valid @RequestBody BulkDecideLeaveReviewRequest req, Principal principal) {
        return payrollRunReviewService.bulkDecideLeaveApplied(runId, req, principal.getName());
    }

    @GetMapping("/no-attendance")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayrollRunReviewItemResponse> listNoAttendance(@PathVariable UUID runId) {
        return payrollRunReviewService.listNoAttendance(runId);
    }

    @PostMapping("/no-attendance/{itemId}/decide")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayrollRunReviewItemResponse decideNoAttendance(@PathVariable UUID runId, @PathVariable UUID itemId,
            @Valid @RequestBody DecideNoAttendanceReviewRequest req, Principal principal) {
        return payrollRunReviewService.decideNoAttendance(runId, itemId, req, principal.getName());
    }
}
