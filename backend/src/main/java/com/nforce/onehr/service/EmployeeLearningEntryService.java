package com.nforce.onehr.service;

import com.nforce.onehr.dto.learning.EmployeeLearningEntryRequest;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.entity.EmployeeLearningEntry;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeLearningEntryRepository;
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
public class EmployeeLearningEntryService {

    private final EmployeeLearningEntryRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EmployeeLearningEntryResponse> listMine(String actorEmail) {
        UUID actorId = requireActor(actorEmail).getId();
        return repository.findByEmployeeUserIdOrderByEntryDateDesc(actorId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeLearningEntryResponse create(String actorEmail, EmployeeLearningEntryRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeLearningEntry entity = EmployeeLearningEntry.builder()
                .employeeUserId(actorId)
                .title(req.getTitle().trim())
                .description(req.getDescription() != null ? req.getDescription().trim() : null)
                .entryDate(req.getEntryDate())
                .build();
        return toResponse(repository.save(entity));
    }

    @Transactional
    public EmployeeLearningEntryResponse update(String actorEmail, UUID id, EmployeeLearningEntryRequest req) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeLearningEntry entity = requireOwned(id, actorId);
        entity.setTitle(req.getTitle().trim());
        entity.setDescription(req.getDescription() != null ? req.getDescription().trim() : null);
        entity.setEntryDate(req.getEntryDate());
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        UUID actorId = requireActor(actorEmail).getId();
        EmployeeLearningEntry entity = requireOwned(id, actorId);
        repository.delete(entity);
    }

    // Never trusts a client-supplied employeeId — the acting employee is always resolved from the
    // authenticated principal's email, matching ProfileService's own requireUser pattern.
    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    // Ownership check: {id} in the URL is attacker-controlled — loading by id alone and trusting
    // it would be an IDOR. Mirrors EmployeeEducationService's find-then-verify pattern.
    private EmployeeLearningEntry requireOwned(UUID id, UUID actorId) {
        EmployeeLearningEntry entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Learning entry not found"));
        if (!entity.getEmployeeUserId().equals(actorId)) {
            throw new AccessDeniedException("You can only modify your own learning entries");
        }
        return entity;
    }

    private EmployeeLearningEntryResponse toResponse(EmployeeLearningEntry e) {
        return EmployeeLearningEntryResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .description(e.getDescription())
                .entryDate(e.getEntryDate())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
