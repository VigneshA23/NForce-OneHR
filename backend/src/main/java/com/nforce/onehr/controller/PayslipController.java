package com.nforce.onehr.controller;

import com.nforce.onehr.service.PayslipService;
import com.nforce.onehr.service.PayslipService.PayslipFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * US-C12 — employee self-service payslips. No {@code @PreAuthorize}: any authenticated user may call
 * these, and the service resolves "me" from the JWT principal only (never a path/query employeeId).
 * The HR-side {@code /payslips/{employeeId}/{period}} endpoint is intentionally not exposed yet — see
 * {@link PayslipService}.
 */
@RestController
@RequestMapping("/api/payslips")
@RequiredArgsConstructor
public class PayslipController {

    private final PayslipService payslipService;

    /** Periods ({@code yyyy-MM}) the caller can download, newest first. */
    @GetMapping("/me")
    public List<String> myPeriods(Principal principal) {
        return payslipService.listMyPeriods(principal.getName());
    }

    @GetMapping("/me/{period}")
    public ResponseEntity<byte[]> myPayslip(@PathVariable String period, Principal principal) {
        PayslipFile file = payslipService.getMyPayslipPdf(principal.getName(), period);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                // Salary data: never let a browser or proxy cache it.
                .cacheControl(CacheControl.noStore())
                .body(file.data());
    }
}
