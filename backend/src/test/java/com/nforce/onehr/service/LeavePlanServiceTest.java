package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeavePlanDtos;
import com.nforce.onehr.dto.LeavePlanDtos.CreatePlanRequest;
import org.mockito.ArgumentCaptor;
import com.nforce.onehr.dto.LeavePlanDtos.SaveVersionRequest;
import com.nforce.onehr.dto.LeavePlanDtos.TypeRuleRequest;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanTypeRule;
import com.nforce.onehr.entity.LeavePlanVersion;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeavePlanAllocationRepository;
import com.nforce.onehr.repository.LeavePlanRepository;
import com.nforce.onehr.repository.LeavePlanTypeEligibilityRepository;
import com.nforce.onehr.repository.LeavePlanTypeOverrideRepository;
import com.nforce.onehr.repository.LeavePlanTypeRuleRepository;
import com.nforce.onehr.repository.LeavePlanVersionRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** LV-01: plan versions on a timeline, unchanged saves, closed years, and the plan safeguards. */
@ExtendWith(MockitoExtension.class)
class LeavePlanServiceTest {

    @Mock private LeavePlanRepository planRepo;
    @Mock private LeavePlanVersionRepository versionRepo;
    @Mock private LeavePlanTypeRuleRepository ruleRepo;
    @Mock private com.nforce.onehr.repository.LeavePlanTypeClubbingRepository clubbingRepo;
    @Mock private LeavePlanTypeEligibilityRepository eligibilityRepo;
    @Mock private LeavePlanTypeOverrideRepository overrideRepo;
    @Mock private LeavePlanAllocationRepository allocationRepo;
    @Mock private AssignmentRuleRepository assignmentRuleRepo;
    @Mock private AssignmentRuleAssignmentRepository ruleAssignmentRepo;
    @Mock private LeaveTypeRepository typeRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private UserRepository userRepo;
    @Mock private LeavePlanRuleService planRuleService;
    @Mock private LeaveAccrualService accrual;
    @Mock private AuditService audit;

