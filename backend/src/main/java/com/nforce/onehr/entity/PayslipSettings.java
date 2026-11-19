package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — "Configure Payslip Settings" (§3.9), one row per pay group.
 * Read by {@link com.nforce.onehr.service.PayslipService} when rendering a payslip PDF; a pay
 * group with no row (the default) renders with {@link com.nforce.onehr.service.PayslipPdfService}'s
 * own long-standing hardcoded text, unencrypted — see that service's own doc comment.
 */
@Entity
@Table(name = "payslip_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayslipSettings {

    @Id
    @Column(name = "pay_group_id")
    private UUID payGroupId;

    // When true, the generated PDF is encrypted with the employee's own PAN (uppercase) as the
    // password to open it — see PayslipPdfService#render(PayslipPdfModel, String)'s own doc
    // comment for the exact convention this matches.
    @Column(name = "password_protected", nullable = false)
    @Builder.Default
    private boolean passwordProtected = false;

    @Column(name = "document_header_text", length = 50)
    private String documentHeaderText;

    @Column(name = "footer_text", columnDefinition = "text")
    private String footerText;

    // The company logo printed above the payslip title — raw bytes, same BYTEA-column pattern as
    // Employee#profilePhoto/profileCover; no separate file-storage system exists in this app.
    @Column(name = "logo", columnDefinition = "bytea")
    private byte[] logo;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    // "LEFT" or "RIGHT" — which side of the header the logo sits on, next to the company name.
    @Column(name = "logo_alignment", nullable = false, length = 10)
    @Builder.Default
    private String logoAlignment = "RIGHT";

    // When true, a blank identity field (e.g. no PAN on file) is left off the payslip entirely
    // instead of printing as "-".
    @Column(name = "exclude_na_fields", nullable = false)
    @Builder.Default
    private boolean excludeNaFields = false;

    @Column(name = "earnings_heading", length = 50)
    private String earningsHeading;

    @Column(name = "deductions_heading", length = 50)
    private String deductionsHeading;

    @Column(name = "include_leave_summary", nullable = false)
    @Builder.Default
    private boolean includeLeaveSummary = false;

    @Column(name = "include_arrear_breakup", nullable = false)
    @Builder.Default
    private boolean includeArrearBreakup = false;

    @Column(name = "include_ytd_column", nullable = false)
    @Builder.Default
    private boolean includeYtdColumn = false;

    // When true, any employer-side CONTRIBUTION component configured for this employee's
    // statutory rules prints in its own section — paired with Earnings, same layout Keka uses.
    @Column(name = "include_employer_contributions", nullable = false)
    @Builder.Default
    private boolean includeEmployerContributions = false;

    // Comma-separated, ordered subset of PayslipFields.VALID_KEYS — which configurable identity
    // fields print, and in what order. Null means "never configured" → every key, in the
    // catalog's own default order, exactly matching the fixed set PayslipPdfService always
    // printed before this setting existed.
    @Column(name = "payslip_fields", columnDefinition = "text")
    private String payslipFields;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
