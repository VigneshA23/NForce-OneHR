package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Create/update for {@link LeaveType} (Organization Masters &gt; Leave). {@link LeaveService}
 * keeps owning the read-only {@code #listTypes()} plus all leave-request/balance logic; this
 * service is the CRUD half, mirroring {@link DocumentTypeService}'s split from its entity's read
 * side — LeaveType had no create/update path before this.
 */
@Service
@RequiredArgsConstructor
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public LeaveTypeResponse create(CreateLeaveTypeRequest req, String actorEmail) {
        String code = req.getCode().trim().toUpperCase();
        if (leaveTypeRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("A leave type with code '" + code + "' already exists");
        }
        LeaveType type = LeaveType.builder()
                .code(code)
                .name(req.getName().trim())
                .classification(req.getClassification())
                .build();
        type = leaveTypeRepository.save(type);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(Map.of(
                "code", type.getCode(), "name", type.getName(), "classification", type.getClassification()));
        auditService.log(actor.getId(), "LEAVE_TYPE_CREATED", type.getId(), null, after);

        return toResponse(type);
    }

    @Transactional
    public LeaveTypeResponse update(UUID id, UpdateLeaveTypeRequest req, String actorEmail) {
        LeaveType type = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Leave type not found: " + id));

        String before = auditSnapshot.toJson(Map.of(
                "name", type.getName(), "classification", type.getClassification()));

        if (req.getName() != null && !req.getName().isBlank()) {
            type.setName(req.getName().trim());
        }
        if (req.getClassification() != null) {
            type.setClassification(req.getClassification());
        }
        type = leaveTypeRepository.save(type);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(Map.of(
                "name", type.getName(), "classification", type.getClassification()));
        auditService.log(actor.getId(), "LEAVE_TYPE_UPDATED", type.getId(), before, after);

        return toResponse(type);
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private LeaveTypeResponse toResponse(LeaveType t) {
        return LeaveTypeResponse.builder()
                .id(t.getId())
                .code(t.getCode())
                .name(t.getName())
                .classification(t.getClassification())
                .build();
    }
}