    private LeavePlanService service;
    /** The real "today" decides which years are closed, so dates are relative to it. */
    private final int year = LocalDate.now(ZoneId.of(new AttendanceProperties().getZone())).getYear();
    private final User hr = User.builder().id(UUID.randomUUID()).email("hr@test.com").build();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave").build();
    private final LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).code("UNPAID").name("Unpaid Leave").classification("UNPAID").build();
    private final LeavePlan plan = LeavePlan.builder().id(UUID.randomUUID()).name("Full-time India").orgDefault(true).build();
    private final List<LeavePlanVersion> versions = new ArrayList<>();
    private final List<LeavePlanTypeRule> rules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new LeavePlanService(planRepo, versionRepo, ruleRepo, clubbingRepo, eligibilityRepo, overrideRepo, allocationRepo, assignmentRuleRepo,
                ruleAssignmentRepo, typeRepo, employeeRepo, userRepo, planRuleService, accrual, audit,
                new AuditSnapshotSerializer(new ObjectMapper()), new AttendanceProperties());
        lenient().when(userRepo.findByEmail("hr@test.com")).thenReturn(Optional.of(hr));
        lenient().when(planRepo.findById(plan.getId())).thenReturn(Optional.of(plan));
        lenient().when(planRepo.findAllByOrderByNameAsc()).thenReturn(List.of(plan));
        lenient().when(typeRepo.findById(any())).thenAnswer(inv -> List.of(paid, unpaid).stream()
                .filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(planRuleService.catalogForAll()).thenReturn(new LeavePlanCatalog(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null));
        lenient().when(versionRepo.findByPlanIdOrderByEffectiveFromAsc(plan.getId())).thenAnswer(inv -> versions.stream()
                .sorted(Comparator.comparing(LeavePlanVersion::getEffectiveFrom)).toList());
        lenient().when(versionRepo.save(any())).thenAnswer(inv -> store(inv.getArgument(0)));
        lenient().when(versionRepo.saveAndFlush(any())).thenAnswer(inv -> store(inv.getArgument(0)));
        lenient().when(ruleRepo.save(any())).thenAnswer(inv -> {
            LeavePlanTypeRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            rules.add(r);
            return r;
        });
        lenient().when(ruleRepo.findByVersionId(any())).thenAnswer(inv -> rules.stream()
                .filter(r -> r.getVersionId().equals(inv.getArgument(0))).toList());
        lenient().doAnswer(inv -> {
            List<LeavePlanTypeRule> gone = new ArrayList<>(inv.<Iterable<LeavePlanTypeRule>>getArgument(0) instanceof List<?> l
                    ? (List<LeavePlanTypeRule>) l : List.of());
            rules.removeAll(gone);
            return null;
        }).when(ruleRepo).deleteAll(any());
    }

    private LeavePlanVersion store(LeavePlanVersion v) {
        if (v.getId() == null) v.setId(UUID.randomUUID());
        if (!versions.contains(v)) versions.add(v);
        return v;
    }

    private LeavePlanVersion version(int n, LocalDate from, LocalDate to, String paidDays) {
        LeavePlanVersion v = store(LeavePlanVersion.builder().planId(plan.getId()).version(n).effectiveFrom(from).effectiveTo(to).build());
        rules.add(LeavePlanTypeRule.builder().id(UUID.randomUUID()).versionId(v.getId()).leaveTypeId(paid.getId())
                .days(new BigDecimal(paidDays)).build());
        return v;
    }

    private List<TypeRuleRequest> paidDays(String days) {
        return List.of(new TypeRuleRequest(paid.getId(), new BigDecimal(days), "YEARLY", false, null, null, null, null));
    }

    private BigDecimal paidDaysIn(LeavePlanVersion v) {
        return rules.stream().filter(r -> r.getVersionId().equals(v.getId())).findFirst().orElseThrow().getDays();
    }

    private LocalDate jan1() {
        return LocalDate.of(year, 1, 1);
    }

    @Test
    void savingFromADate_endsTheVersionItStartsIn_andOpensANewOne() {
        LeavePlanVersion v1 = version(1, jan1(), null, "15");
        service.saveVersion(plan.getId(), new SaveVersionRequest(LocalDate.of(year, 7, 1), paidDays("18"), false), "hr@test.com");

        assertEquals(LocalDate.of(year, 6, 30), v1.getEffectiveTo());
        LeavePlanVersion v2 = versions.stream().filter(v -> v.getVersion() == 2).findFirst().orElseThrow();
        assertEquals(LocalDate.of(year, 7, 1), v2.getEffectiveFrom());
        assertNull(v2.getEffectiveTo());
        assertEquals(0, new BigDecimal("18").compareTo(paidDaysIn(v2)));
        assertEquals(0, new BigDecimal("15").compareTo(paidDaysIn(v1))); // history untouched
        verify(audit).log(eq(hr.getId()), eq("LEAVE_PLAN_VERSION_SAVED"), eq(plan.getId()), any(), any());
        verify(accrual, never()).reconcile(any(), any(), anyBoolean()); // credited months keep their days unless asked
    }

    @Test
    void savingOnTheDayAVersionStarts_replacesThatVersionsRules() {
        LeavePlanVersion v1 = version(1, jan1(), null, "15");
        service.saveVersion(plan.getId(), new SaveVersionRequest(jan1(), paidDays("20"), true), "hr@test.com");
        assertEquals(1, versions.size());
        assertEquals(0, new BigDecimal("20").compareTo(paidDaysIn(v1)));
        verify(accrual).reconcile(null, jan1(), true); // "re-credit" from 1 Jan also resets migrated balances
    }

    @Test
    void aVersionInsertedBeforeAScheduledOne_endsWhereTheScheduledOneBegins() {
        version(1, jan1(), LocalDate.of(year, 12, 31), "15");
        LeavePlanVersion next = version(2, LocalDate.of(year + 1, 1, 1), null, "15");
        service.saveVersion(plan.getId(), new SaveVersionRequest(LocalDate.of(year, 7, 1), paidDays("18"), false), "hr@test.com");
        LeavePlanVersion inserted = versions.stream().filter(v -> v.getVersion() == 3).findFirst().orElseThrow();
        assertEquals(LocalDate.of(year, 12, 31), inserted.getEffectiveTo());
        assertNull(next.getEffectiveTo());
        assertEquals(0, new BigDecimal("15").compareTo(paidDaysIn(next)));
    }

    @Test
    void unchangedRules_createNothing() {
        version(1, jan1(), null, "15");
        service.saveVersion(plan.getId(), new SaveVersionRequest(LocalDate.of(year, 7, 1), paidDays("15.00"), true), "hr@test.com");
        assertEquals(1, versions.size());
        verify(audit, never()).log(any(), eq("LEAVE_PLAN_VERSION_SAVED"), any(), any(), any());
        verify(accrual, never()).reconcile(any(), any(), anyBoolean());
    }

    @Test
    void closedLeaveYears_cantBeChanged() {
        version(1, jan1(), null, "15");
        assertThrows(IllegalArgumentException.class, () -> service.saveVersion(plan.getId(),
                new SaveVersionRequest(LocalDate.of(year - 1, 12, 1), paidDays("18"), false), "hr@test.com"));
    }

    @Test
    void rules_needDaysForPaidTypes_noCarryForwardForUnpaid_andEachTypeOnce() {
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(
                List.of(new TypeRuleRequest(paid.getId(), null, "YEARLY", false, null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(
                List.of(new TypeRuleRequest(unpaid.getId(), null, null, true, null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(List.of(
                new TypeRuleRequest(paid.getId(), BigDecimal.TEN, null, false, null, null, null, null),
                new TypeRuleRequest(paid.getId(), BigDecimal.ONE, null, false, null, null, null, null))));
        assertEquals(2, service.validateRules(List.of(
                new TypeRuleRequest(paid.getId(), BigDecimal.TEN, null, false, null, null, null, null),
                new TypeRuleRequest(unpaid.getId(), null, null, false, null, null, null, null))).size());
    }

    private static LeavePlanDtos.RuleLimits limits(BigDecimal minDays, BigDecimal maxDays, String mode, List<UUID> notNextTo,
                                                   BigDecimal minHours, boolean hourly, String types) {
        return new LeavePlanDtos.RuleLimits(mode, minDays, maxDays, null, null, null, null, null, true, hourly, minHours,
                null, types, null, notNextTo);
    }

    @Test
    void limits_mustMakeSenseTogether() {
        java.util.function.Function<LeavePlanDtos.RuleLimits, List<TypeRuleRequest>> paidWith = l -> List.of(
                new TypeRuleRequest(paid.getId(), BigDecimal.TEN, null, false, null, null, null, null, l));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validateRules(paidWith.apply(
                limits(new BigDecimal("3"), new BigDecimal("2"), null, null, null, false, null)))).getMessage().contains("minimum days"));
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(paidWith.apply(
                limits(null, null, null, List.of(paid.getId()), null, false, null)))); // blocks itself
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(paidWith.apply(
                limits(null, null, null, null, new BigDecimal("2"), false, null)))); // min hours without hourly
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(paidWith.apply(
                limits(null, null, null, null, null, false, "pdf, ../exe")))); // not an extension
        assertThrows(IllegalArgumentException.class, () -> service.validateRules(List.of(
                new TypeRuleRequest(unpaid.getId(), null, null, false, null, null, null, null,
                        limits(null, null, "EARNED_ONLY", null, null, false, null)))));
        // Earned-only paid leave needs no days.
        assertEquals(1, service.validateRules(List.of(new TypeRuleRequest(paid.getId(), null, null, false, null, null, null, null,
                limits(null, null, "EARNED_ONLY", null, null, false, null)))).size());
    }

    @Test
    void compOff_isAlwaysEarnedOnly_whateverTheEditorSends() {
        LeaveType compOff = LeaveType.builder().id(UUID.randomUUID()).code("COMP_OFF").name("Comp-Off").build();
        assertEquals("EARNED_ONLY", LeavePlanService.modeOf(compOff, LeavePlanDtos.RuleLimits.DEFAULTS));
        assertEquals("QUOTA", LeavePlanService.modeOf(paid, LeavePlanDtos.RuleLimits.DEFAULTS));
        when(typeRepo.findById(compOff.getId())).thenReturn(Optional.of(compOff));
        // A seeded Comp-Off rule (EARNED_ONLY, days 0) re-saves without days.
        assertEquals(1, service.validateRules(List.of(new TypeRuleRequest(compOff.getId(), null, null, false, null, null, null, null,
                limits(null, null, "EARNED_ONLY", null, null, false, null)))).size());
    }

    @Test
    void savingLimits_writesThemAndTheClubbing_andAChangedLimitIsANewVersion() {
        version(1, jan1(), null, "15");
        var l = limits(null, new BigDecimal("3"), null, List.of(unpaid.getId()), null, false, " PDF,.jpg ");
        service.saveVersion(plan.getId(), new SaveVersionRequest(LocalDate.of(year, 7, 1),
                List.of(new TypeRuleRequest(paid.getId(), new BigDecimal("15"), "YEARLY", false, null, null, null, null, l)), false),
                "hr@test.com");

        LeavePlanVersion v2 = versions.stream().filter(v -> v.getVersion() == 2).findFirst().orElseThrow();
        LeavePlanTypeRule saved = rules.stream().filter(r -> r.getVersionId().equals(v2.getId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3").compareTo(saved.getMaxDaysPerRequest()));
        assertEquals("pdf,jpg", saved.getAttachmentAllowedTypes());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.nforce.onehr.entity.LeavePlanTypeClubbing>> clubbing = ArgumentCaptor.forClass(List.class);
        verify(clubbingRepo, atLeastOnce()).saveAll(clubbing.capture());
        assertEquals(unpaid.getId(), clubbing.getAllValues().stream().flatMap(List::stream).findFirst().orElseThrow().getBlockedLeaveTypeId());
    }

    @Test
    void changingTheProration_recreditsThisYear_andAnUnchangedOneDoesNot() {
        service.update(plan.getId(), new LeavePlanDtos.UpdatePlanRequest(plan.getName(), null, 1, null), "hr@test.com");
        verify(accrual, never()).reconcile(any(), any(), anyBoolean());

        service.update(plan.getId(), new LeavePlanDtos.UpdatePlanRequest(plan.getName(), null, 1,
                new LeavePlanDtos.Proration("MONTHS", 15, "DOWN_HALF")), "hr@test.com");
        assertEquals(15, plan.getProrationCutoffDay());
        assertEquals("DOWN_HALF", plan.getProrationRounding());
        verify(accrual).reconcile(null, jan1(), false);
        verify(audit).log(eq(hr.getId()), eq("LEAVE_PLAN_UPDATED"), eq(plan.getId()), any(), any());

        // A cut-off day means nothing for calendar days.
        service.update(plan.getId(), new LeavePlanDtos.UpdatePlanRequest(plan.getName(), null, 1,
                new LeavePlanDtos.Proration("CALENDAR_DAYS", 15, null)), "hr@test.com");
        assertNull(plan.getProrationCutoffDay());
        assertEquals("NEAREST_HALF", plan.getProrationRounding());
    }

    @Test
    void theOrgDefault_cantBeDeactivatedOrDeleted() {
        assertThrows(IllegalArgumentException.class, () -> service.setActive(plan.getId(), false, "hr@test.com"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(plan.getId(), "hr@test.com"));
        verify(planRepo, never()).delete(any());
    }

    @Test
    void aPlanThatWasEverAssigned_cantBeDeleted_onlyDeactivated() {
        LeavePlan interns = LeavePlan.builder().id(UUID.randomUUID()).name("Interns").build();
        when(planRepo.findById(interns.getId())).thenReturn(Optional.of(interns));
        when(allocationRepo.countByPlanId(interns.getId())).thenReturn(1L);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.delete(interns.getId(), "hr@test.com"));
        assertTrue(e.getMessage().contains("Deactivate"));

        when(allocationRepo.countByPlanId(interns.getId())).thenReturn(0L);
        service.delete(interns.getId(), "hr@test.com");
        verify(planRepo).delete(interns);
        verify(audit).log(eq(hr.getId()), eq("LEAVE_PLAN_DELETED"), eq(interns.getId()), any(), isNull());
    }

    @Test
    void deactivatingAPlan_movesItsEmployees_soCreditsAreReconciled() {
        LeavePlan interns = LeavePlan.builder().id(UUID.randomUUID()).name("Interns").build();
        when(planRepo.findById(interns.getId())).thenReturn(Optional.of(interns));
        when(planRepo.findAllByOrderByNameAsc()).thenReturn(List.of(plan, interns));
        service.setActive(interns.getId(), false, "hr@test.com");
        assertFalse(interns.isActive());
        verify(accrual).reconcile(isNull(), any(), eq(false));
        verify(audit).log(eq(hr.getId()), eq("LEAVE_PLAN_STATUS_CHANGED"), eq(interns.getId()), any(), any());
    }

    @Test
    void creating_rejectsADuplicateName_andANonJanuaryLeaveYear() {
        when(planRepo.existsByNameIgnoreCase("Interns")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.create(new CreatePlanRequest("Interns", null, 1, List.of()), "hr@test.com"));
        when(planRepo.existsByNameIgnoreCase("Apprentices")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.create(new CreatePlanRequest("Apprentices", null, 4, List.of()), "hr@test.com"));
        verify(planRepo, never()).save(any());
    }
}
