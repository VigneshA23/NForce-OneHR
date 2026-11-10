package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.YtdResponse;
import com.nforce.onehr.service.PayrollYtdService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * US-C17 — Year-to-Date payroll figures. Two endpoints on purpose, never one serving both:
 * <ul>
 *   <li>{@code GET /api/me/finances/ytd} — self-service, no {@code @PreAuthorize} (MyFinances
 *       convention); the employee is resolved from the Principal and no id is ever accepted.</li>
 *   <li>{@code GET /api/payroll/ytd/{employeeId}} — HR lookup of anyone, gated by
 *       {@code payroll:VIEW:ALL}.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class PayrollYtdController {

    private final PayrollYtdService ytdService;

    @GetMapping("/api/me/finances/ytd")
    public YtdResponse myYtd(Principal principal) {
        return ytdService.getMyYtd(principal.getName());
    }

    @GetMapping("/api/payroll/ytd/{employeeId}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public YtdResponse ytdFor(@PathVariable UUID employeeId) {
        return ytdService.getYtdFor(employeeId);
    }
}
