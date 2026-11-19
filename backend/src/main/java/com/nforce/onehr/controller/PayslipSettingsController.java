package com.nforce.onehr.controller;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.service.PayslipSettingsService;
import com.nforce.onehr.service.PayslipSettingsService.Logo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** Keka restyle (payroll module) — §3.9 "Configure Payslip Settings". Same payroll:VIEW:ALL /
 * payroll:CONFIGURE:ALL split as the rest of Payroll Setup. */
@RestController
@RequestMapping("/api/payroll/pay-groups/{payGroupId}/payslip-settings")
@RequiredArgsConstructor
public class PayslipSettingsController {

    private final PayslipSettingsService payslipSettingsService;

    @GetMapping
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public PayslipSettingsResponse get(@PathVariable UUID payGroupId) {
        return payslipSettingsService.get(payGroupId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayslipSettingsResponse update(@PathVariable UUID payGroupId, @Valid @RequestBody UpdatePayslipSettingsRequest req) {
        return payslipSettingsService.update(payGroupId, req);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayslipSettingsResponse uploadLogo(@PathVariable UUID payGroupId, @RequestParam("file") MultipartFile file) throws IOException {
        return payslipSettingsService.uploadLogo(payGroupId, file);
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasAuthority('payroll:CONFIGURE:ALL')")
    public PayslipSettingsResponse removeLogo(@PathVariable UUID payGroupId) {
        return payslipSettingsService.removeLogo(payGroupId);
    }

    /** Raw bytes, same shape as {@code EmployeeController#photo} — fetched by the frontend as a
     * blob (it needs the caller's bearer token), not linked directly as an <img src>. */
    @GetMapping("/logo")
    @PreAuthorize("hasAuthority('payroll:VIEW:ALL')")
    public ResponseEntity<byte[]> logo(@PathVariable UUID payGroupId) {
        Logo logo = payslipSettingsService.getLogo(payGroupId);
        if (logo == null) return ResponseEntity.notFound().build();
        MediaType type = logo.contentType() != null ? MediaType.parseMediaType(logo.contentType()) : MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).body(logo.data());
    }
}
