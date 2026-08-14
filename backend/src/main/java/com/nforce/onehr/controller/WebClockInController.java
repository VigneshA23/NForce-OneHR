package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.ApproveRegularizationRequest;
import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.RejectRegularizationRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.service.WebClockInService;
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
@RequestMapping("/api/attendance/web-clock-in")
@RequiredArgsConstructor
public class WebClockInController {

    private final WebClockInService webClockInService;

    @PostMapping
    public ResponseEntity<WebClockInResponse> submit(
            @Valid @RequestBody CreateWebClockInRequest req, Principal principal) {
        WebClockInResponse created = webClockInService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    public List<WebClockInResponse> mine(Principal principal) {
        return webClockInService.listMine(principal.getName());
    }

    @PostMapping("/checkout")
    public WebClockInResponse checkOut(Principal principal) {
        return webClockInService.checkOut(principal.getName());
    }

    /** Undoes today's still-open check-in (before check-out) — no approval needed, same as submit/checkout. */
    @DeleteMapping("/cancel")
    public ResponseEntity<Void> cancel(Principal principal) {
        webClockInService.cancel(principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public List<WebClockInResponse> pending(Principal principal) {
        return webClockInService.listPendingForApprover(principal.getName());
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public WebClockInResponse approve(@PathVariable UUID id,
                                       @RequestBody(required = false) ApproveRegularizationRequest req,
                                       Principal principal) {
        String comment = req != null ? req.getComment() : null;
        return webClockInService.approve(id, comment, principal.getName());
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public WebClockInResponse reject(@PathVariable UUID id,
                                      @Valid @RequestBody RejectRegularizationRequest req,
                                      Principal principal) {
        return webClockInService.reject(id, req.getComment(), principal.getName());
    }
}
