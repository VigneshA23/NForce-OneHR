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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
