package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.CarryForwardSettingsRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.EntitlementChangeRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.EntitlementPolicyResponse;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Leave Configuration (Organization Masters > Leave, permission {@code leave_configuration}):
 * leave types, entitlement policies, carry-forward settings and manual balance adjustments.
 * {@link LeaveService} keeps the read-only public type list and all request logic;
 * {@link LeaveBalanceService} owns how balances are calculated. Every change here is audited.
 */
@Service
@RequiredArgsConstructor
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveEntitlementPolicyRepository policyRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveYearEndService leaveYearEndService;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    // ── Leave types ─────────────────────────────────────────────────────────────────────────────

    /** Every type, inactive included, with its entitlement in force this leave year and its usage count. */
    @Transactional(readOnly = true)
    public List<LeaveTypeResponse> listForAdmin() {
        int year = currentYear();
        return leaveTypeRepository.findAllByOrderByNameAsc().stream().map(t -> toResponse(t, year)).toList();
    }

    @Transactional
    public LeaveTypeResponse create(CreateLeaveTypeRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        String code = req.getCode().trim().toUpperCase();
        if (leaveTypeRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("A leave type with code '" + code + "' already exists");
        }
        boolean paid = LeaveTypeClassification.PAID.equals(req.getClassification());
        String basis = paid && req.getEntitlementBasis() != null ? req.getEntitlementBasis() : LeaveType.BASIS_ANNUAL;
        if (paid && req.getEntitlementDays() == null) {
            throw new IllegalArgumentException("Entitlement days are required for a paid leave type");
        }
        if (LeaveType.BASIS_PER_OCCURRENCE.equals(basis) && req.getOccurrenceWindowMonths() == null) {
            throw new IllegalArgumentException("An occurrence window (months) is required for per-occurrence leave");
        }
        LeaveType type = leaveTypeRepository.save(LeaveType.builder()
                .code(code)
                .name(req.getName().trim())
                .classification(req.getClassification())
                .entitlementBasis(basis)
                .occurrenceWindowMonths(LeaveType.BASIS_PER_OCCURRENCE.equals(basis) ? req.getOccurrenceWindowMonths() : null)
                .build());
        // Existing employees get a balance on first use (LeaveBalanceService#ensureBalance) from this policy.
        if (paid) {
            savePolicy(type.getId(), currentYear(), req.getEntitlementDays(), actor.getId());
        }
        auditService.log(actor.getId(), "LEAVE_TYPE_CREATED", type.getId(), null, snapshot(type));
        return toResponse(type, currentYear());
    }

    @Transactional
    public LeaveTypeResponse update(UUID id, UpdateLeaveTypeRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = requireType(id);
        String before = snapshot(type);

        if (req.getName() != null && !req.getName().isBlank()) {
            type.setName(req.getName().trim());
        }
        if (req.getEntitlementBasis() != null && !req.getEntitlementBasis().equals(type.getEntitlementBasis())) {
            long refs = leaveTypeRepository.countReferences(type.getId(), type.getCode());
            if (refs > 0) {
                throw new IllegalStateException("The entitlement basis of \"" + type.getName() + "\" can't change: "
                        + refs + " record(s) already use it.");
            }
            type.setEntitlementBasis(req.getEntitlementBasis());
        }
        if (req.getOccurrenceWindowMonths() != null) {
            type.setOccurrenceWindowMonths(req.getOccurrenceWindowMonths());
        }
        if (req.getClassification() != null && !req.getClassification().equals(type.getClassification())) {
            type.setClassification(req.getClassification());
            if (type.isPaid() && policyRepository.findByLeaveTypeIdOrderByEffectiveYearDesc(type.getId()).isEmpty()) {
                if (req.getEntitlementDays() == null) {
                    throw new IllegalArgumentException("Entitlement days are required to make this leave type paid");
                }
                savePolicy(type.getId(), currentYear(), req.getEntitlementDays(), actor.getId());
            }
        }
        if (!type.isAnnualBalance()) {
            // Unpaid and per-occurrence leave never carry forward (also a DB check constraint).
            type.setCarryForwardEnabled(false);
        }
        if (type.isPerOccurrence() && type.getOccurrenceWindowMonths() == null) {
            throw new IllegalArgumentException("An occurrence window (months) is required for per-occurrence leave");
        }
        type = leaveTypeRepository.save(type);
        auditService.log(actor.getId(), "LEAVE_TYPE_UPDATED", type.getId(), before, snapshot(type));
        return toResponse(type, currentYear());
    }

    /** Deactivated types disappear from new requests; history keeps showing them unchanged. */
    @Transactional
    public LeaveTypeResponse setActive(UUID id, boolean active, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = requireType(id);
        if (type.isActive() != active) {
            type.setActive(active);
            type = leaveTypeRepository.save(type);
            auditService.log(actor.getId(), active ? "LEAVE_TYPE_REACTIVATED" : "LEAVE_TYPE_DEACTIVATED", type.getId(),
                    auditSnapshot.toJson(Map.of("active", !active)), auditSnapshot.toJson(Map.of("active", active)));
        }
        return toResponse(type, currentYear());
    }

    /**
     * Permanent delete, only while nothing references the type (balances, requests, occurrences,
     * carry-forward history, payroll review items, penalty policies/penalties). Otherwise refused with
     * the count, and the admin deactivates instead — history is never cascade-deleted.
     */
    @Transactional
    public void delete(UUID id, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = requireType(id);
        long refs = leaveTypeRepository.countReferences(type.getId(), type.getCode());
        if (refs > 0) {
            throw new IllegalStateException("Cannot delete \"" + type.getName() + "\": it is used by " + refs
                    + " leave record(s). Deactivate it instead to stop new requests while keeping history.");
        }
        String before = snapshot(type);
        leaveTypeRepository.delete(type); // its own entitlement policies go with it (ON DELETE CASCADE)
        auditService.log(actor.getId(), "LEAVE_TYPE_DELETED", id, before, null);
    }

    // ── Entitlements ────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EntitlementPolicyResponse> listPolicies(UUID typeId) {
        requireType(typeId);
        List<LeaveEntitlementPolicy> policies = policyRepository.findByLeaveTypeIdOrderByEffectiveYearDesc(typeId);
        Map<UUID, String> names = new HashMap<>();
        java.util.Set<UUID> creatorIds = policies.stream().map(LeaveEntitlementPolicy::getCreatedBy)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!creatorIds.isEmpty()) {
            for (Object[] row : employeeRepository.findNamesByUserIds(creatorIds)) {
                names.put((UUID) row[0], (String) row[1]);
            }
        }
        return policies.stream().map(p -> new EntitlementPolicyResponse(p.getId(), p.getEffectiveYear(), p.getDays(),
                p.getCreatedBy() == null ? "System default" : names.getOrDefault(p.getCreatedBy(), "Unknown"), p.getCreatedAt())).toList();
    }

    /**
     * New entitlement from {@code effectiveYear}. Balances that already exist keep their figure unless
     * {@code applyToExistingBalances} — then only the years this policy actually governs (up to the
     * next later policy) are updated, used/carried-forward days untouched. Per-occurrence types: an
     * occurrence keeps the entitlement it opened with; the change applies to occurrences opened later.
     */
    @Transactional
    public List<EntitlementPolicyResponse> changeEntitlement(UUID typeId, EntitlementChangeRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = requireType(typeId);
        if (!type.isPaid()) {
            throw new IllegalArgumentException("Unpaid leave is unlimited, so it has no entitlement to configure.");
        }
        BigDecimal previous = leaveBalanceService.entitlementDays(type, req.effectiveYear()).orElse(null);
        savePolicy(type.getId(), req.effectiveYear(), req.days(), actor.getId());

        int balancesUpdated = 0;
        if (req.applyToExistingBalances() && type.isAnnualBalance()) {
            Integer nextPolicyYear = policyRepository.findByLeaveTypeIdOrderByEffectiveYearDesc(type.getId()).stream()
                    .map(LeaveEntitlementPolicy::getEffectiveYear)
                    .filter(y -> y > req.effectiveYear())
                    .min(Integer::compare).orElse(Integer.MAX_VALUE);
            for (LeaveBalance b : leaveBalanceRepository.findByLeaveTypeIdAndYearGreaterThanEqual(type.getId(), req.effectiveYear())) {
                if (b.getYear() >= nextPolicyYear) continue;
                b.setTotalDays(req.days());
                leaveBalanceRepository.save(b);
                balancesUpdated++;
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("leaveTypeCode", type.getCode());
        after.put("effectiveYear", req.effectiveYear());
        after.put("days", req.days());
        after.put("appliedToExistingBalances", req.applyToExistingBalances());
        after.put("balancesUpdated", balancesUpdated);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("leaveTypeCode", type.getCode());
        before.put("effectiveYear", req.effectiveYear());
        before.put("days", previous);
        auditService.log(actor.getId(), "LEAVE_ENTITLEMENT_CHANGED", type.getId(), auditSnapshot.toJson(before), auditSnapshot.toJson(after));
        return listPolicies(typeId);
    }

    private void savePolicy(UUID typeId, int effectiveYear, BigDecimal days, UUID actorId) {
        LeaveEntitlementPolicy policy = policyRepository.findByLeaveTypeIdAndEffectiveYear(typeId, effectiveYear)
                .orElseGet(() -> LeaveEntitlementPolicy.builder().leaveTypeId(typeId).effectiveYear(effectiveYear).build());
        policy.setDays(days);
        policy.setCreatedBy(actorId);
        policyRepository.save(policy);
    }

    // ── Carry-forward ───────────────────────────────────────────────────────────────────────────

    /** Settings used by the next year-end run. Disabling never touches days already carried forward. */
    @Transactional
    public LeaveTypeResponse updateCarryForward(UUID typeId, CarryForwardSettingsRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveType type = requireType(typeId);
        if (req.enabled() && !type.isAnnualBalance()) {
            throw new IllegalArgumentException("Carry-forward is only available for regular paid leave types "
                    + "(not unpaid or per-occurrence leave such as Maternity).");
        }
        String before = cfSnapshot(type);
        type.setCarryForwardEnabled(req.enabled());
        type.setCarryForwardMaxDays(req.maxDays());
        type.setCarryForwardExpiryMonths(req.expiryMonths());
        type.setCarryForwardPriority(req.priority() != null ? req.priority() : LeaveType.CF_FIRST);
        type = leaveTypeRepository.save(type);
        auditService.log(actor.getId(), "LEAVE_CARRY_FORWARD_CONFIG_CHANGED", type.getId(), before, cfSnapshot(type));
        return toResponse(type, currentYear());
    }

    // ── Employee balances ───────────────────────────────────────────────────────────────────────

    /** [userId, name, code] of every non-deleted employee, for the adjustment screen's picker. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> employeeDirectory() {
        return employeeRepository.findDirectoryRows().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r[0]);
            m.put("name", r[1]);
            m.put("employeeCode", r[2]);
            return m;
        }).toList();
    }

    /** One employee's balances for a leave year (missing regular paid rows are created, as for the employee). */
    @Transactional
    public List<LeaveBalanceResponse> employeeBalances(UUID employeeUserId, Integer year) {
        if (!userRepository.existsById(employeeUserId)) {
            throw new NoSuchElementException("Employee not found");
        }
        List<LeaveBalanceResponse> rows = leaveBalanceService.balancesFor(employeeUserId, year != null ? year : currentYear());
        rows.forEach(r -> r.setEmployeeUserId(employeeUserId));
        return rows;
    }

    /**
     * Manual adjustment of one balance row: the current-year entitlement and the carried-forward days.
     * Never below what is already used (or expired), so the balance can't go negative by an adjustment.
     * Audited with previous value, new value, reason and the administrator.
     */
    @Transactional
    public LeaveBalanceResponse adjustBalance(UUID balanceId, BalanceAdjustmentRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveBalance b = leaveBalanceRepository.findById(balanceId)
                .orElseThrow(() -> new NoSuchElementException("Leave balance not found"));
        if (req.entitlementDays().compareTo(b.getUsedDays()) < 0) {
            throw new IllegalArgumentException("Entitlement can't be less than the " + LeaveBalanceService.formatDays(b.getUsedDays())
                    + " day(s) already used.");
        }
        BigDecimal cfFloor = b.getCarriedForwardUsedDays().add(b.getCarriedForwardExpiredDays());
        if (req.carriedForwardDays().compareTo(cfFloor) < 0) {
            throw new IllegalArgumentException("Carried-forward days can't be less than the " + LeaveBalanceService.formatDays(cfFloor)
                    + " day(s) already used or expired.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("employeeUserId", b.getEmployeeUserId().toString());
        before.put("leaveTypeCode", b.getLeaveType().getCode());
        before.put("year", b.getYear());
        before.put("entitlementDays", b.getTotalDays());
        before.put("carriedForwardDays", b.getCarriedForwardDays());

        b.setTotalDays(req.entitlementDays());
        b.setCarriedForwardDays(req.carriedForwardDays());
        b = leaveBalanceRepository.save(b);

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("entitlementDays", b.getTotalDays());
        after.put("carriedForwardDays", b.getCarriedForwardDays());
        after.put("reason", req.reason().trim());
        auditService.log(actor.getId(), "LEAVE_BALANCE_ADJUSTED", b.getId(), auditSnapshot.toJson(before), auditSnapshot.toJson(after));
        // Raising a closed year's entitlement frees days that may now carry forward.
        LocalDate yearStart = LeaveBalanceService.yearStart(b.getYear());
        leaveYearEndService.refreshTransfers(b.getEmployeeUserId(), b.getLeaveType(), yearStart, yearStart);

        LeaveBalanceResponse r = leaveBalanceService.toResponse(b);
        r.setEmployeeUserId(b.getEmployeeUserId());
        return r;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private int currentYear() {
        return LeaveBalanceService.leaveYearOf(leaveBalanceService.today());
    }

    private LeaveType requireType(UUID id) {
        return leaveTypeRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Leave type not found: " + id));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String snapshot(LeaveType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.getCode());
        m.put("name", t.getName());
        m.put("classification", t.getClassification());
        m.put("entitlementBasis", t.getEntitlementBasis());
        m.put("occurrenceWindowMonths", t.getOccurrenceWindowMonths());
        m.put("active", t.isActive());
        return auditSnapshot.toJson(m);
    }

    private String cfSnapshot(LeaveType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("leaveTypeCode", t.getCode());
        m.put("enabled", t.isCarryForwardEnabled());
        m.put("maxDays", t.getCarryForwardMaxDays());
        m.put("expiryMonths", t.getCarryForwardExpiryMonths());
        m.put("priority", t.getCarryForwardPriority());
        return auditSnapshot.toJson(m);
    }

    private LeaveTypeResponse toResponse(LeaveType t, int year) {
        return LeaveTypeResponse.builder()
                .id(t.getId())
                .code(t.getCode())
                .name(t.getName())
                .classification(t.getClassification())
                .entitlementBasis(t.isPaid() ? t.getEntitlementBasis() : "UNLIMITED")
                .active(t.isActive())
                .occurrenceWindowMonths(t.getOccurrenceWindowMonths())
                .currentEntitlementDays(leaveBalanceService.entitlementDays(t, year).orElse(null))
                .carryForwardEnabled(t.isCarryForwardEnabled())
                .carryForwardMaxDays(t.getCarryForwardMaxDays())
                .carryForwardExpiryMonths(t.getCarryForwardExpiryMonths())
                .carryForwardPriority(t.getCarryForwardPriority())
                .referenceCount(t.getId() == null ? 0L : leaveTypeRepository.countReferences(t.getId(), t.getCode()))
                .build();
    }
}
