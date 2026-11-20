package com.nforce.onehr.controller;

import com.nforce.onehr.service.MyTaxProfileService;
import com.nforce.onehr.service.MyTaxProfileService.RegimeView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;

/** US-F04 / US-F06. Self-service endpoints take the user from the token only; the window endpoint is HR/Super Admin. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MyTaxProfileController {

    public record EsiCeiling(BigDecimal wageCeiling) {}
    public record RegimeRequest(String regime) {}
    public record WindowRequest(LocalDate opensOn, LocalDate closesOn) {}

    private final MyTaxProfileService service;

    @GetMapping("/me/finances/esi-ceiling")
    public EsiCeiling esiCeiling(Principal principal) {
        return new EsiCeiling(service.esiWageCeiling(principal.getName()));
    }

    @GetMapping("/me/finances/tax-regime")
    public RegimeView regime(Principal principal) {
        return service.regime(principal.getName());
    }

    @PutMapping("/me/finances/tax-regime")
    public RegimeView setRegime(@RequestBody RegimeRequest body, Principal principal) {
        return service.setRegime(principal.getName(), body.regime());
    }

    @PutMapping("/tax-regime/window")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public void setWindow(@RequestBody WindowRequest body, Principal principal) {
        service.setWindow(principal.getName(), body.opensOn(), body.closesOn());
    }
}
