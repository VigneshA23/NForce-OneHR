package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreatePayGroupRequest;
import com.nforce.onehr.dto.payroll.PayGroupResponse;
import com.nforce.onehr.dto.payroll.UpdatePayGroupRequest;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C19 — Pay Group and pay schedule. Multiple pay groups are supported (per this story's own
 * dev notes) though only one ("Default pay group," seeded by V252) is used today.
 *
 * <p>US-C19's own acceptance criteria also require "schedule cannot be changed while a cycle is
 * open (processed but not locked)" — not enforced here yet, since "a cycle" is a {@code
 * PayrollRun} (US-C08), which doesn't exist in this codebase yet. {@link #update} will need that
 * guard added once US-C08 lands; until then there is nothing to check against.
 */
@Service
@RequiredArgsConstructor
public class PayGroupService {

    private final PayGroupRepository payGroupRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public PayGroupResponse create(CreatePayGroupRequest req, String actorEmail) {
        PayGroup group = PayGroup.builder()
                .name(req.getName().trim())
                .legalEntity(req.getLegalEntity() != null ? req.getLegalEntity().trim() : null)
                .frequency(req.getFrequency())
                .currency(req.getCurrency())
                .cycleStartDay(req.getCycleStartDay())
                .cycleEndDay(req.getCycleEndDay())
                .processingDay(req.getProcessingDay())
                .build();
        group = payGroupRepository.save(group);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(snapshot(group));
        auditService.log(actor.getId(), "PAY_GROUP_CREATED", group.getId(), null, after);

        return toResponse(group);
    }

    @Transactional
    public PayGroupResponse update(UUID id, UpdatePayGroupRequest req, String actorEmail) {
        PayGroup group = payGroupRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + id));

        String before = auditSnapshot.toJson(snapshot(group));

        if (req.getName() != null && !req.getName().isBlank()) group.setName(req.getName().trim());
        if (req.getLegalEntity() != null) group.setLegalEntity(req.getLegalEntity().trim());
        if (req.getFrequency() != null) group.setFrequency(req.getFrequency());
        if (req.getCurrency() != null) group.setCurrency(req.getCurrency());
        if (req.getCycleStartDay() != null) group.setCycleStartDay(req.getCycleStartDay());
        if (req.getCycleEndDay() != null) group.setCycleEndDay(req.getCycleEndDay());
        if (req.getProcessingDay() != null) group.setProcessingDay(req.getProcessingDay());
        if (req.getActive() != null) group.setActive(req.getActive());
        group = payGroupRepository.save(group);

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(snapshot(group));
        auditService.log(actor.getId(), "PAY_GROUP_UPDATED", group.getId(), before, after);

        return toResponse(group);
    }

    @Transactional(readOnly = true)
    public List<PayGroupResponse> list() {
        return payGroupRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PayGroupResponse get(UUID id) {
        return payGroupRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + id));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> snapshot(PayGroup g) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", g.getName());
        snapshot.put("legalEntity", g.getLegalEntity());
        snapshot.put("frequency", g.getFrequency());
        snapshot.put("currency", g.getCurrency());
        snapshot.put("cycleStartDay", g.getCycleStartDay());
        snapshot.put("cycleEndDay", g.getCycleEndDay());
        snapshot.put("processingDay", g.getProcessingDay());
        snapshot.put("active", g.isActive());
        return snapshot;
    }

    private PayGroupResponse toResponse(PayGroup g) {
        return PayGroupResponse.builder()
                .id(g.getId())
                .name(g.getName())
                .legalEntity(g.getLegalEntity())
                .frequency(g.getFrequency())
                .currency(g.getCurrency())
                .cycleStartDay(g.getCycleStartDay())
                .cycleEndDay(g.getCycleEndDay())
                .processingDay(g.getProcessingDay())
                .active(g.isActive())
                .build();
    }
}
