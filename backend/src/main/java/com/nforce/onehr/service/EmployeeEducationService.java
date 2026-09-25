package com.nforce.onehr.service;

import com.nforce.onehr.dto.education.EmployeeEducationRequest;
import com.nforce.onehr.dto.education.EmployeeEducationResponse;
import com.nforce.onehr.entity.EmployeeEducation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeEducationRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeEducationService {

    private final EmployeeEducationRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EmployeeEducationResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return repository.findByEmployeeUserIdOrderByStartDateDesc(actorId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeEducationResponse create(String actorEmail, EmployeeEducationRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeEducation entity = EmployeeEducation.builder()
                .employeeUserId(actorId)
                .institutionName(req.getInstitutionName().trim())
                .degree(req.getDegree().trim())
                .fieldOfStudy(req.getFieldOfStudy() != null ? req.getFieldOfStudy().trim() : null)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .build();
        return toResponse(repository.save(entity));
    }

    @Transactional
    public EmployeeEducationResponse update(String actorEmail, UUID id, EmployeeEducationRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeEducation entity = requireOwned(id, actorId);
        entity.setInstitutionName(req.getInstitutionName().trim());
        entity.setDegree(req.getDegree().trim());
        entity.setFieldOfStudy(req.getFieldOfStudy() != null ? req.getFieldOfStudy().trim() : null);
        entity.setStartDate(req.getStartDate());
        entity.setEndDate(req.getEndDate());
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeEducation entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    // Never trusts a client-supplied employeeId — the acting employee is always resolved from the
    // authenticated principal's email, matching ProfileService's own requireUser pattern.
    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // Ownership check: {id} in the URL is attacker-controlled — loading by id alone and trusting
    // it would be an IDOR. Mirrors AssetService#acknowledgeReceipt's find-then-verify pattern.
    private EmployeeEducation requireOwned(UUID id, UUID actorId) {
        EmployeeEducation entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Education record not found"));
        if (!entity.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only modify your own education records");
        }
        return entity;
    }

    private EmployeeEducationResponse toResponse(EmployeeEducation e) {
        return EmployeeEducationResponse.builder()
                .id(e.getId())
                .institutionName(e.getInstitutionName())
                .degree(e.getDegree())
                .fieldOfStudy(e.getFieldOfStudy())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
