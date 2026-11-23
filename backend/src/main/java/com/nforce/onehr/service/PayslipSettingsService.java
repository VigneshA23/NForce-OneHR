package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayslipSettingsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayslipSettingsRequest;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Keka restyle (payroll module) — "Configure Payslip Settings" (§3.9) admin CRUD. The actual
 * render-time read of this lives in {@link PayslipService#toModel}, not here. */
@Service
@RequiredArgsConstructor
public class PayslipSettingsService {

    private static final long MAX_LOGO_BYTES = 500 * 1024;

    private final PayslipSettingsRepository payslipSettingsRepository;
    private final PayGroupRepository payGroupRepository;

    public record Logo(byte[] data, String contentType) {}

    @Transactional(readOnly = true)
    public PayslipSettingsResponse get(UUID payGroupId) {
        return payslipSettingsRepository.findById(payGroupId)
                .map(this::toResponse)
                .orElseGet(() -> PayslipSettingsResponse.builder().payGroupId(payGroupId)
                        .logoAlignment("RIGHT").logoSize(70).logoShape("SQUARE")
                        .payslipFields(PayslipFields.DEFAULT_KEYS).build());
    }

    @Transactional
    public PayslipSettingsResponse update(UUID payGroupId, UpdatePayslipSettingsRequest req) {
        if (!payGroupRepository.existsById(payGroupId)) {
            throw new NoSuchElementException("Pay group not found: " + payGroupId);
        }
        if (req.getLogoAlignment() != null && !req.getLogoAlignment().equals("LEFT") && !req.getLogoAlignment().equals("RIGHT")) {
            throw new IllegalArgumentException("logoAlignment must be LEFT or RIGHT");
        }
        if (req.getLogoShape() != null && !req.getLogoShape().equals("SQUARE") && !req.getLogoShape().equals("CIRCLE")) {
            throw new IllegalArgumentException("logoShape must be SQUARE or CIRCLE");
        }
        if (req.getLogoSize() != 0 && (req.getLogoSize() < 30 || req.getLogoSize() > 200)) {
            throw new IllegalArgumentException("logoSize must be between 30 and 200");
        }
        PayslipSettings settings = payslipSettingsRepository.findById(payGroupId)
                .orElseGet(() -> PayslipSettings.builder().payGroupId(payGroupId).build());

        settings.setPasswordProtected(req.isPasswordProtected());
        settings.setDocumentHeaderText(trim(req.getDocumentHeaderText()));
        settings.setFooterText(trim(req.getFooterText()));
        settings.setLogoAlignment(req.getLogoAlignment() != null ? req.getLogoAlignment() : "RIGHT");
        settings.setLogoSize(req.getLogoSize() != 0 ? req.getLogoSize() : 70);
        settings.setLogoShape(req.getLogoShape() != null ? req.getLogoShape() : "SQUARE");
        settings.setExcludeNaFields(req.isExcludeNaFields());
        settings.setEarningsHeading(trim(req.getEarningsHeading()));
        settings.setDeductionsHeading(trim(req.getDeductionsHeading()));
        settings.setIncludeLeaveSummary(req.isIncludeLeaveSummary());
        settings.setIncludeArrearBreakup(req.isIncludeArrearBreakup());
        settings.setIncludeYtdColumn(req.isIncludeYtdColumn());
        settings.setIncludeEmployerContributions(req.isIncludeEmployerContributions());
        settings.setPayslipFields(encodeFields(req.getPayslipFields()));

        return toResponse(payslipSettingsRepository.save(settings));
    }

    private String encodeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) return null;
        Set<String> seen = new LinkedHashSet<>();
        for (String f : fields) {
            if (!PayslipFields.VALID_KEYS.contains(f)) {
                throw new IllegalArgumentException("Unknown payslip field: " + f);
            }
            seen.add(f);
        }
        return String.join(",", seen);
    }

    private List<String> decodeFields(String stored) {
        return PayslipFields.decode(stored);
    }

    @Transactional
    public PayslipSettingsResponse uploadLogo(UUID payGroupId, MultipartFile file) throws IOException {
        if (!payGroupRepository.existsById(payGroupId)) {
            throw new NoSuchElementException("Pay group not found: " + payGroupId);
        }
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_LOGO_BYTES) throw new IllegalArgumentException("Logo must be under 500 KB");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/"))
            throw new IllegalArgumentException("File must be an image");

        PayslipSettings settings = payslipSettingsRepository.findById(payGroupId)
                .orElseGet(() -> PayslipSettings.builder().payGroupId(payGroupId).build());
        settings.setLogo(file.getBytes());
        settings.setLogoContentType(contentType);
        return toResponse(payslipSettingsRepository.save(settings));
    }

    @Transactional
    public PayslipSettingsResponse removeLogo(UUID payGroupId) {
        PayslipSettings settings = payslipSettingsRepository.findById(payGroupId)
                .orElseThrow(() -> new NoSuchElementException("No payslip settings configured for this pay group"));
        settings.setLogo(null);
        settings.setLogoContentType(null);
        return toResponse(payslipSettingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public Logo getLogo(UUID payGroupId) {
        return payslipSettingsRepository.findById(payGroupId)
                .filter(s -> s.getLogo() != null)
                .map(s -> new Logo(s.getLogo(), s.getLogoContentType()))
                .orElse(null);
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
                .hasLogo(s.getLogo() != null)
                .logoAlignment(s.getLogoAlignment())
                .logoSize(s.getLogoSize())
                .logoShape(s.getLogoShape())
                .excludeNaFields(s.isExcludeNaFields())
                .earningsHeading(s.getEarningsHeading())
                .deductionsHeading(s.getDeductionsHeading())
                .includeLeaveSummary(s.isIncludeLeaveSummary())
                .includeArrearBreakup(s.isIncludeArrearBreakup())
                .includeYtdColumn(s.isIncludeYtdColumn())
                .includeEmployerContributions(s.isIncludeEmployerContributions())
                .payslipFields(decodeFields(s.getPayslipFields()))
                .build();
    }
}
