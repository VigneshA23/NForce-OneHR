package com.nforce.onehr.service;

import com.nforce.onehr.dto.audit.AuditLogEntryDto;
import com.nforce.onehr.dto.audit.AuditLogStatsDto;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.nforce.onehr.repository.AuditLogSpecifications.actionIn;
import static com.nforce.onehr.repository.AuditLogSpecifications.actorIdIn;
import static com.nforce.onehr.repository.AuditLogSpecifications.occurredBetween;
import static com.nforce.onehr.repository.AuditLogSpecifications.targetIdIn;

/**
 * Read side of audit logging — deliberately separate from the write-only {@link AuditService},
 * matching the CQRS-style split implied by "read side of audit history."
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditTargetResolver targetResolver;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<AuditLogEntryDto> search(String targetSearch, String action,
            AuditActionGroup group, LocalDateTime from, LocalDateTime to,
            int page, int size, boolean isSuperAdmin, String callerEmail) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Optional<Specification<AuditLog>> spec =
                buildSpec(targetSearch, action, group, from, to, isSuperAdmin, callerEmail);
        if (spec.isEmpty()) return Page.empty(pageable);

        Page<AuditLog> rows = auditLogRepository.findAll(spec.get(), pageable);
        return rows.map(buildRowMapper(rows.getContent()));
    }

    /** Unpaginated fetch of every row matching the current filters — backs the Excel export. */
    @Transactional(readOnly = true)
    public List<AuditLogEntryDto> searchAll(String targetSearch, String action,
            AuditActionGroup group, LocalDateTime from, LocalDateTime to, boolean isSuperAdmin, String callerEmail) {
        Optional<Specification<AuditLog>> spec =
                buildSpec(targetSearch, action, group, from, to, isSuperAdmin, callerEmail);
        if (spec.isEmpty()) return List.of();

        List<AuditLog> rows = auditLogRepository.findAll(spec.get(), Sort.by(Sort.Direction.DESC, "occurredAt"));
        Function<AuditLog, AuditLogEntryDto> mapper = buildRowMapper(rows);
        return rows.stream().map(mapper).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AuditLogStatsDto stats(String targetSearch, String action,
            LocalDateTime from, LocalDateTime to, boolean isSuperAdmin, String callerEmail) {
        // Group is intentionally excluded from the stats spec: the stat cards/chip counts always
        // reflect the whole role-scoped, target/date-filtered corpus so a user can use them
        // to navigate between chips, not just describe whichever chip happens to be active.
        Optional<Specification<AuditLog>> baseSpec =
                buildSpec(targetSearch, action, null, from, to, isSuperAdmin, callerEmail);
        if (baseSpec.isEmpty()) {
            Map<String, Long> zeroed = new LinkedHashMap<>();
            for (AuditActionGroup g : AuditActionGroup.values()) {
                if (g == AuditActionGroup.ACCESS && !isSuperAdmin) continue;
                zeroed.put(g.name(), 0L);
            }
            return AuditLogStatsDto.builder().totalCount(0).todayCount(0).byGroup(zeroed).build();
        }

        Specification<AuditLog> spec = baseSpec.get();

        // Single grouped-aggregate query replaces what used to be 1 (total) + 1 (today) + up to 7
        // (one per AuditActionGroup) sequential COUNT queries. AuditActionGroup.of(action) is a
        // pure, deterministic function of the action string (prefix-based — see AuditActionGroup),
        // so "count per action, bucketed into groups in memory" is exactly equivalent to "count per
        // group via actionIn(group.knownActions())" — same predicate, same rows, same numbers.
        // Summing all per-action counts also gives the same value as a separate count(spec) would,
        // since the grouping query already applies the identical spec predicate.
        Map<String, Long> countsByAction = countByAction(spec);
        long totalCount = countsByAction.values().stream().mapToLong(Long::longValue).sum();

        // "Today" has a different WHERE clause (date-bounded) so it still needs its own query —
        // that's the one remaining round trip beyond the grouped query above.
        // Pinned to UTC, not the JVM default zone — occurredAt is now an unambiguous UTC Instant,
        // so the "today" boundary compared against it needs to be computed the same way.
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long todayCount = auditLogRepository.count(spec.and(occurredBetween(startOfToday, Instant.now())));

        Map<String, Long> byGroup = new LinkedHashMap<>();
        for (AuditActionGroup g : AuditActionGroup.values()) {
            if (g == AuditActionGroup.ACCESS && !isSuperAdmin) continue; // never surface an access-control count to HR Admin
            long groupCount = 0L;
            for (Map.Entry<String, Long> e : countsByAction.entrySet()) {
                if (AuditActionGroup.of(e.getKey()) == g) groupCount += e.getValue();
            }
            byGroup.put(g.name(), groupCount);
        }
        return AuditLogStatsDto.builder().totalCount(totalCount).todayCount(todayCount).byGroup(byGroup).build();
    }

    /**
     * One {@code SELECT action, COUNT(*) ... GROUP BY action} query, scoped by the same
     * {@link Specification} predicate the rest of {@code stats()} uses, so results stay in sync
     * with the row-level filters (actor scoping, target search, date range) automatically.
     */
    private Map<String, Long> countByAction(Specification<AuditLog> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AuditLog> root = cq.from(AuditLog.class);
        Predicate predicate = spec.toPredicate(root, cq, cb);
        cq.multiselect(root.get("action"), cb.count(root));
        if (predicate != null) {
            cq.where(predicate);
        }
        cq.groupBy(root.get("action"));

        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : entityManager.createQuery(cq).getResultList()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Returns empty when the filters can prove ahead of time that nothing will match (a
     * target search with zero hits, or an action/group outside what this caller is allowed
     * to see) — callers use this to skip querying {@code audit_log} entirely rather than issuing
     * a query with an empty {@code IN ()} clause.
     *
     * <p>{@code callerId} is unconditionally ANDed in — this feature shows a personal activity
     * history, not a system-wide trail, so every query is scoped to rows the caller themselves
     * generated. There is no actor-search filter: since every row is already the caller's own,
     * a free-text "who performed this" search would be redundant by construction. This self-scope
     * is layered on top of, not instead of, the existing category boundary from
     * {@link #resolveAllowedActions}: HR Admin is still structurally incapable of matching an
     * ACCESS_CONTROL action, self-scope just narrows further to "only rows I created."
     */
    private Optional<Specification<AuditLog>> buildSpec(String targetSearch, String action,
            AuditActionGroup group, LocalDateTime from, LocalDateTime to, boolean isSuperAdmin, String callerEmail) {
        Set<String> allowedActions = resolveAllowedActions(action, group, isSuperAdmin);
        if (allowedActions.isEmpty()) return Optional.empty();

        UUID callerId = requireActor(callerEmail).getId();
        Specification<AuditLog> spec = Specification.where(actionIn(allowedActions))
                .and(actorIdIn(Set.of(callerId)));
        if (from != null || to != null) {
            // from/to arrive as zone-naive LocalDateTime (the frontend sends bare
            // "yyyy-MM-ddTHH:mm:ss" day boundaries) — converted here, in plain Java, to the
            // Instant occurredAt is now stored as. Deliberately UTC: this is the same convention
            // occurredAt itself uses, so a "from" of midnight still means midnight, just now
            // unambiguously.
            Instant fromInstant = from != null ? from.toInstant(ZoneOffset.UTC) : null;
            Instant toInstant = to != null ? to.toInstant(ZoneOffset.UTC) : null;
            spec = spec.and(occurredBetween(fromInstant, toInstant));
        }
        if (targetSearch != null && !targetSearch.isBlank()) {
            Set<UUID> targetIds = targetResolver.resolveTargetIdsMatching(targetSearch);
            if (targetIds.isEmpty()) return Optional.empty();
            spec = spec.and(targetIdIn(targetIds));
        }
        return Optional.of(spec);
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    /**
     * The actual security boundary: which action strings the caller is allowed to see at all,
     * before any Specification/JPA machinery is involved. Package-private and side-effect-free
     * so it's directly unit-testable without mocking a CriteriaBuilder — this is what guarantees
     * HR Admin never receives an ACCESS_CONTROL action regardless of what {@code group}/{@code
     * action} they pass.
     */
    Set<String> resolveAllowedActions(String action, AuditActionGroup group, boolean isSuperAdmin) {
        Set<String> allowed = isSuperAdmin
                ? AuditActionCategory.allActions()
                : AuditActionCategory.HR_OPERATIONAL.actions();
        if (group != null) {
            Set<String> groupActions = group.knownActions();
            allowed = allowed.stream().filter(groupActions::contains).collect(Collectors.toSet());
        }
        if (action != null && !action.isBlank()) {
            allowed = allowed.contains(action) ? Set.of(action) : Set.of();
        }
        return allowed;
    }

    /**
     * Builds a per-row DTO mapper with actor names/emails pre-fetched in two batch queries for
     * the whole page (not per-row) — target-label resolution stays per-row since it dispatches
     * across several different repositories depending on the action.
     */
    private Function<AuditLog, AuditLogEntryDto> buildRowMapper(List<AuditLog> rows) {
        Set<UUID> actorIds = rows.stream().map(AuditLog::getActorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> actorNames = new HashMap<>();
        Map<UUID, String> actorEmails = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (Object[] row : employeeRepository.findNamesByUserIds(actorIds)) {
                actorNames.put((UUID) row[0], (String) row[1]);
            }
            for (User u : userRepository.findAllById(actorIds)) {
                actorEmails.put(u.getId(), u.getEmail());
            }
        }
        return log -> AuditLogEntryDto.builder()
                .id(log.getId())
                .actorId(log.getActorId())
                .actorName(log.getActorId() != null ? actorNames.get(log.getActorId()) : null)
                .actorEmail(log.getActorId() != null ? actorEmails.get(log.getActorId()) : null)
                .action(log.getAction())
                .actionCategory(AuditActionCategory.of(log.getAction()).name())
                .actionGroup(AuditActionGroup.of(log.getAction()).name())
                .targetId(log.getTargetId())
                .targetLabel(targetResolver.resolve(log.getAction(), log.getTargetId()))
                .targetEmployeeCode(targetResolver.resolveEmployeeCode(log.getAction(), log.getTargetId()))
                .beforeState(log.getBeforeState())
                .afterState(log.getAfterState())
                .occurredAt(log.getOccurredAt())
                .build();
    }
}
