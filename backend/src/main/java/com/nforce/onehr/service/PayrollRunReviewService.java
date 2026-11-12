package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * US-C22/US-C23 (ONEHR-579) — the two "run step" reviews gating a DRAFT run's submission: "Leave
 * Applied" (bulk approve/reject of unpaid-leave requests and penalization-policy deductions in the
 * cycle) and "No Attendance" (per-date resolution of employee-days with no attendance and no leave
 * record). See {@link PayrollRunReviewItem}'s own Javadoc for the full item-shape and decision
 * semantics, including the deliberate asymmetry between a rejected PENALTY item (durable — calls
 * {@link AttendancePenaltyService#cancelBulk}) and a rejected LEAVE_UNPAID item (review-item-only,
 * reset on the next regenerate).
 *
 * <p>{@link #generateItemsForEmployee} is called by {@code PayrollRunService#buildPayslip}, once
 * per employee, right after that employee's {@link
 * PayrollLopCalculationService#recomputeWithDetails} — reusing its already-fetched unpaid-leave
 * requests, PENDING_REVIEW penalties and {@code needsManualReviewDates} rather than re-querying
 * any of them a second time (a real, measured cost on this app's Neon pooled endpoint, not just a
 * style preference — see that method's own Javadoc). It deletes this employee's OLD items itself,
 * but only AFTER {@code recomputeWithDetails} has already read them — never before: a regenerate
 * resets review decisions, same as it resets payslips, but only once they've had their one chance
 * to affect this regenerate's numbers (a rejected leave request excluded from the sum, a
 * NO_ATTENDANCE item marked LOP added to it). Deleting up front, the way payslips themselves are
 * wiped, would destroy a decision before it could ever take effect — a real bug this design
 * deliberately avoids, not a hypothetical one. An employee excluded from the regenerate entirely
 * (a new "Hold processing" hold) never reaches {@link #generateItemsForEmployee} at all, so {@code
 * PayrollRunService#generateDraft} calls {@link #deleteItemsForEmployee} for them directly, or
 * their stale items would linger forever.
 */
@Service
@RequiredArgsConstructor
public class PayrollRunReviewService {

    private final PayrollRunReviewItemRepository payrollRunReviewItemRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final AttendancePenaltyService attendancePenaltyService;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public void generateItemsForEmployee(PayrollRun run, UUID employeeId, PayCycleWindow window,
            PayrollLopCalculationResponse lop, List<LeaveRequest> unpaidLeaveRequests, List<AttendancePenalty> pendingPenalties) {
        // Deleted here — AFTER the caller's PayrollLopCalculationService#recomputeWithDetails has
        // already read this employee's OLD items (a rejected leave request, a MARK_LOP decision)
        // to compute THIS regenerate's numbers — never before. Deleting up front, the way payslips
        // themselves are wiped, would destroy a decision before it ever got a chance to take
        // effect; this ordering is what actually makes "decide, then regenerate to bake it in"
        // work, rather than silently discarding every decision on every regenerate.
        payrollRunReviewItemRepository.deleteByPayrollRunIdAndEmployeeId(run.getId(), employeeId);

        List<PayrollRunReviewItem> items = new ArrayList<>();

        for (LeaveRequest request : unpaidLeaveRequests) {
            items.add(PayrollRunReviewItem.builder()
                    .payrollRun(run)
                    .employeeId(employeeId)
                    .stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                    .sourceType(PayrollReviewItemSourceType.LEAVE_UNPAID)
                    .sourceId(request.getId())
                    .itemDate(request.getStartDate())
                    .days(request.getTotalDays())
                    .leaveTypeName(request.getLeaveType().getName())
                    .decision(LeaveReviewDecision.PENDING)
                    .build());
        }

        for (AttendancePenalty penalty : pendingPenalties) {
            items.add(PayrollRunReviewItem.builder()
                    .payrollRun(run)
                    .employeeId(employeeId)
                    .stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                    .sourceType(PayrollReviewItemSourceType.PENALTY)
                    .sourceId(penalty.getId())
                    .itemDate(penalty.getIncidentDate())
                    .days(penalty.getLopDays() != null ? penalty.getLopDays() : BigDecimal.ZERO)
                    .decision(LeaveReviewDecision.PENDING)
                    .build());
        }

        List<LocalDate> needsManualReviewDates = lop.getNeedsManualReviewDates();
        for (LocalDate date : needsManualReviewDates != null ? needsManualReviewDates : List.<LocalDate>of()) {
            items.add(PayrollRunReviewItem.builder()
                    .payrollRun(run)
                    .employeeId(employeeId)
                    .stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                    .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE)
                    .itemDate(date)
                    .days(BigDecimal.ONE)
                    .decision(NoAttendanceReviewDecision.PENDING)
                    .build());
        }

        payrollRunReviewItemRepository.saveAll(items);
    }

    /** Used for an employee EXCLUDED from a regenerate (e.g. a new "Hold processing" hold) — they
     * never go through {@link #generateItemsForEmployee}, so their items from a prior regenerate
     * would otherwise linger as stale PENDING rows blocking {@code PayrollRunService#submit}
     * forever. A harmless no-op when there's nothing to delete. */
    public void deleteItemsForEmployee(UUID runId, UUID employeeId) {
        payrollRunReviewItemRepository.deleteByPayrollRunIdAndEmployeeId(runId, employeeId);
    }

    public boolean isStepComplete(UUID runId, String stepType) {
        return !payrollRunReviewItemRepository.existsByPayrollRunIdAndStepTypeAndDecision(
                runId, stepType, PENDING_FOR(stepType));
    }

    // Both steps' "not yet decided" value happens to be the literal string "PENDING" — see
    // LeaveReviewDecision/NoAttendanceReviewDecision — but this reads the right constant for
    // either step rather than relying on that coincidence.
    private static String PENDING_FOR(String stepType) {
        return PayrollRunReviewStepType.NO_ATTENDANCE.equals(stepType)
                ? NoAttendanceReviewDecision.PENDING
                : LeaveReviewDecision.PENDING;
    }

    @Transactional(readOnly = true)
    public List<PayrollRunReviewItemResponse> listLeaveApplied(UUID runId) {
        requireRun(runId);
        return toResponses(payrollRunReviewItemRepository.findByPayrollRunIdAndStepType(runId, PayrollRunReviewStepType.LEAVE_APPLIED));
    }

    @Transactional(readOnly = true)
    public List<PayrollRunReviewItemResponse> listNoAttendance(UUID runId) {
        requireRun(runId);
        return toResponses(payrollRunReviewItemRepository.findByPayrollRunIdAndStepType(runId, PayrollRunReviewStepType.NO_ATTENDANCE));
    }

    @Transactional
    public BulkDecideLeaveReviewResponse bulkDecideLeaveApplied(UUID runId, BulkDecideLeaveReviewRequest req, String actorEmail) {
        requireRun(runId);
        List<PayrollRunReviewItem> items = payrollRunReviewItemRepository.findByIdIn(req.getItemIds()).stream()
                .filter(i -> i.getPayrollRun().getId().equals(runId))
                .collect(Collectors.toList());
        if (items.size() != req.getItemIds().size()) {
            throw new NoSuchElementException("One or more review items not found on this run");
        }

        User actor = requireActor(actorEmail);
        List<UUID> succeeded = new ArrayList<>();
        List<BulkDecideLeaveReviewResponse.Failure> failed = new ArrayList<>();

        if (LeaveReviewDecision.APPROVED.equals(req.getDecision())) {
            // Pure acknowledgement — the entry already counts toward LOP today; nothing else changes.
            for (PayrollRunReviewItem item : items) {
                decide(item, LeaveReviewDecision.APPROVED, actor.getId());
                succeeded.add(item.getId());
            }
        } else {
            List<PayrollRunReviewItem> penaltyItems = items.stream()
                    .filter(i -> PayrollReviewItemSourceType.PENALTY.equals(i.getSourceType())).collect(Collectors.toList());
            List<PayrollRunReviewItem> leaveItems = items.stream()
                    .filter(i -> PayrollReviewItemSourceType.LEAVE_UNPAID.equals(i.getSourceType())).collect(Collectors.toList());

            for (PayrollRunReviewItem item : leaveItems) {
                decide(item, LeaveReviewDecision.REJECTED, actor.getId());
                succeeded.add(item.getId());
            }

            if (!penaltyItems.isEmpty()) {
                Map<UUID, PayrollRunReviewItem> bySourceId = penaltyItems.stream()
                        .collect(Collectors.toMap(PayrollRunReviewItem::getSourceId, i -> i));
                PenaltyCancelResultResponse result = attendancePenaltyService.cancelBulk(
                        actorEmail, new ArrayList<>(bySourceId.keySet()), req.getReason());
                for (UUID penaltyId : result.getSucceededIds()) {
                    PayrollRunReviewItem item = bySourceId.get(penaltyId);
                    decide(item, LeaveReviewDecision.REJECTED, actor.getId());
                    succeeded.add(item.getId());
                }
                for (PenaltyCancelResultResponse.BulkFailureDto failure : result.getFailed()) {
                    PayrollRunReviewItem item = bySourceId.get(failure.getId());
                    failed.add(BulkDecideLeaveReviewResponse.Failure.builder()
                            .itemId(item.getId()).reason(failure.getReason()).build());
                }
            }
        }

        payrollRunReviewItemRepository.saveAll(items);
        auditService.log(actor.getId(), "PAYROLL_RUN_REVIEW_LEAVE_DECIDED", runId, null,
                auditSnapshot.toJson(Map.of("decision", req.getDecision(), "itemIds", succeeded)));

        return BulkDecideLeaveReviewResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    @Transactional
    public PayrollRunReviewItemResponse decideNoAttendance(UUID runId, UUID itemId, DecideNoAttendanceReviewRequest req, String actorEmail) {
        requireRun(runId);
        PayrollRunReviewItem item = payrollRunReviewItemRepository.findById(itemId)
                .orElseThrow(() -> new NoSuchElementException("Review item not found: " + itemId));
        if (!item.getPayrollRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Review item " + itemId + " does not belong to run " + runId);
        }
        if (!PayrollRunReviewStepType.NO_ATTENDANCE.equals(item.getStepType())) {
            throw new IllegalArgumentException("Review item " + itemId + " is not a NO_ATTENDANCE item");
        }
        if (NoAttendanceReviewDecision.DEDUCT_FROM_LEAVE.equals(req.getAction()) && req.getSelectedLeaveTypeId() == null) {
            throw new IllegalArgumentException("selectedLeaveTypeId is required for DEDUCT_FROM_LEAVE");
        }
        if (NoAttendanceReviewDecision.NO_ACTION.equals(req.getAction()) && (req.getComment() == null || req.getComment().isBlank())) {
            throw new IllegalArgumentException("comment is required for NO_ACTION");
        }

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(itemSnapshot(item));
        item.setDecision(req.getAction());
        item.setSelectedLeaveTypeId(req.getSelectedLeaveTypeId());
        item.setComment(req.getComment());
        item.setDecidedBy(actor.getId());
        item.setDecidedAt(LocalDateTime.now());
        item = payrollRunReviewItemRepository.save(item);

        auditService.log(actor.getId(), "PAYROLL_RUN_REVIEW_NO_ATTENDANCE_DECIDED", item.getId(), before,
                auditSnapshot.toJson(itemSnapshot(item)));
        return toResponse(item);
    }

    /** Bulk version of {@link #decideNoAttendance} — same action applied to every listed item.
     *  All-or-nothing: unlike bulkDecideLeaveApplied, there's no external per-item dependency that
     *  can fail independently, so a missing/foreign/wrong-step-type item fails the whole call
     *  before anything is saved, same upfront-validation shape as the single-item decide.
     *
     *  <p>The actual write is one {@code UPDATE ... WHERE id IN (...)} (see {@link
     *  PayrollRunReviewItemRepository#bulkDecide}) plus one summary audit row for the whole batch
     *  — never a per-item {@code save}/{@code auditService.log} loop. Two bugs compound there: (1)
     *  {@code auditService.log} is {@code REQUIRES_NEW}, so a per-item call opens a brand-new
     *  transaction (a fresh round trip to Neon) for every item while this method's own transaction
     *  stays open throughout; (2) even with that fixed, a plain entity-by-entity {@code save()}
     *  loop is still one UPDATE statement, and therefore one network round trip to Neon, per item.
     *  At real-world batch sizes (seen: 169 items for one employee's absence run) either one alone
     *  reliably blew past the 30s transaction timeout. A single bulk UPDATE is one round trip
     *  regardless of how many ids it covers. */
    @Transactional
    public List<PayrollRunReviewItemResponse> bulkDecideNoAttendance(UUID runId, BulkDecideNoAttendanceRequest req, String actorEmail) {
        requireRun(runId);
        if (NoAttendanceReviewDecision.DEDUCT_FROM_LEAVE.equals(req.getAction()) && req.getSelectedLeaveTypeId() == null) {
            throw new IllegalArgumentException("selectedLeaveTypeId is required for DEDUCT_FROM_LEAVE");
        }
        if (NoAttendanceReviewDecision.NO_ACTION.equals(req.getAction()) && (req.getComment() == null || req.getComment().isBlank())) {
            throw new IllegalArgumentException("comment is required for NO_ACTION");
        }

        List<PayrollRunReviewItem> items = payrollRunReviewItemRepository.findByIdIn(req.getItemIds());
        if (items.size() != req.getItemIds().size()
                || items.stream().anyMatch(i -> !i.getPayrollRun().getId().equals(runId))) {
            throw new NoSuchElementException("One or more review items not found on this run");
        }
        if (items.stream().anyMatch(i -> !PayrollRunReviewStepType.NO_ATTENDANCE.equals(i.getStepType()))) {
            throw new IllegalArgumentException("One or more items are not NO_ATTENDANCE items");
        }

        User actor = requireActor(actorEmail);
        LocalDateTime decidedAt = LocalDateTime.now();
        payrollRunReviewItemRepository.bulkDecide(req.getItemIds(), req.getAction(), req.getSelectedLeaveTypeId(),
                req.getComment(), actor.getId(), decidedAt);

        auditService.log(actor.getId(), "PAYROLL_RUN_REVIEW_NO_ATTENDANCE_DECIDED", runId, null,
                auditSnapshot.toJson(Map.of("action", req.getAction(), "itemIds", req.getItemIds())));

        return toResponses(payrollRunReviewItemRepository.findByIdIn(req.getItemIds()));
    }

    private void decide(PayrollRunReviewItem item, String decision, UUID actorId) {
        item.setDecision(decision);
        item.setDecidedBy(actorId);
        item.setDecidedAt(LocalDateTime.now());
    }

    private PayrollRun requireRun(UUID runId) {
        return payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found: " + runId));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> itemSnapshot(PayrollRunReviewItem i) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employeeId", i.getEmployeeId());
        snapshot.put("itemDate", i.getItemDate());
        snapshot.put("decision", i.getDecision());
        return snapshot;
    }

    /** Single-item callers only — see {@link #toResponses} for the batch-resolving equivalent
     *  every list/bulk endpoint uses instead (one {@code findAllById} per distinct id across the
     *  whole batch, not one {@code findById} per item — the same per-item-round-trip mistake
     *  {@link #bulkDecideNoAttendance} itself was just fixed for, just one call deeper). */
    private String displayName(UUID userId) {
        if (userId == null) return null;
        return employeeRepository.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

    /** Batch display-name resolution for a list of items: one {@code findAllById} across every
     *  distinct employeeId/decidedBy in the whole list (falling back to {@code userRepository} only
     *  for whichever ids aren't employees), instead of {@link #displayName} being called per item
     *  per field — the latter is a real N+1 (seen: ~340 individual lookups for one 169-item bulk
     *  decide, the dominant cost once the write itself stopped round-tripping per row). */
    private List<PayrollRunReviewItemResponse> toResponses(List<PayrollRunReviewItem> items) {
        Set<UUID> ids = new HashSet<>();
        for (PayrollRunReviewItem i : items) {
            if (i.getEmployeeId() != null) ids.add(i.getEmployeeId());
            if (i.getDecidedBy() != null) ids.add(i.getDecidedBy());
        }
        Map<UUID, String> namesById = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Employee e : employeeRepository.findAllById(ids)) {
                namesById.put(e.getUserId(), e.getFullName());
            }
            Set<UUID> remaining = new HashSet<>(ids);
            remaining.removeAll(namesById.keySet());
            if (!remaining.isEmpty()) {
                for (User u : userRepository.findAllById(remaining)) {
                    namesById.put(u.getId(), u.getEmail());
                }
            }
        }
        return items.stream().map(i -> toResponse(i, namesById)).collect(Collectors.toList());
    }

    private PayrollRunReviewItemResponse toResponse(PayrollRunReviewItem i) {
        return toResponse(i, null);
    }

    private PayrollRunReviewItemResponse toResponse(PayrollRunReviewItem i, Map<UUID, String> namesById) {
        return PayrollRunReviewItemResponse.builder()
                .id(i.getId())
                .payrollRunId(i.getPayrollRun().getId())
                .employeeId(i.getEmployeeId())
                .employeeName(namesById != null ? resolveName(namesById, i.getEmployeeId()) : displayName(i.getEmployeeId()))
                .stepType(i.getStepType())
                .sourceType(i.getSourceType())
                .sourceId(i.getSourceId())
                .itemDate(i.getItemDate())
                .days(i.getDays())
                .leaveTypeName(i.getLeaveTypeName())
                .decision(i.getDecision())
                .selectedLeaveTypeId(i.getSelectedLeaveTypeId())
                .comment(i.getComment())
                .decidedByName(namesById != null ? resolveName(namesById, i.getDecidedBy()) : displayName(i.getDecidedBy()))
                .decidedAt(i.getDecidedAt())
                .build();
    }

    private String resolveName(Map<UUID, String> namesById, UUID id) {
        if (id == null) return null;
        return namesById.getOrDefault(id, "Unknown");
    }
}
