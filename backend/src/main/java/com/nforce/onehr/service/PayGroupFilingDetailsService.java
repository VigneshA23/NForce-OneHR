package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayGroupFilingDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupFilingDetailsRequest;
import com.nforce.onehr.entity.PayGroupFilingDetails;
import com.nforce.onehr.repository.PayGroupFilingDetailsRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — Income Tax/PF/ESI filing info, one row per pay group (§3.8
 * "Filing Details"). No audit log here (unlike most of this module's other admin screens) — this
 * is low-stakes reference data (registration numbers a company already has, not a workflow
 * decision), and treating every keystroke-save the same as a payroll-affecting change would just
 * add noise to the audit trail.
 */
@Service
@RequiredArgsConstructor
public class PayGroupFilingDetailsService {

    private final PayGroupFilingDetailsRepository payGroupFilingDetailsRepository;
    private final PayGroupRepository payGroupRepository;

    @Transactional(readOnly = true)
    public PayGroupFilingDetailsResponse get(UUID payGroupId) {
        return payGroupFilingDetailsRepository.findById(payGroupId)
                .map(this::toResponse)
                .orElseGet(() -> PayGroupFilingDetailsResponse.builder().payGroupId(payGroupId).build());
    }

    @Transactional
    public PayGroupFilingDetailsResponse update(UUID payGroupId, UpdatePayGroupFilingDetailsRequest req) {
        if (!payGroupRepository.existsById(payGroupId)) {
            throw new NoSuchElementException("Pay group not found: " + payGroupId);
        }
        PayGroupFilingDetails details = payGroupFilingDetailsRepository.findById(payGroupId)
                .orElseGet(() -> PayGroupFilingDetails.builder().payGroupId(payGroupId).build());

        details.setPan(trim(req.getPan()));
        details.setTan(trim(req.getTan()));
        details.setTanCircleNumber(trim(req.getTanCircleNumber()));
        details.setCitTdsLocation(trim(req.getCitTdsLocation()));
        details.setForm130Signatory(trim(req.getForm130Signatory()));
        details.setSignatoryDesignation(trim(req.getSignatoryDesignation()));
        details.setSignatoryFatherName(trim(req.getSignatoryFatherName()));
        details.setPfEstablishmentId(trim(req.getPfEstablishmentId()));
        details.setEsiEstablishmentId(trim(req.getEsiEstablishmentId()));

        return toResponse(payGroupFilingDetailsRepository.save(details));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private PayGroupFilingDetailsResponse toResponse(PayGroupFilingDetails d) {
        return PayGroupFilingDetailsResponse.builder()
                .payGroupId(d.getPayGroupId())
                .pan(d.getPan())
                .tan(d.getTan())
                .tanCircleNumber(d.getTanCircleNumber())
                .citTdsLocation(d.getCitTdsLocation())
                .form130Signatory(d.getForm130Signatory())
                .signatoryDesignation(d.getSignatoryDesignation())
                .signatoryFatherName(d.getSignatoryFatherName())
                .pfEstablishmentId(d.getPfEstablishmentId())
                .esiEstablishmentId(d.getEsiEstablishmentId())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
