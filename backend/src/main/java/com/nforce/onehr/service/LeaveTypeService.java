package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateLeaveTypeRequest;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.BalanceAdjustmentRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.AccrualCreditResponse;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityPreview;
import com.nforce.onehr.dto.LeaveTypeResponse;
import com.nforce.onehr.dto.UpdateLeaveTypeRequest;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Leave Configuration (Organization Masters > Leave, permission {@code leave_configuration}): the leave
 * type catalogue and manual balance adjustments. Since LV-01 a type is only its name, code, paid/unpaid
 * and basis; quotas, crediting, carry-forward and "applies to" live in leave plans ({@link LeavePlanService}).
 * {@link LeaveService} keeps the read-only public type list and all request logic;
 * {@link LeaveBalanceService} owns how balances are calculated. Every change here is audited.
 */
@Service
@RequiredArgsConstructor
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final com.nforce.onehr.repository.LeavePlanTypeRuleRepository planRuleRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveYearEndService leaveYearEndService;
    private final LeaveAccrualService accrualService;
    private final LeaveEligibilityService eligibilityService;
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
        // Nobody can use it until it is added to a leave plan (LV-01).
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
        if (type.isSystem() && ((req.getClassification() != null && !req.getClassification().equals(type.getClassification()))
                || (req.getEntitlementBasis() != null && !req.getEntitlementBasis().equals(type.getEntitlementBasis())))) {
            throw new IllegalArgumentException("\"" + type.getName() + "\" is a built-in leave type: it can be renamed, "
                    + "but it can't change between paid and unpaid or change its entitlement basis.");
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
            long refs = leaveTypeRepository.countReferences(type.getId(), type.getCode());
            if (refs > 0) {
                throw new IllegalStateException("\"" + type.getName() + "\" can't change between paid and unpaid: "
                        + refs + " record(s) already use it.");
            }
            type.setClassification(req.getClassification());
        }
        if (!type.isAnnualBalance()) {
            // Unpaid and per-occurrence leave never carry forward (also a DB check constraint on leave_types).
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
        if (!active && type.isSystem()) {
            throw new IllegalArgumentException("\"" + type.getName() + "\" is a built-in leave type and can't be deactivated.");
        }
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
        if (type.isSystem()) {
            throw new IllegalArgumentException("\"" + type.getName() + "\" is a built-in leave type and can't be deleted.");
        }
        long refs = leaveTypeRepository.countReferences(type.getId(), type.getCode());
        if (refs > 0) {
            throw new IllegalStateException("Cannot delete \"" + type.getName() + "\": it is used by " + refs
                    + " leave record(s). Deactivate it instead to stop new requests while keeping history.");
        }
        String before = snapshot(type);
        leaveTypeRepository.delete(type); // its plan rules go with it (ON DELETE CASCADE)
        auditService.log(actor.getId(), "LEAVE_TYPE_DELETED", id, before, null);
    }

    /** How many current employees a set of "applies to" rules would cover (the plan editor's live count). */
    @Transactional(readOnly = true)
    public EligibilityPreview previewEligibility(EligibilityConfig config) {
        int[] counts = eligibilityService.preview(config);
        return new EligibilityPreview(counts[0], counts[1]);
    }

    // ── Employee balances ───────────────────────────────────────────────────────────────────────

    /** The monthly credits behind one balance row. */
    @Transactional(readOnly = true)
    public List<AccrualCreditResponse> creditHistory(UUID balanceId) {
        LeaveBalance b = leaveBalanceRepository.findById(balanceId)
                .orElseThrow(() -> new NoSuchElementException("Leave balance not found"));
        return accrualService.history(b.getEmployeeUserId(), b.getLeaveType().getId(), b.getYear());
    }

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
        List<LeaveBalanceResponse> rows = leaveBalanceService.balancesFor(employeeUserId, year != null ? year : currentYear(), true);
        rows.forEach(r -> r.setEmployeeUserId(employeeUserId));
        return rows;
    }

    /**
     * Manual adjustment of one balance row: the days credited this year and the carried-forward days.
     * Credited days may be set below what is used (the employee then owes days, as with leave taken in
     * advance); carried-forward days never below what is used or expired. Later monthly credits add
     * on top of the adjusted figure. Audited with previous value, new value, reason and the administrator.
     */
    @Transactional
    public LeaveBalanceResponse adjustBalance(UUID balanceId, BalanceAdjustmentRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveBalance b = leaveBalanceRepository.findById(balanceId)
                .orElseThrow(() -> new NoSuchElementException("Leave balance not found"));
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

    private LeaveTypeResponse toResponse(LeaveType t, int year) {
        return LeaveTypeResponse.builder()
                .id(t.getId())
                .code(t.getCode())
                .name(t.getName())
                .classification(t.getClassification())
                .entitlementBasis(t.isPaid() ? t.getEntitlementBasis() : "UNLIMITED")
                .active(t.isActive())
                .systemKey(t.getSystemKey())
                .occurrenceWindowMonths(t.getOccurrenceWindowMonths())
                .planNames(t.getId() == null ? List.of() : planRuleRepository.findPlanNamesUsingType(t.getId()))
                .referenceCount(t.getId() == null ? 0L : leaveTypeRepository.countReferences(t.getId(), t.getCode()))
                .build();
    }
}
