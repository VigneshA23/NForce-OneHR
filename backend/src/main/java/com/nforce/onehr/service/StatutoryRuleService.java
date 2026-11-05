package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.payroll.CreateStatutoryRuleRequest;
import com.nforce.onehr.dto.payroll.StatutoryRuleResponse;
import com.nforce.onehr.entity.StatutoryRule;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.StatutoryRuleRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C13 (ONEHR-619, Phase 2) — config/versioning only. See {@link StatutoryRule}'s own doc
 * comment for why {@code slabConfig} is opaque jsonb and {@code ruleType} isn't an enum here:
 * jurisdiction scope (Decision #7) and which mechanism owns each deduction (Decision #5) aren't
 * decided yet. This service never interprets {@code slabConfig} or computes a deduction from it —
 * that's Phase 3's concern, once those decisions land.
 */
@Service
@RequiredArgsConstructor
public class StatutoryRuleService {

    private final StatutoryRuleRepository statutoryRuleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public StatutoryRuleResponse createOrRevise(CreateStatutoryRuleRequest req, String actorEmail) {
        String country = req.getCountry().trim().toUpperCase();
        String ruleType = req.getRuleType().trim().toUpperCase();
        validateJson(req.getSlabConfig());

        Optional<StatutoryRule> current = statutoryRuleRepository
                .findByCountryAndRuleTypeAndEffectiveToIsNull(country, ruleType);
        current.ifPresent(r -> {
            if (!req.getEffectiveFrom().isAfter(r.getEffectiveFrom())) {
                throw new IllegalArgumentException(
                        "effectiveFrom must be after the current rule's effectiveFrom (" + r.getEffectiveFrom() + ")");
            }
        });

        String before = current.map(r -> auditSnapshot.toJson(ruleSnapshot(r))).orElse(null);

        if (current.isPresent()) {
            statutoryRuleRepository.closeCurrentEntry(country, ruleType, req.getEffectiveFrom());
        }

        StatutoryRule saved = statutoryRuleRepository.save(StatutoryRule.builder()
                .country(country)
                .ruleType(ruleType)
                .slabConfig(req.getSlabConfig())
                .effectiveFrom(req.getEffectiveFrom())
                .build());

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(ruleSnapshot(saved));
        auditService.log(actor.getId(), "STATUTORY_RULE_CREATED", saved.getId(), before, after);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<StatutoryRuleResponse> listCurrent() {
        return statutoryRuleRepository.findByEffectiveToIsNullOrderByCountryAscRuleTypeAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StatutoryRuleResponse> history(String country, String ruleType) {
        return statutoryRuleRepository
                .findByCountryAndRuleTypeOrderByEffectiveFromDesc(country.trim().toUpperCase(), ruleType.trim().toUpperCase())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private void validateJson(String slabConfig) {
        try {
            objectMapper.readTree(slabConfig);
        } catch (Exception e) {
            throw new IllegalArgumentException("slabConfig must be valid JSON");
        }
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> ruleSnapshot(StatutoryRule r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("country", r.getCountry());
        snapshot.put("ruleType", r.getRuleType());
        snapshot.put("effectiveFrom", r.getEffectiveFrom());
        snapshot.put("effectiveTo", r.getEffectiveTo());
        return snapshot;
    }

    private StatutoryRuleResponse toResponse(StatutoryRule r) {
        return StatutoryRuleResponse.builder()
                .id(r.getId())
                .country(r.getCountry())
                .ruleType(r.getRuleType())
                .slabConfig(r.getSlabConfig())
                .effectiveFrom(r.getEffectiveFrom())
                .effectiveTo(r.getEffectiveTo())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
