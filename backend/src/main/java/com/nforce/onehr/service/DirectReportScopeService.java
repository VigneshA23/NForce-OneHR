package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single authoritative answer to "who is this caller actually allowed to see as their team",
 * for callers that must never be trusted to answer that themselves - most notably the AI
 * assistant's My Team capability (ONEHR - NORA My Team access control).
 *
 * <p>Every existing "direct reports" read in this codebase already resolves the same way, from
 * {@link EmployeeManagerHistoryRepository#findCurrentDirectReportIds} - the single most-shared
 * query behind My Team across attendance, leave, assets, expenses, penalties, exceptions, reports
 * and kudos (see that method's own Javadoc). This class adds no new relationship and no new
 * business rule; it exists so that <strong>a new caller with a security requirement this strict
 * has one obvious, reviewable place to call</strong>, rather than one more hand-written copy of
 * the same query. It intentionally does not touch or refactor those existing call sites - several
 * of them (penalties, HR service-request reports, the Approval Center, regularization approval)
 * deliberately widen to the whole organisation for HR_ADMIN/SUPER_ADMIN, which is correct for
 * those screens and would be a real regression to fold into a single "resolve my team" helper.
 * What this class guarantees is narrower and absolute: <strong>callers of this class never see
 * that widening</strong> - not for any role, including Super Admin. See {@code MyTeamDataProviders}.
 *
 * <p>Never call any of the widening service methods above and filter their result down to this
 * set afterwards - the unauthorised rows would already have reached application memory (and, for
 * an AI caller, risk reaching a prompt) before the filter ran. Resolve the id set here first, and
 * pass it into a query that is scoped by it from the start.
 */
@Service
@RequiredArgsConstructor
public class DirectReportScopeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;

    /**
     * The authenticated caller's own employee record. Never built from anything the caller
     * supplied - {@code actorEmail} must come from the authenticated principal, exactly as every
     * other actor-scoped service in this codebase requires.
     */
    @Transactional(readOnly = true)
    public Employee resolveActor(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }

    /**
     * The caller's current direct reports' user ids - never the organisation, never anyone else's
     * team, whatever role the caller holds. This is the one set every My Team AI read must be
     * scoped by before any data reaches the model.
     */
    @Transactional(readOnly = true)
    public Set<UUID> directReportIds(String actorEmail) {
        return directReportIds(resolveActor(actorEmail));
    }

    /** As {@link #directReportIds(String)}, when the actor has already been resolved once this turn. */
    @Transactional(readOnly = true)
    public Set<UUID> directReportIds(Employee actor) {
        return Set.copyOf(managerHistoryRepository.findCurrentDirectReportIds(actor.getUserId()));
    }

    /**
     * The caller's current direct reports, as full employee records (name, code, designation) -
     * for a provider that needs to join another table's rows (keyed only by user id) back to a
     * name without a second, unscoped directory lookup.
     */
    @Transactional(readOnly = true)
    public List<Employee> directReports(String actorEmail) {
        Set<UUID> ids = directReportIds(actorEmail);
        return ids.isEmpty() ? List.of() : employeeRepository.findAllById(ids);
    }

    /**
     * True only when {@code employeeUserId} is genuinely, currently a direct report of
     * {@code actorEmail}. The only question this class exists to answer with total confidence -
     * every My Team AI answer about a named individual must pass through this before that
     * person's name or figures are allowed anywhere near a prompt.
     */
    @Transactional(readOnly = true)
    public boolean isDirectReport(String actorEmail, UUID employeeUserId) {
        return employeeUserId != null && directReportIds(actorEmail).contains(employeeUserId);
    }
}
