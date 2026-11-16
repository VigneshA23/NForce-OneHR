package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.CreatePtRegisteredLocationRequest;
import com.nforce.onehr.dto.payroll.PtRegisteredLocationResponse;
import com.nforce.onehr.dto.payroll.UpdatePtRegisteredLocationRequest;
import com.nforce.onehr.service.PtRegisteredLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Keka restyle (payroll module) — §3.8 "PT Registered states"/"Registered PT Locations". Same
 * payroll:VIEW:ALL / payroll:CONFIGURE:ALL split as the rest of Payroll Setup. */
@RestController
@RequestMapping("/api/payroll/pay-groups/{payGroupId}/pt-locations")
@RequiredArgsConstructor
public class PtRegisteredLocationController {

    private final PtRegisteredLocationService ptRegisteredLocationService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public List<PtRegisteredLocationResponse> list(@PathVariable UUID payGroupId) {
        return ptRegisteredLocationService.list(payGroupId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PtRegisteredLocationResponse create(@PathVariable UUID payGroupId, @Valid @RequestBody CreatePtRegisteredLocationRequest req) {
        return ptRegisteredLocationService.create(payGroupId, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PtRegisteredLocationResponse update(@PathVariable UUID payGroupId, @PathVariable UUID id, @Valid @RequestBody UpdatePtRegisteredLocationRequest req) {
        return ptRegisteredLocationService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public void delete(@PathVariable UUID payGroupId, @PathVariable UUID id) {
        ptRegisteredLocationService.delete(id);
    }
}
