package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Policy;
import com.nforce.onehr.entity.PolicyAcknowledgment;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolicyService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> ALL_ROLE_CODES = Set.of("EMPLOYEE", "MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    // Policy notification "Open Related Page" destination — every recipient, regardless of
    // role, lands on their own self-scoped My Documents & Policies → Policies tab, never the
    // HR/admin policy-management page.
    private static final String POLICIES_TAB_LINK = "/my-documents?tab=policies";

    private final PolicyRepository policyRepo;
    private final PolicyAcknowledgmentRepository ackRepo;
    private final UserRepository userRepo;
    private final EmployeeRepository employeeRepo;
    private final NotificationService notificationService;

    // ── Employee: my policies (filtered by audience) ──

    @Transactional(readOnly = true)
    public List<PolicyResponse> myPolicies(String actorEmail) {
        User actor = requireUser(actorEmail);
        Set<String> actorRoles = actor.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        UUID actorId = actor.getId();

        List<Policy> active = policyRepo.findByActiveTrueOrderByPublishedAtDesc();
        List<Policy> applicable = active.stream()
                .filter(p -> matchesAudience(p.getAudience(), actorRoles))
                .collect(Collectors.toList());

        Map<Long, PolicyAcknowledgment> myAcks = ackRepo.findByEmployeeUserIdOrderByPolicy_PublishedAtDesc(actorId).stream()
                .collect(Collectors.toMap(a -> a.getPolicy().getId(), a -> a, (a, b) -> a));

        return applicable.stream().map(p -> {
            PolicyAcknowledgment ack = myAcks.get(p.getId());
            return PolicyResponse.from(p, ack != null ? ack.getAcknowledgedAt() != null : null,
                    ack != null ? ack.getAcknowledgedAt() : null);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long countPendingRequiredForEmployee(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        return ackRepo.countPendingRequiredForEmployee(actorId);
    }

    // ── Employee: acknowledge policy ──

    @Transactional
    public void acknowledge(String actorEmail, Long policyId) {
        UUID actorId = requireUser(actorEmail).getId();
        PolicyAcknowledgment ack = ackRepo.findByPolicyIdAndEmployeeUserId(policyId, actorId)
                .orElseThrow(() -> new NoSuchElementException("Acknowledgment record not found"));
        if (ack.getAcknowledgedAt() != null) return;
        ack.setAcknowledgedAt(Instant.now());
        ackRepo.save(ack);
    }

    // ── HR/SA: list all policies ──

    @Transactional(readOnly = true)
    public List<PolicyResponse> listAll(String actorEmail) {
        requireAdminRole(actorEmail);
        return policyRepo.findAllByOrderByPublishedAtDesc().stream()
                .map(PolicyResponse::from)
                .collect(Collectors.toList());
    }

    // ── HR/SA: publish new policy ──

    @Transactional
    public PolicyResponse publish(String actorEmail, PublishPolicyRequest req) {
        User actor = requireUser(actorEmail);
        requireAdminRole(actorEmail);

        policyRepo.findByTitleOrderByPublishedAtDesc(req.getTitle()).forEach(old -> {
            old.setActive(false);
            policyRepo.save(old);
        });

        Policy p = Policy.builder()
                .title(req.getTitle())
                .version(req.getVersion())
                .description(req.getDescription())
                .audience(req.getAudience() != null ? req.getAudience() : "All Employees")
                .required(req.isRequired())
                .publishedBy(actor.getId())
                .publishedAt(Instant.now())
                .build();
        p = policyRepo.save(p);

        seedAcknowledgmentsAndNotify(p, "New Policy: " + p.getTitle(),
                "Please review and acknowledge version " + p.getVersion() + ".");

        return PolicyResponse.from(p);
    }

    // ── HR/SA: publish a substantive new version of an existing policy ──
    //
    // Separate from editPolicy (metadata-only, same version, acknowledgments untouched). This
    // increments the version, makes the new content current, and requires every employee to
    // re-acknowledge — previous acknowledgments stay attached to the superseded version's row and
    // are preserved for audit, never deleted or mutated.

    @Transactional
    public PolicyResponse publishNewVersion(String actorEmail, Long policyId, PublishPolicyVersionRequest req) {
        User actor = requireUser(actorEmail);
        requireAdminRole(actorEmail);

        Policy current = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));

        // Flush the supersede before inserting the new version's acknowledgment rows below — not
        // required for correctness here (no uniqueness constraint spans policy rows), but keeps
        // the write order explicit and matches the document-versioning supersede-then-insert
        // pattern.
        current.setActive(false);
        policyRepo.saveAndFlush(current);

        String title = notBlank(req.getTitle(), current.getTitle());
        String description = notBlank(req.getDescription(), current.getDescription());
        String audience = notBlank(req.getAudience(), current.getAudience());
        boolean required = req.getRequired() != null ? req.getRequired() : current.isRequired();
        String version = notBlank(req.getVersion(), suggestNextVersion(current.getVersion()));

        Policy p = Policy.builder()
                .title(title)
                .version(version)
                .versionNumber(current.getVersionNumber() + 1)
                .previousVersionId(current.getId())
                .description(description)
                .audience(audience)
                .required(required)
                .publishedBy(actor.getId())
                .publishedAt(Instant.now())
                .active(true)
                .build();
        p = policyRepo.save(p);

        seedAcknowledgmentsAndNotify(p, "Updated Policy: " + p.getTitle(),
                "A new version (" + p.getVersion() + ") has been published. Please review and acknowledge again.");

        return PolicyResponse.from(p);
    }

    // ── HR/SA: this policy's version chain, oldest first, for grouped acknowledgment history ──

    @Transactional(readOnly = true)
    public List<PolicyResponse> getVersionHistory(String actorEmail, Long policyId) {
        requireAdminRole(actorEmail);
        Policy anchor = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));

        LinkedList<Policy> chain = new LinkedList<>();
        chain.add(anchor);

        Policy cursor = anchor;
        while (cursor.getPreviousVersionId() != null) {
            Long previousId = cursor.getPreviousVersionId();
            cursor = policyRepo.findById(previousId)
                    .orElseThrow(() -> new NoSuchElementException("Policy not found: " + previousId));
            chain.addFirst(cursor);
        }
        cursor = anchor;
        Optional<Policy> next = policyRepo.findByPreviousVersionId(cursor.getId());
        while (next.isPresent()) {
            cursor = next.get();
            chain.addLast(cursor);
            next = policyRepo.findByPreviousVersionId(cursor.getId());
        }

        return chain.stream().map(PolicyResponse::from).collect(Collectors.toList());
    }

    private void seedAcknowledgmentsAndNotify(Policy p, String notificationTitle, String notificationMessage) {
        Set<String> audienceRoles = parseAudienceToRoleCodes(p.getAudience());
        List<Employee> targetEmployees = audienceRoles.equals(ALL_ROLE_CODES)
                ? employeeRepo.findAllActiveWithDetails()
                : employeeRepo.findActiveByRoleCodes(audienceRoles);

        for (Employee emp : targetEmployees) {
            PolicyAcknowledgment ack = PolicyAcknowledgment.builder()
                    .policy(p)
                    .employeeUserId(emp.getUserId())
                    .build();
            ackRepo.save(ack);
            notificationService.send(emp.getUserId(), "POLICY_PUBLISHED", notificationTitle, notificationMessage, POLICIES_TAB_LINK);
        }
    }

    private static String notBlank(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate : fallback;
    }

    // "1.0" -> "2.0", "3.2" -> "4.0"; anything unparsable falls back to "2.0" — same
    // auto-suggestion the existing PublishModal frontend already does for a same-titled republish.
    private static String suggestNextVersion(String currentVersion) {
        try {
            int major = Integer.parseInt(currentVersion.split("\\.")[0]);
            return (major + 1) + ".0";
        } catch (Exception e) {
            return "2.0";
        }
    }

    // ── HR/SA: edit policy (same version, update metadata) ──

    @Transactional
    public PolicyResponse editPolicy(String actorEmail, Long policyId, UpdatePolicyRequest req) {
        requireAdminRole(actorEmail);
        Policy p = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));
        if (req.getTitle() != null && !req.getTitle().isBlank()) p.setTitle(req.getTitle());
        if (req.getDescription() != null && !req.getDescription().isBlank()) p.setDescription(req.getDescription());
        if (req.getAudience() != null && !req.getAudience().isBlank()) p.setAudience(req.getAudience());
        return PolicyResponse.from(policyRepo.save(p));
    }

    // ── HR/SA: deactivate policy (soft) ──

    @Transactional
    public PolicyResponse deactivatePolicy(String actorEmail, Long policyId) {
        requireAdminRole(actorEmail);
        Policy p = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));
        p.setActive(false);
        return PolicyResponse.from(policyRepo.save(p));
    }

    // ── HR/SA: reactivate policy ──

    @Transactional
    public PolicyResponse reactivatePolicy(String actorEmail, Long policyId) {
        requireAdminRole(actorEmail);
        Policy p = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));
        p.setActive(true);
        return PolicyResponse.from(policyRepo.save(p));
    }

    // ── HR/SA: delete policy (hard delete) ──

    @Transactional
    public void deletePolicy(String actorEmail, Long policyId) {
        requireAdminRole(actorEmail);
        if (!policyRepo.existsById(policyId)) throw new NoSuchElementException("Policy not found: " + policyId);
        ackRepo.deleteByPolicyId(policyId);
        policyRepo.deleteById(policyId);
    }

    // ── HR/SA: acknowledgment status for a policy ──

    @Transactional(readOnly = true)
    public List<PolicyAcknowledgmentResponse> listAcknowledgments(String actorEmail, Long policyId) {
        requireAdminRole(actorEmail);
        Set<UUID> adminIds = userRepo.findAdminUserIds();
        List<PolicyAcknowledgment> acks = ackRepo.findByPolicyIdOrderByAcknowledgedAtDesc(policyId).stream()
                .filter(a -> !adminIds.contains(a.getEmployeeUserId()))
                .collect(Collectors.toList());
        Set<UUID> ids = acks.stream().map(PolicyAcknowledgment::getEmployeeUserId).collect(Collectors.toSet());
        Map<UUID, String> names = ids.isEmpty() ? Collections.emptyMap()
                : employeeRepo.findNamesByUserIds(ids).stream()
                        .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
        return acks.stream()
                .map(a -> PolicyAcknowledgmentResponse.from(a, names.get(a.getEmployeeUserId())))
                .collect(Collectors.toList());
    }

    // ── HR/SA: global pending ack count ──

    @Transactional(readOnly = true)
    public long countAllPendingRequired() {
        return ackRepo.countAllPendingRequired();
    }

    // ── HR/SA: reset acknowledgment ──

    @Transactional
    public void resetAcknowledgment(String actorEmail, Long policyId, UUID employeeUserId) {
        requireAdminRole(actorEmail);
        PolicyAcknowledgment ack = ackRepo.findByPolicyIdAndEmployeeUserId(policyId, employeeUserId)
                .orElseThrow(() -> new NoSuchElementException("Acknowledgment not found"));
        ack.setAcknowledgedAt(null);
        ackRepo.save(ack);
    }

    // ── HR/SA: remind employee about pending policy ──

    @Transactional
    public void remindEmployee(String actorEmail, Long policyId, UUID employeeUserId) {
        requireAdminRole(actorEmail);
        Policy p = policyRepo.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Policy not found: " + policyId));
        notificationService.send(employeeUserId, "POLICY_REMINDER",
                "Policy Reminder: " + p.getTitle(),
                "Please review and acknowledge version " + p.getVersion() + " of this policy.",
                POLICIES_TAB_LINK);
    }

    // ── Helpers ──

    private Set<String> parseAudienceToRoleCodes(String audience) {
        if (audience == null || audience.isBlank()
                || audience.equalsIgnoreCase("ALL")
                || audience.equalsIgnoreCase("All Employees")) {
            return ALL_ROLE_CODES;
        }
        Set<String> codes = Arrays.stream(audience.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return codes.isEmpty() ? ALL_ROLE_CODES : codes;
    }

    private boolean matchesAudience(String audience, Set<String> userRoles) {
        if (audience == null || audience.isBlank()
                || audience.equalsIgnoreCase("ALL")
                || audience.equalsIgnoreCase("All Employees")) {
            return true;
        }
        Set<String> audienceRoles = Arrays.stream(audience.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        return audienceRoles.stream().anyMatch(userRoles::contains);
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private void requireAdminRole(String email) {
        User u = requireUser(email);
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("Access denied");
        }
    }
}
