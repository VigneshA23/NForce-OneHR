package com.nforce.onehr.service;

import com.nforce.onehr.dto.overtime.OtDtos.RateRuleDto;
import com.nforce.onehr.dto.overtime.OtDtos.RateRuleRequest;
import com.nforce.onehr.dto.overtime.OtDtos.SettingsDto;
import com.nforce.onehr.dto.overtime.OtDtos.UpdateSettingsRequest;
import com.nforce.onehr.entity.OtRateRule;
import com.nforce.onehr.entity.OtSettings;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.OtRateRuleRepository;
import com.nforce.onehr.repository.OtSettingsRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** OT policy settings and the effective-dated rate rules. */
@Service
@RequiredArgsConstructor
public class OtConfigService {

    private static final short SETTINGS_ID = 1;

    private final OtSettingsRepository settingsRepository;
    private final OtRateRuleRepository ruleRepository;
    private final DepartmentRepository departmentRepository;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public OtSettings settings() {
        return settingsRepository.findById(SETTINGS_ID).orElseGet(() -> OtSettings.builder()
                .id(SETTINGS_ID).minOtMinutes(30).roundingMinutes(15).workingDaysPerMonth(new BigDecimal("26"))
                .maxRequestMinutes(720).updatedAt(LocalDateTime.now()).build());
    }

    @Transactional(readOnly = true)
    public SettingsDto getSettings() {
        return toDto(settings());
    }

    @Transactional
    public SettingsDto updateSettings(UpdateSettingsRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        OtSettings s = settingsRepository.findById(SETTINGS_ID)
                .orElseGet(() -> OtSettings.builder().id(SETTINGS_ID).build());
        s.setMinOtMinutes(req.minOtMinutes());
        s.setRoundingMinutes(req.roundingMinutes());
        s.setWorkingDaysPerMonth(req.workingDaysPerMonth());
        s.setRequireApprovedRequest(req.requireApprovedRequest());
        s.setMaxRequestMinutes(req.maxRequestMinutes());
        s.setAllowDuplicateRequests(req.allowDuplicateRequests());
        s.setAdjustmentApprovalRequired(req.adjustmentApprovalRequired());
        s.setUpdatedBy(actor.getId());
        s.setUpdatedAt(LocalDateTime.now());
        settingsRepository.save(s);
        auditService.log(actor.getId(), "OT_SETTINGS_UPDATED", actor.getId());
        return toDto(s);
    }

    // ------------------------------------------------------------------ rate rules

    @Transactional(readOnly = true)
    public List<RateRuleDto> listRules() {
        return ruleRepository.findAllByOrderByEffectiveFromDescNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public RateRuleDto createRule(RateRuleRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        OtRateRule rule = OtRateRule.builder().createdBy(actor.getId()).build();
        apply(rule, req);
        rule = ruleRepository.save(rule);
        auditService.log(actor.getId(), "OT_RATE_RULE_CREATED", rule.getId());
        return toDto(rule);
    }

    @Transactional
    public RateRuleDto updateRule(UUID id, RateRuleRequest req, String actorEmail) {
        User actor = actor(actorEmail);
        OtRateRule rule = ruleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Rate rule not found"));
        apply(rule, req);
        ruleRepository.save(rule);
        auditService.log(actor.getId(), "OT_RATE_RULE_UPDATED", rule.getId());
        return toDto(rule);
    }

    /** Rules are soft-deactivated so historic calculations keep a valid reference. */
    @Transactional
    public void deactivateRule(UUID id, String actorEmail) {
        User actor = actor(actorEmail);
        OtRateRule rule = ruleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Rate rule not found"));
        rule.setActive(false);
        ruleRepository.save(rule);
        auditService.log(actor.getId(), "OT_RATE_RULE_DEACTIVATED", rule.getId());
    }

    /**
     * The most specific active rule on {@code date}. Specificity: department match (4) + shift match
     * (2) + a concrete day type rather than ANY (1); ties go to the latest effective-from.
     */
    public Optional<OtRateRule> resolveRule(List<OtRateRule> activeRules, String dayType, UUID departmentId,
                                            UUID shiftId, LocalDate date) {
        return activeRules.stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(date)
                        && (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(date)))
                .filter(r -> "ANY".equals(r.getDayType()) || r.getDayType().equals(dayType))
                .filter(r -> r.getDepartmentId() == null || r.getDepartmentId().equals(departmentId))
                .filter(r -> r.getShiftId() == null || r.getShiftId().equals(shiftId))
                .max(Comparator.<OtRateRule>comparingInt(r -> (r.getDepartmentId() != null ? 4 : 0)
                                + (r.getShiftId() != null ? 2 : 0) + ("ANY".equals(r.getDayType()) ? 0 : 1))
                        .thenComparing(OtRateRule::getEffectiveFrom));
    }

    public List<OtRateRule> activeRules() {
        return ruleRepository.findByActiveTrue();
    }

    private void apply(OtRateRule rule, RateRuleRequest req) {
        if (req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            throw new IllegalArgumentException("Effective-to date cannot be before effective-from date");
        }
        if (req.departmentId() != null && !departmentRepository.existsById(req.departmentId())) {
            throw new IllegalArgumentException("Department not found");
        }
        if (req.shiftId() != null && !shiftRepository.existsById(req.shiftId())) {
            throw new IllegalArgumentException("Shift not found");
        }
        rule.setName(req.name().trim());
        rule.setDayType(req.dayType());
        rule.setDepartmentId(req.departmentId());
        rule.setShiftId(req.shiftId());
        rule.setMultiplier(req.multiplier());
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        if (req.active() != null) rule.setActive(req.active());
    }

    private RateRuleDto toDto(OtRateRule r) {
        String dept = r.getDepartmentId() == null ? null
                : departmentRepository.findById(r.getDepartmentId()).map(d -> d.getName()).orElse(null);
        String shift = r.getShiftId() == null ? null
                : shiftRepository.findById(r.getShiftId()).map(s -> s.getName()).orElse(null);
        return new RateRuleDto(r.getId(), r.getName(), r.getDayType(), r.getDepartmentId(), dept, r.getShiftId(),
                shift, r.getMultiplier(), r.getEffectiveFrom(), r.getEffectiveTo(), r.isActive());
    }

    private SettingsDto toDto(OtSettings s) {
        return new SettingsDto(s.getMinOtMinutes(), s.getRoundingMinutes(), s.getWorkingDaysPerMonth(),
                s.isRequireApprovedRequest(), s.getMaxRequestMinutes(), s.isAllowDuplicateRequests(),
                s.isAdjustmentApprovalRequired(), s.getUpdatedAt());
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
