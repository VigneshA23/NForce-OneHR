package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Keka restyle (payroll module) — "Configure Payslip Settings" (§3.9) admin CRUD. The actual
 * render-time read of this lives in {@link PayslipService#toModel}, not here. */
@Service
@RequiredArgsConstructor
public class PayslipSettingsService {

    private final PayslipSettingsRepository payslipSettingsRepository;
    private final PayGroupRepository payGroupRepository;

    @Transactional(readOnly = true)
    public PayslipSettingsResponse get(UUID payGroupId) {
        return payslipSettingsRepository.findById(payGroupId)
                .map(this::toResponse)
                .orElseGet(() -> PayslipSettingsResponse.builder().payGroupId(payGroupId).build());
    }

    @Transactional
    public PayslipSettingsResponse update(UUID payGroupId, UpdatePayslipSettingsRequest req) {
        if (!payGroupRepository.existsById(payGroupId)) {
            throw new NoSuchElementException("Pay group not found: " + payGroupId);
        }
        PayslipSettings settings = payslipSettingsRepository.findById(payGroupId)
                .orElseGet(() -> PayslipSettings.builder().payGroupId(payGroupId).build());

        settings.setPasswordProtected(req.isPasswordProtected());
        settings.setDocumentHeaderText(trim(req.getDocumentHeaderText()));
        settings.setFooterText(trim(req.getFooterText()));

        return toResponse(payslipSettingsRepository.save(settings));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private PayslipSettingsResponse toResponse(PayslipSettings s) {
        return PayslipSettingsResponse.builder()
                .payGroupId(s.getPayGroupId())
                .passwordProtected(s.isPasswordProtected())
                .documentHeaderText(s.getDocumentHeaderText())
                .footerText(s.getFooterText())
                .build();
    }
}
