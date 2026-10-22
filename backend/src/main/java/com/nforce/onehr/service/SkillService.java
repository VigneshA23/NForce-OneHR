package com.nforce.onehr.service;

import com.nforce.onehr.dto.skill.SkillDtos.RatingRequest;
import com.nforce.onehr.dto.skill.SkillDtos.RatingResponse;
import com.nforce.onehr.dto.skill.SkillDtos.SkillRequest;
import com.nforce.onehr.dto.skill.SkillDtos.SkillResponse;
import com.nforce.onehr.dto.skill.SkillDtos.TeamRatingResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeSkillRating;
import com.nforce.onehr.entity.SkillTaxonomy;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeSkillRatingRepository;
import com.nforce.onehr.repository.SkillTaxonomyRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-A07: the managed skill taxonomy (HR_ADMIN/SUPER_ADMIN) and per-employee 1-5 ratings. Skills
 * are only ever picked from the taxonomy, never typed freely, so US-A08's skill search is an
 * exact-id match. A self-rating is the employee's own write path; the manager rating is a separate
 * one, allowed only for that employee's current manager or a skill_ratings:EDIT:ALL holder
 * (HR_ADMIN/SUPER_ADMIN by default).
 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillTaxonomyRepository taxonomyRepository;
    private final EmployeeSkillRatingRepository ratingRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final DirectReportScopeService directReportScopeService;
    private final EffectivePermissionService effectivePermissionService;

    // ---- taxonomy ----

    @Transactional(readOnly = true)
    public List<SkillResponse> taxonomy() {
        return taxonomyRepository.findAll().stream()
                .sorted(Comparator.comparing(SkillTaxonomy::getCategory, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SkillTaxonomy::getSkill, String.CASE_INSENSITIVE_ORDER))
                .map(s -> new SkillResponse(s.getId(), s.getSkill(), s.getCategory())).toList();
    }

    @Transactional
    public SkillResponse addSkill(SkillRequest req) {
        String skill = req.skill().trim();
        if (taxonomyRepository.existsBySkillIgnoreCase(skill)) {
            throw new IllegalStateException("Skill \"" + skill + "\" already exists");
        }
        SkillTaxonomy saved = taxonomyRepository.save(
                SkillTaxonomy.builder().skill(skill).category(req.category().trim()).build());
        return new SkillResponse(saved.getId(), saved.getSkill(), saved.getCategory());
    }

    // ---- self ratings ----

    @Transactional(readOnly = true)
    public List<RatingResponse> myRatings(String actorEmail) {
        UUID me = resolveEmployee(actorEmail).getUserId();
        Map<UUID, SkillTaxonomy> skills = taxonomyById();
        return ratingRepository.findByEmployeeUserId(me).stream()
                .filter(r -> skills.containsKey(r.getSkillId()))
                .map(r -> new RatingResponse(r.getSkillId(), skills.get(r.getSkillId()).getSkill(),
                        skills.get(r.getSkillId()).getCategory(), r.getSelfRating(), r.getManagerRating()))
                .sorted(Comparator.comparing(RatingResponse::skill, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Transactional
    public void rateSelf(String actorEmail, RatingRequest req) {
        UUID me = resolveEmployee(actorEmail).getUserId();
        requireSkill(req.skillId());
        EmployeeSkillRating rating = ratingRepository.findByEmployeeUserIdAndSkillId(me, req.skillId())
                .orElseGet(() -> EmployeeSkillRating.builder().employeeUserId(me).skillId(req.skillId()).build());
        rating.setSelfRating(req.rating());
        rating.setUpdatedAt(Instant.now());
        ratingRepository.save(rating);
    }

    @Transactional
    public void removeSelf(String actorEmail, UUID skillId) {
        UUID me = resolveEmployee(actorEmail).getUserId();
        ratingRepository.findByEmployeeUserIdAndSkillId(me, skillId).ifPresent(ratingRepository::delete);
    }

    // ---- manager endorsement ----

    /** Sets the manager rating on an existing self-rating; only the employee's manager or HR may. */
    @Transactional
    public void rateAsManager(String actorEmail, UUID employeeId, UUID skillId, int rating) {
        // Checked here (not with @Min/@Max on the request parameter) so a bad value is a plain 400
        // like the self-rating's body validation, not an unmapped ConstraintViolationException.
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        boolean admin = effectivePermissionService.hasAuthority(actor, "skill_ratings:EDIT:ALL");
        if (!admin && !directReportScopeService.isDirectReport(actorEmail, employeeId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You can only rate the skills of your own direct reports");
        }
        EmployeeSkillRating existing = ratingRepository.findByEmployeeUserIdAndSkillId(employeeId, skillId)
                .orElseThrow(() -> new NoSuchElementException("The employee has not listed this skill"));
        existing.setManagerRating(rating);
        existing.setUpdatedAt(Instant.now());
        ratingRepository.save(existing);
    }

    /** The caller's direct reports' ratings, for the endorse screen. */
    @Transactional(readOnly = true)
    public List<TeamRatingResponse> teamRatings(String actorEmail) {
        Set<UUID> reportIds = directReportScopeService.directReportIds(actorEmail);
        if (reportIds.isEmpty()) return List.of();
        Map<UUID, SkillTaxonomy> skills = taxonomyById();
        Map<UUID, String> names = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        return ratingRepository.findByEmployeeUserIdIn(reportIds).stream()
                .filter(r -> skills.containsKey(r.getSkillId()))
                .map(r -> new TeamRatingResponse(r.getEmployeeUserId(), names.get(r.getEmployeeUserId()),
                        r.getSkillId(), skills.get(r.getSkillId()).getSkill(), r.getSelfRating(), r.getManagerRating()))
                .sorted(Comparator.comparing(TeamRatingResponse::employeeName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(TeamRatingResponse::skill, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private Map<UUID, SkillTaxonomy> taxonomyById() {
        return taxonomyRepository.findAll().stream().collect(Collectors.toMap(SkillTaxonomy::getId, Function.identity()));
    }

    private void requireSkill(UUID skillId) {
        if (!taxonomyRepository.existsById(skillId)) {
            throw new NoSuchElementException("Skill not found in the taxonomy");
        }
    }

    private Employee resolveEmployee(String actorEmail) {
        return directReportScopeService.resolveActor(actorEmail);
    }
}
