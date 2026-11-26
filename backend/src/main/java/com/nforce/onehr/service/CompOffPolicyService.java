package com.nforce.onehr.service;

import com.nforce.onehr.dto.compoff.CompOffDtos.PolicyDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.SettingsDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.UpdatePolicyRequest;
import com.nforce.onehr.dto.compoff.CompOffDtos.UpdateRulesRequest;
import com.nforce.onehr.entity.CompOffSettings;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CompOffSettingsRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Company policy for extra working hours. The Super Admin decides whether Overtime, Comp-Off or both
 * are in force (at least one must be); HR/Admin tunes the Comp-Off eligibility rules and validity.
 * Defaults (OT on, Comp-Off off) leave the existing OT behaviour exactly as it was.
 */
@Service
@RequiredArgsConstructor
public class CompOffPolicyService {

    private static final short SETTINGS_ID = 1;

    private final CompOffSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public CompOffSettings settings() {
        return settingsRepository.findById(SETTINGS_ID).orElseGet(CompOffSettings::defaults);
    }

    public boolean overtimeEnabled() {
        return settings().isOvertimeEnabled();
    }

    public boolean compOffEnabled() {
        return settings().isCompOffEnabled();
    }

    @Transactional(readOnly = true)
    public SettingsDto getSettings() {
        return toDto(settings());
    }

    @Transactional(readOnly = true)
    public PolicyDto getPolicy() {
        CompOffSettings s = settings();
        return new PolicyDto(s.isOvertimeEnabled(), s.isCompOffEnabled(), s.isAllowPartialDay());
    }

    @Transactional
    public SettingsDto updatePolicy(UpdatePolicyRequest req, String actorEmail) {
        if (!req.overtimeEnabled() && !req.compOffEnabled()) {
            throw new IllegalArgumentException("Enable Overtime, Comp-Off or both - at least one is required");
        }
        User actor = actor(actorEmail);
        CompOffSettings s = load();
        boolean changed = s.isOvertimeEnabled() != req.overtimeEnabled() || s.isCompOffEnabled() != req.compOffEnabled();
        s.setOvertimeEnabled(req.overtimeEnabled());
        s.setCompOffEnabled(req.compOffEnabled());
        stamp(s, actor);
        settingsRepository.save(s);
        if (changed) auditService.log(actor.getId(), "COMPENSATION_POLICY_UPDATED", actor.getId());
        return toDto(s);
    }

    @Transactional
    public SettingsDto updateRules(UpdateRulesRequest req, String actorEmail) {
        if (req.minMinutesFullDay() < req.minMinutesHalfDay()) {
            throw new IllegalArgumentException("Full-day minimum cannot be less than the half-day minimum");
        }
        User actor = actor(actorEmail);
        CompOffSettings s = load();
        s.setEligibleWeeklyOff(req.eligibleWeeklyOff());
        s.setEligibleHoliday(req.eligibleHoliday());
        s.setIncludeWeekdayExtra(req.includeWeekdayExtra());
        s.setMinMinutesHalfDay(req.minMinutesHalfDay());
        s.setMinMinutesFullDay(req.minMinutesFullDay());
        s.setValidityDays(req.validityDays());
        s.setRequireCreditApproval(req.requireCreditApproval());
        s.setAllowPartialDay(req.allowPartialDay());
        s.setExpiryNoticeDays(req.expiryNoticeDays());
        stamp(s, actor);
        settingsRepository.save(s);
        auditService.log(actor.getId(), "COMP_OFF_RULES_UPDATED", actor.getId());
        return toDto(s);
    }

    private CompOffSettings load() {
        return settingsRepository.findById(SETTINGS_ID).orElseGet(CompOffSettings::defaults);
    }

    private void stamp(CompOffSettings s, User actor) {
        s.setUpdatedBy(actor.getId());
        s.setUpdatedAt(LocalDateTime.now());
    }

    private SettingsDto toDto(CompOffSettings s) {
        return new SettingsDto(s.isOvertimeEnabled(), s.isCompOffEnabled(), s.isEligibleWeeklyOff(),
                s.isEligibleHoliday(), s.isIncludeWeekdayExtra(), s.getMinMinutesHalfDay(), s.getMinMinutesFullDay(),
                s.getValidityDays(), s.isRequireCreditApproval(), s.isAllowPartialDay(), s.getExpiryNoticeDays(),
                s.getUpdatedAt());
    }

    private User actor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
