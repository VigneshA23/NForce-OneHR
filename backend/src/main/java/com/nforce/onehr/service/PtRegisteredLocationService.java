package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreatePtRegisteredLocationRequest;
import com.nforce.onehr.dto.payroll.PtRegisteredLocationResponse;
import com.nforce.onehr.dto.payroll.UpdatePtRegisteredLocationRequest;
import com.nforce.onehr.entity.PtRegisteredLocation;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PtRegisteredLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/** Keka restyle (payroll module) — Professional Tax registrations, per state, per physical
 * location (§3.8 "PT Registered states"). Same "low-stakes reference data, no audit log" call as
 * {@link PayGroupFilingDetailsService}. */
@Service
@RequiredArgsConstructor
public class PtRegisteredLocationService {

    private final PtRegisteredLocationRepository ptRegisteredLocationRepository;
    private final PayGroupRepository payGroupRepository;

    @Transactional(readOnly = true)
    public List<PtRegisteredLocationResponse> list(UUID payGroupId) {
        return ptRegisteredLocationRepository.findByPayGroupIdOrderByStateNameAscLocationNameAsc(payGroupId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public PtRegisteredLocationResponse create(UUID payGroupId, CreatePtRegisteredLocationRequest req) {
        if (!payGroupRepository.existsById(payGroupId)) {
            throw new NoSuchElementException("Pay group not found: " + payGroupId);
        }
        PtRegisteredLocation location = PtRegisteredLocation.builder()
                .payGroupId(payGroupId)
                .stateName(req.getStateName().trim())
                .locationName(req.getLocationName().trim())
                .establishmentId(trim(req.getEstablishmentId()))
                .registrationDate(req.getRegistrationDate())
                .signatory(trim(req.getSignatory()))
                .signatoryDesignation(trim(req.getSignatoryDesignation()))
                .signatoryFatherName(trim(req.getSignatoryFatherName()))
                .build();
        return toResponse(ptRegisteredLocationRepository.save(location));
    }

    @Transactional
    public PtRegisteredLocationResponse update(UUID id, UpdatePtRegisteredLocationRequest req) {
        PtRegisteredLocation location = ptRegisteredLocationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("PT registered location not found: " + id));
        location.setStateName(req.getStateName().trim());
        location.setLocationName(req.getLocationName().trim());
        location.setEstablishmentId(trim(req.getEstablishmentId()));
        location.setRegistrationDate(req.getRegistrationDate());
        location.setSignatory(trim(req.getSignatory()));
        location.setSignatoryDesignation(trim(req.getSignatoryDesignation()));
        location.setSignatoryFatherName(trim(req.getSignatoryFatherName()));
        return toResponse(ptRegisteredLocationRepository.save(location));
    }

    @Transactional
    public void delete(UUID id) {
        if (!ptRegisteredLocationRepository.existsById(id)) {
            throw new NoSuchElementException("PT registered location not found: " + id);
        }
        ptRegisteredLocationRepository.deleteById(id);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private PtRegisteredLocationResponse toResponse(PtRegisteredLocation l) {
        return PtRegisteredLocationResponse.builder()
                .id(l.getId())
                .payGroupId(l.getPayGroupId())
                .stateName(l.getStateName())
                .locationName(l.getLocationName())
                .establishmentId(l.getEstablishmentId())
                .registrationDate(l.getRegistrationDate())
                .signatory(l.getSignatory())
                .signatoryDesignation(l.getSignatoryDesignation())
                .signatoryFatherName(l.getSignatoryFatherName())
                .build();
    }
}
