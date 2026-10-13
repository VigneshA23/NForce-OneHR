package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.ProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.ProjectAllocationResponse;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.CloseOpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.InterestResponse;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityRequest;
import com.nforce.onehr.dto.opportunity.OpportunityDtos.OpportunityResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.InternalOpportunity;
import com.nforce.onehr.entity.OpportunityInterest;
import com.nforce.onehr.entity.OpportunityStatus;
import com.nforce.onehr.entity.SkillTaxonomy;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.InternalOpportunityRepository;
import com.nforce.onehr.repository.OpportunityInterestRepository;
import com.nforce.onehr.repository.SkillTaxonomyRepository;
import com.nforce.onehr.repository.UserRepository;
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
 * US-A10 (post), US-A11 (express interest) and US-A12 (close by assigning).
 *
 * <p>{@link #close} is deliberately the single place Epic A (bench) and Epic B (allocations) call
 * into each other: in one transaction it creates the allocation through the existing
 * {@link ProjectAllocationService#allocate} (so capacity validation is never re-implemented), ends
 * the employee's bench episode through the same {@link BenchStatusService} path as a manual end,
 * and closes the opportunity. Any failure rolls all three back together.
 */
@Service
@RequiredArgsConstructor
public class OpportunityService {

    public static final String NOTIFICATION_TYPE = "OPPORTUNITY_INTEREST";
    static final String AUDIT_CLOSED = "INTERNAL_OPPORTUNITY_CLOSED";
    private static final Set<String> POSTER_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN", "MANAGER");
    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final InternalOpportunityRepository opportunityRepository;
    private final OpportunityInterestRepository interestRepository;
    private final SkillTaxonomyRepository taxonomyRepository;
    private final BenchStatusRepository benchRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ProjectAllocationService allocationService;
    private final BenchStatusService benchStatusService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;

    @Transactional
    public OpportunityResponse post(String actorEmail, OpportunityRequest req) {
        User actor = resolveUser(actorEmail);
        requireAnyRole(actor, POSTER_ROLES, "Only HR or a Manager can post an opportunity");
        Map<UUID, SkillTaxonomy> skills = taxonomyById();
        for (UUID id : req.requiredSkillIds()) {
            if (!skills.containsKey(id)) throw new NoSuchElementException("Required skill not found in the taxonomy");
        }
        InternalOpportunity saved = opportunityRepository.save(InternalOpportunity.builder()
                .title(req.title().trim())
                .durationWeeks(req.durationWeeks())
                .postedByUserId(actor.getId())
                .requiredSkillIds(Set.copyOf(req.requiredSkillIds()))
                .build());
        return toResponse(saved, skills, Map.of(), Set.of(), nameMap(Set.of(actor.getId())));
    }

    /**
     * What the caller may see: HR/Manager see every opportunity; anyone else sees OPEN ones only
     * while they themselves are on bench (US-A10 visibility rule) and nothing otherwise.
     */
    @Transactional(readOnly = true)
    public List<OpportunityResponse> list(String actorEmail) {
        User actor = resolveUser(actorEmail);
        boolean privileged = hasAnyRole(actor, POSTER_ROLES);
        boolean benched = benchRepository.existsByEmployeeUserIdAndActiveTrue(actor.getId());
        if (!privileged && !benched) return List.of();

        List<InternalOpportunity> opportunities = privileged
                ? opportunityRepository.findAllByOrderByCreatedAtDesc()
                : opportunityRepository.findByStatusOrderByCreatedAtDesc(OpportunityStatus.OPEN);
        Map<UUID, Long> interestCounts = interestRepository.findAll().stream()
                .collect(Collectors.groupingBy(OpportunityInterest::getOpportunityId, Collectors.counting()));
        Set<UUID> mine = interestRepository.findByEmployeeUserId(actor.getId()).stream()
                .map(OpportunityInterest::getOpportunityId).collect(Collectors.toSet());
        Set<UUID> people = opportunities.stream().flatMap(o -> java.util.stream.Stream.of(
                o.getPostedByUserId(), o.getAssignedEmployeeUserId())).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, SkillTaxonomy> skills = taxonomyById();
        Map<UUID, String> names = nameMap(people);
        return opportunities.stream().map(o -> toResponse(o, skills, interestCounts, mine, names)).toList();
    }

    /** US-A11: a benched employee's interest, once per opportunity; the poster is notified. */
    @Transactional
    public void expressInterest(String actorEmail, UUID opportunityId) {
        User actor = resolveUser(actorEmail);
        InternalOpportunity opp = findOpportunity(opportunityId);
        if (!benchRepository.existsByEmployeeUserIdAndActiveTrue(actor.getId())) {
            throw new IllegalStateException("Only employees currently on bench can express interest");
        }
        if (opp.getStatus() != OpportunityStatus.OPEN) {
            throw new IllegalStateException("This opportunity is closed");
        }
        if (interestRepository.existsByOpportunityIdAndEmployeeUserId(opportunityId, actor.getId())) {
            throw new IllegalStateException("You have already expressed interest in this opportunity");
        }
        interestRepository.save(OpportunityInterest.builder()
                .opportunityId(opportunityId).employeeUserId(actor.getId()).build());
        String who = employeeRepository.findById(actor.getId()).map(Employee::getFullName).orElse("A benched employee");
        notificationService.send(opp.getPostedByUserId(), NOTIFICATION_TYPE, "Interest in your opportunity",
                who + " is interested in \"" + opp.getTitle() + "\".", "/bench-opportunities");
    }

    @Transactional(readOnly = true)
    public List<InterestResponse> interests(String actorEmail, UUID opportunityId) {
        User actor = resolveUser(actorEmail);
        InternalOpportunity opp = findOpportunity(opportunityId);
        if (!hasAnyRole(actor, ADMIN_ROLES) && !opp.getPostedByUserId().equals(actor.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Only the poster or HR can see who is interested");
        }
        List<OpportunityInterest> rows = interestRepository.findByOpportunityIdOrderByExpressedAtAsc(opportunityId);
        Map<UUID, String> names = employeeRepository.findAllById(
                rows.stream().map(OpportunityInterest::getEmployeeUserId).toList()).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        return rows.stream().map(i -> new InterestResponse(i.getEmployeeUserId(),
                names.get(i.getEmployeeUserId()), i.getExpressedAt())).toList();
    }

    /** US-A12: allocate + end bench + close the opportunity, all or nothing. */
    @Transactional
    public OpportunityResponse close(String actorEmail, UUID opportunityId, CloseOpportunityRequest req) {
        User actor = resolveUser(actorEmail);
        requireAnyRole(actor, ADMIN_ROLES, "Only HR can close an opportunity");
        InternalOpportunity opp = findOpportunity(opportunityId);
        if (opp.getStatus() != OpportunityStatus.OPEN) {
            throw new IllegalStateException("This opportunity is already closed");
        }

        ProjectAllocationRequest allocationReq = new ProjectAllocationRequest();
        allocationReq.setEmployeeId(req.employeeId());
        allocationReq.setProjectId(req.projectId());
        allocationReq.setCapacityPercent(req.capacityPercent());
        allocationReq.setStartDate(req.startDate());
        allocationReq.setEndDate(req.endDate());
        ProjectAllocationResponse allocation = allocationService.allocate(allocationReq);

        benchStatusService.endForAllocation(actor.getId(), req.employeeId(), allocation.getId(), req.startDate());

        opp.setStatus(OpportunityStatus.CLOSED);
        opp.setAssignedEmployeeUserId(req.employeeId());
        opp.setClosedAt(Instant.now());
        InternalOpportunity saved = opportunityRepository.save(opp);
        // target_id is the assigned employee (the audit trail's convention); the opportunity and
        // allocation ids travel in the snapshot.
        String after = snapshotSerializer.toJson(java.util.Map.of(
                "opportunityId", saved.getId(), "allocationId", allocation.getId(), "status", saved.getStatus()));
        AuditService.afterCommit(() -> auditService.log(actor.getId(), AUDIT_CLOSED, req.employeeId(), null, after));

        Map<UUID, SkillTaxonomy> skills = taxonomyById();
        // HashSet, not Set.of: the poster can also be the assignee, and Set.of rejects duplicates.
        return toResponse(saved, skills, Map.of(), Set.of(),
                nameMap(new java.util.HashSet<>(List.of(saved.getPostedByUserId(), req.employeeId()))));
    }

    private OpportunityResponse toResponse(InternalOpportunity o, Map<UUID, SkillTaxonomy> skills,
                                            Map<UUID, Long> interestCounts, Set<UUID> mine, Map<UUID, String> names) {
        List<String> skillNames = o.getRequiredSkillIds().stream().filter(skills::containsKey)
                .map(id -> skills.get(id).getSkill()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new OpportunityResponse(o.getId(), o.getTitle(), o.getDurationWeeks(), o.getStatus(), skillNames,
                names.get(o.getPostedByUserId()), o.getCreatedAt(), interestCounts.getOrDefault(o.getId(), 0L),
                mine.contains(o.getId()), o.getAssignedEmployeeUserId() == null ? null : names.get(o.getAssignedEmployeeUserId()));
    }

    private Map<UUID, SkillTaxonomy> taxonomyById() {
        return taxonomyRepository.findAll().stream().collect(Collectors.toMap(SkillTaxonomy::getId, Function.identity()));
    }

    private Map<UUID, String> nameMap(Set<UUID> userIds) {
        return employeeRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
    }

    private InternalOpportunity findOpportunity(UUID id) {
        return opportunityRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Opportunity not found"));
    }

    private User resolveUser(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private boolean hasAnyRole(User user, Set<String> roles) {
        return user.getRoles().stream().anyMatch(r -> roles.contains(r.getCode()));
    }

    private void requireAnyRole(User user, Set<String> roles, String message) {
        if (!hasAnyRole(user, roles)) throw new org.springframework.security.access.AccessDeniedException(message);
    }
}
