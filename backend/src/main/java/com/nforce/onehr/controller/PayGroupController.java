package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreatePayGroupRequest;
import com.nforce.onehr.dto.payroll.PayGroupResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupRequest;
import com.nforce.onehr.service.PayGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** US-C19 — Pay Group and pay schedule. HR Admin/Super Admin only. */
@RestController
@RequestMapping("/api/payroll/pay-groups")
@RequiredArgsConstructor
public class PayGroupController {

    private final PayGroupService payGroupService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayGroupResponse create(@Valid @RequestBody CreatePayGroupRequest req, Principal principal) {
        return payGroupService.create(req, principal.getName());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayGroupResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePayGroupRequest req, Principal principal) {
        return payGroupService.update(id, req, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PayGroupResponse> list() {
        return payGroupService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayGroupResponse get(@PathVariable UUID id) {
        return payGroupService.get(id);
    }
}
