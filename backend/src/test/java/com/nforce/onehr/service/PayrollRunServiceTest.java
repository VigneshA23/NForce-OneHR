package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class PayrollRunServiceTest {

    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private PayslipLineItemRepository payslipLineItemRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private EmployeeCompensationRepository employeeCompensationRepository;
    @Mock private SalaryStructureComponentRepository salaryStructureComponentRepository;
    @Mock private PayrollLopCalculationService payrollLopCalculationService;
    @Mock private PayrollProrationService payrollProrationService;
    @Mock private ApprovalRuleEvaluationService approvalRuleEvaluationService;
    @Mock private AdHocPaymentRepository adHocPaymentRepository;
    @Mock private AdHocDeductionRepository adHocDeductionRepository;
    @Mock private AdHocDeductionInstallmentRepository adHocDeductionInstallmentRepository;
    @Mock private SalaryHoldRepository salaryHoldRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private PayrollRunService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID payGroupId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();

    private final LocalDate referenceDate = LocalDate.of(2026, 6, 15);
    private final LocalDate cycleStart = LocalDate.of(2026, 6, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28); // 28-day cycle, same pattern as other tests

    private final Role hrAdminRole = Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build();
    private final Role superAdminRole = Role.builder().id(2).code("SUPER_ADMIN").displayName("Super Admin").build();

    @BeforeEach
    void setUp() {
        service = new PayrollRunService(payrollRunRepository, payslipRepository, payslipLineItemRepository,
                payGroupRepository, employeeCompensationRepository, salaryStructureComponentRepository,
                payrollLopCalculationService, payrollProrationService, approvalRuleEvaluationService,
                adHocPaymentRepository, adHocDeductionRepository, adHocDeductionInstallmentRepository,
                salaryHoldRepository, new SalaryStructureResolutionService(), userRepository, auditService, auditSnapshot);

        // Default actor holds HR_ADMIN — matches this app's one real approver role before US-C09,
        // and the legacy single-HR_ADMIN-stage default evaluatePayrollRun falls back to below.
        User actor = User.builder().id(actorId).email(actorEmail).roles(Set.of(hrAdminRole)).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");

        // US-C09: no active ApprovalRule for PAYROLL_RUN by default — single HR_ADMIN-only stage,
        // the exact behavior this app shipped with before this story.
        lenient().when(approvalRuleEvaluationService.evaluatePayrollRun(any())).thenReturn(
                new ApprovalRuleEvaluationService.Decision(false, List.of("HR_ADMIN"), null));

        PayGroup payGroup = PayGroup.builder().id(payGroupId).cycleStartDay(1).cycleEndDay(28).build();
        lenient().when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(payGroup));

        lenient().when(payrollRunRepository.save(any(PayrollRun.class))).thenAnswer(inv -> {
            PayrollRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        lenient().when(payslipRepository.save(any(Payslip.class))).thenAnswer(inv -> {
            Payslip p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        lenient().when(payslipLineItemRepository.save(any(PayslipLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        // No LOP/proration reduction by default — full 28-day cycle, fully employed, no LOP.
        lenient().when(payrollLopCalculationService.recompute(any(), any())).thenReturn(
                PayrollLopCalculationResponse.builder().effectiveLopDays(BigDecimal.ZERO).build());
        lenient().when(payrollProrationService.recompute(any(), any())).thenReturn(
                PayrollProrationResponse.builder().cycleDays(28).employedDays(28).prorationFactor(new BigDecimal("1.0000")).build());

        // US-C24: no ad-hoc payments by default.
        lenient().when(adHocPaymentRepository.findByPayrollRunIdAndEmployeeId(any(), any())).thenReturn(List.of());

        // US-C25: no ad-hoc deductions by default.
        lenient().when(adHocDeductionRepository.findByEmployeeIdAndStatus(any(), any())).thenReturn(List.of());
        lenient().when(adHocDeductionInstallmentRepository.save(any(AdHocDeductionInstallment.class))).thenAnswer(inv -> {
            AdHocDeductionInstallment i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            return i;
        });
        lenient().when(adHocDeductionRepository.save(any(AdHocDeduction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(adHocDeductionInstallmentRepository.findByPayrollRunId(any())).thenReturn(List.of());

        // US-C26: no active salary holds by default.
        lenient().when(salaryHoldRepository.findActiveEmployeeIdsByPayrollRunId(any())).thenReturn(Set.of());
    }

    private SalaryComponent catalogComponent(String name, String type, boolean prorateOnLop) {
        return SalaryComponent.builder().id(UUID.randomUUID()).name(name).type(type).prorateOnLop(prorateOnLop).build();
    }

    private SalaryStructureComponent structureComponent(SalaryComponent catalog, String calcType, BigDecimal value, UUID referenceId) {
        return SalaryStructureComponent.builder().id(UUID.randomUUID()).component(catalog).calcType(calcType)
                .value(value).referenceStructureComponentId(referenceId).build();
    }

    private EmployeeCompensation compensation(BigDecimal ctc) {
        SalaryStructure structure = SalaryStructure.builder().id(structureId).name("Test Structure").build();
        return EmployeeCompensation.builder().id(UUID.randomUUID()).employeeId(employeeId).structure(structure)
                .ctc(ctc).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
    }

    private GenerateDraftRequest generateReq() {
        GenerateDraftRequest req = new GenerateDraftRequest();
        req.setPayGroupId(payGroupId);
        req.setReferenceDate(referenceDate);
        return req;
    }

    @Test
    void generateDraft_realKekaStructure_computesEveryComponentCorrectly() {
        // The org's actual confirmed 2026-27 structure (Keka screenshot, 2026-11-05): Basic =
        // 50% of Gross, HRA = 40% of Basic, LTA = 20% of Basic, Conveyance fixed 19,200/year
        // (not prorated), Other Allowances = balancing remainder. All FIXED/PERCENT_OF_* values
        // are ANNUAL — confirmed by that same screenshot showing Conveyance as 19,200, i.e.
        // 1,600/month x 12, not 1,600 itself.
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryComponent hraCatalog = catalogComponent("HRA", SalaryComponentType.EARNING, true);
        SalaryComponent ltaCatalog = catalogComponent("LTA (Travel Reimbursement)", SalaryComponentType.REIMBURSEMENT, true);
        SalaryComponent conveyanceCatalog = catalogComponent("Conveyance Allowance", SalaryComponentType.EARNING, false);
        SalaryComponent otherCatalog = catalogComponent("Other/Special Allowance", SalaryComponentType.EARNING, true);

        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.PERCENT_OF_GROSS, new BigDecimal("50"), null);
        SalaryStructureComponent hra = structureComponent(hraCatalog, SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("40"), basic.getId());
        SalaryStructureComponent lta = structureComponent(ltaCatalog, SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("20"), basic.getId());
        SalaryStructureComponent conveyance = structureComponent(conveyanceCatalog, SalaryCalculationType.FIXED, new BigDecimal("19200"), null);
        SalaryStructureComponent balancing = structureComponent(otherCatalog, SalaryCalculationType.BALANCING_REMAINDER, null, null);

        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic, hra, lta, conveyance, balancing));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        PayrollRunResponse resp = service.generateDraft(generateReq(), actorEmail);

        org.mockito.ArgumentCaptor<PayslipLineItem> captor = org.mockito.ArgumentCaptor.forClass(PayslipLineItem.class);
        verify(payslipLineItemRepository, times(5)).save(captor.capture());
        Map<String, BigDecimal> byName = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(PayslipLineItem::getComponentName, PayslipLineItem::getAmount));

        // Annual: Basic=600,000; HRA=240,000; LTA=120,000; Conveyance=19,200; Other=1,200,000-979,200=220,800.
        // Monthly (/12): Basic=50,000; HRA=20,000; LTA=10,000; Conveyance=1,600; Other=18,400. Sums to CTC/12 exactly.
        assertEquals(0, new BigDecimal("50000.00").compareTo(byName.get("Basic")));
        assertEquals(0, new BigDecimal("20000.00").compareTo(byName.get("HRA")));
        assertEquals(0, new BigDecimal("10000.00").compareTo(byName.get("LTA (Travel Reimbursement)")));
        assertEquals(0, new BigDecimal("1600.00").compareTo(byName.get("Conveyance Allowance")));
        assertEquals(0, new BigDecimal("18400.00").compareTo(byName.get("Other/Special Allowance")));

        assertEquals(PayrollRunStatus.DRAFT, resp.getStatus());
        assertEquals(1, resp.getEmployeeCount());
        assertEquals(0, new BigDecimal("100000.00").compareTo(resp.getTotalGross()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getTotalDeductions()));
        assertEquals(0, new BigDecimal("100000.00").compareTo(resp.getTotalNet()));
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_DRAFT_GENERATED"), any(), isNull(), any());
    }

    @Test
    void generateDraft_percentOfGross_usesCtcMonthlyAsGross() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.PERCENT_OF_GROSS, new BigDecimal("40"), null);

        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        PayrollRunResponse resp = service.generateDraft(generateReq(), actorEmail);

        // 40% of CTC monthly (100,000) = 40,000.
        assertEquals(0, new BigDecimal("40000.00").compareTo(resp.getTotalGross()));
    }

    @Test
    void generateDraft_prorationReducesOnlyProrateOnLopComponents() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryComponent conveyanceCatalog = catalogComponent("Conveyance Allowance", SalaryComponentType.EARNING, false);
        // FIXED values are annual — 336,000/year = 28,000/month; 19,200/year = 1,600/month (the real Conveyance figure).
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.FIXED, new BigDecimal("336000"), null);
        SalaryStructureComponent conveyance = structureComponent(conveyanceCatalog, SalaryCalculationType.FIXED, new BigDecimal("19200"), null);

        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic, conveyance));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());
        // 2 LOP days out of a 28-day cycle, fully employed: factor = (28-2)/28 = 0.9286
        when(payrollLopCalculationService.recompute(any(), any())).thenReturn(
                PayrollLopCalculationResponse.builder().effectiveLopDays(new BigDecimal("2")).build());

        service.generateDraft(generateReq(), actorEmail);

        org.mockito.ArgumentCaptor<PayslipLineItem> captor = org.mockito.ArgumentCaptor.forClass(PayslipLineItem.class);
        verify(payslipLineItemRepository, times(2)).save(captor.capture());
        PayslipLineItem basicItem = captor.getAllValues().stream().filter(i -> i.getComponentName().equals("Basic")).findFirst().orElseThrow();
        PayslipLineItem conveyanceItem = captor.getAllValues().stream().filter(i -> i.getComponentName().equals("Conveyance Allowance")).findFirst().orElseThrow();

        // Basic: 28,000 * 0.9286 (26/28 rounded to 4dp) = 26,000.80; Conveyance untouched (prorateOnLop=false).
        assertEquals(0, new BigDecimal("26000.80").compareTo(basicItem.getAmount()));
        assertEquals(0, new BigDecimal("1600.00").compareTo(conveyanceItem.getAmount()));
    }

    @Test
    void generateDraft_adHocPayment_addedAtFaceValue_neverProrated() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.FIXED, new BigDecimal("336000"), null);

        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());
        // 2 LOP days out of 28 — would reduce a prorated component, but must NOT reduce the ad-hoc one.
        when(payrollLopCalculationService.recompute(any(), any())).thenReturn(
                PayrollLopCalculationResponse.builder().effectiveLopDays(new BigDecimal("2")).build());

        UUID adHocComponentId = UUID.randomUUID();
        AdHocPayment adHoc = AdHocPayment.builder().id(UUID.randomUUID())
                .employeeId(employeeId).componentId(adHocComponentId).componentName("Referral Bonus")
                .componentType(SalaryComponentType.AD_HOC_EARNING).amount(new BigDecimal("5000.00"))
                .reason("Referral").addedBy(actorId).build();
        when(adHocPaymentRepository.findByPayrollRunIdAndEmployeeId(any(), eq(employeeId))).thenReturn(List.of(adHoc));

        PayrollRunResponse resp = service.generateDraft(generateReq(), actorEmail);

        org.mockito.ArgumentCaptor<PayslipLineItem> captor = org.mockito.ArgumentCaptor.forClass(PayslipLineItem.class);
        verify(payslipLineItemRepository, times(2)).save(captor.capture());
        PayslipLineItem bonusItem = captor.getAllValues().stream()
                .filter(i -> i.getComponentName().equals("Referral Bonus")).findFirst().orElseThrow();

        assertFalse(bonusItem.isProrated());
        assertEquals(0, new BigDecimal("5000.00").compareTo(bonusItem.getAmount()));
        // Basic: 28,000 * (26/28 rounded) = 26,000.80 (prorated); + 5,000.00 ad-hoc (not prorated).
        assertEquals(0, new BigDecimal("31000.80").compareTo(resp.getTotalGross()));
    }

    @Test
    void generateDraft_adHocDeduction_firstInstallment_appliedAndBalanceDecremented() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.FIXED, new BigDecimal("600000"), null);
        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        AdHocDeduction deduction = AdHocDeduction.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .componentId(UUID.randomUUID()).componentName("Asset Damage Recovery (ADR)")
                .componentType(SalaryComponentType.AD_HOC_DEDUCTION)
                .totalAmount(new BigDecimal("6000.00")).installmentAmount(new BigDecimal("2000.00"))
                .remainingBalance(new BigDecimal("6000.00")).status(AdHocDeductionStatus.ACTIVE).build();
        when(adHocDeductionRepository.findByEmployeeIdAndStatus(employeeId, AdHocDeductionStatus.ACTIVE)).thenReturn(List.of(deduction));
        when(adHocDeductionInstallmentRepository.findByAdHocDeductionIdAndPayrollRunId(eq(deduction.getId()), any()))
                .thenReturn(Optional.empty());

        PayrollRunResponse resp = service.generateDraft(generateReq(), actorEmail);

        org.mockito.ArgumentCaptor<PayslipLineItem> captor = org.mockito.ArgumentCaptor.forClass(PayslipLineItem.class);
        verify(payslipLineItemRepository, times(2)).save(captor.capture());
        PayslipLineItem deductionItem = captor.getAllValues().stream()
                .filter(i -> i.getComponentName().equals("Asset Damage Recovery (ADR)")).findFirst().orElseThrow();

        assertFalse(deductionItem.isProrated());
        assertEquals(0, new BigDecimal("2000.00").compareTo(deductionItem.getAmount()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(deduction.getRemainingBalance()));
        assertEquals(AdHocDeductionStatus.ACTIVE, deduction.getStatus());
        assertEquals(0, new BigDecimal("50000.00").compareTo(resp.getTotalGross()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(resp.getTotalDeductions()));
    }

    @Test
    void generateDraft_adHocDeduction_lastInstallment_capsAtRemainingBalance_andCompletes() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.FIXED, new BigDecimal("600000"), null);
        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        AdHocDeduction deduction = AdHocDeduction.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .componentId(UUID.randomUUID()).componentName("Asset Damage Recovery (ADR)")
                .componentType(SalaryComponentType.AD_HOC_DEDUCTION)
                .totalAmount(new BigDecimal("6000.00")).installmentAmount(new BigDecimal("2000.00"))
                .remainingBalance(new BigDecimal("1500.00")).status(AdHocDeductionStatus.ACTIVE).build();
        when(adHocDeductionRepository.findByEmployeeIdAndStatus(employeeId, AdHocDeductionStatus.ACTIVE)).thenReturn(List.of(deduction));
        when(adHocDeductionInstallmentRepository.findByAdHocDeductionIdAndPayrollRunId(eq(deduction.getId()), any()))
                .thenReturn(Optional.empty());

        service.generateDraft(generateReq(), actorEmail);

        assertEquals(0, BigDecimal.ZERO.compareTo(deduction.getRemainingBalance()));
        assertEquals(AdHocDeductionStatus.COMPLETED, deduction.getStatus());
    }

    @Test
    void generateDraft_adHocDeduction_regenerate_reusesExistingInstallment_doesNotDecrementBalanceAgain() {
        SalaryComponent basicCatalog = catalogComponent("Basic", SalaryComponentType.EARNING, true);
        SalaryStructureComponent basic = structureComponent(basicCatalog, SalaryCalculationType.FIXED, new BigDecimal("600000"), null);
        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(basic));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        AdHocDeduction deduction = AdHocDeduction.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .componentId(UUID.randomUUID()).componentName("Asset Damage Recovery (ADR)")
                .componentType(SalaryComponentType.AD_HOC_DEDUCTION)
                .totalAmount(new BigDecimal("6000.00")).installmentAmount(new BigDecimal("2000.00"))
                .remainingBalance(new BigDecimal("4000.00")).status(AdHocDeductionStatus.ACTIVE).build();
        when(adHocDeductionRepository.findByEmployeeIdAndStatus(employeeId, AdHocDeductionStatus.ACTIVE)).thenReturn(List.of(deduction));
        // An installment for this exact (deduction, run) pair already exists — simulating a regenerate.
        AdHocDeductionInstallment existing = AdHocDeductionInstallment.builder().id(UUID.randomUUID())
                .adHocDeduction(deduction).amount(new BigDecimal("2000.00")).build();
        when(adHocDeductionInstallmentRepository.findByAdHocDeductionIdAndPayrollRunId(eq(deduction.getId()), any()))
                .thenReturn(Optional.of(existing));

        service.generateDraft(generateReq(), actorEmail);

        // Balance must stay untouched — no new installment was created, the existing one was reused.
        assertEquals(0, new BigDecimal("4000.00").compareTo(deduction.getRemainingBalance()));
        verify(adHocDeductionRepository, never()).save(any());
        verify(adHocDeductionInstallmentRepository, never()).save(any());
    }

    @Test
    void rollback_reversesAdHocDeductionInstallment_restoresBalanceAndReopensCompletedPlan() {
        PayrollRun approved = run(PayrollRunStatus.APPROVED);
        when(payrollRunRepository.findById(approved.getId())).thenReturn(Optional.of(approved));
        when(payrollRunRepository.findLaterLockedRuns(payGroupId, cycleStart)).thenReturn(List.of());

        AdHocDeduction deduction = AdHocDeduction.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .componentName("Asset Damage Recovery (ADR)").totalAmount(new BigDecimal("1500.00"))
                .installmentAmount(new BigDecimal("2000.00")).remainingBalance(BigDecimal.ZERO)
                .status(AdHocDeductionStatus.COMPLETED).build();
        AdHocDeductionInstallment installment = AdHocDeductionInstallment.builder().id(UUID.randomUUID())
                .adHocDeduction(deduction).payrollRun(approved).amount(new BigDecimal("1500.00")).build();
        when(adHocDeductionInstallmentRepository.findByPayrollRunId(approved.getId())).thenReturn(List.of(installment));

        RollbackRunRequest req = new RollbackRunRequest();
        req.setReason("test rollback reverses deduction");
        service.rollbackWholeRun(approved.getId(), req, actorEmail);

        assertEquals(0, new BigDecimal("1500.00").compareTo(deduction.getRemainingBalance()));
        assertEquals(AdHocDeductionStatus.ACTIVE, deduction.getStatus());
        verify(adHocDeductionInstallmentRepository).delete(installment);
    }

    @Test
    void generateDraft_circularPercentOfComponentReference_throws() {
        SalaryComponent aCatalog = catalogComponent("A", SalaryComponentType.EARNING, true);
        SalaryComponent bCatalog = catalogComponent("B", SalaryComponentType.EARNING, true);
        SalaryStructureComponent a = structureComponent(aCatalog, SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("10"), null);
        SalaryStructureComponent b = structureComponent(bCatalog, SalaryCalculationType.PERCENT_OF_COMPONENT, new BigDecimal("10"), a.getId());
        a.setReferenceStructureComponentId(b.getId()); // a references b, b references a — circular

        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(salaryStructureComponentRepository.findByStructureId(structureId)).thenReturn(List.of(a, b));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.generateDraft(generateReq(), actorEmail));
    }

    @Test
    void generateDraft_heldEmployee_excludedEntirely_noPayslipNoDeductions() {
        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of(compensation(new BigDecimal("1200000"))));
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());
        when(salaryHoldRepository.findActiveEmployeeIdsByPayrollRunId(any())).thenReturn(Set.of(employeeId));

        PayrollRunResponse resp = service.generateDraft(generateReq(), actorEmail);

        assertEquals(0, resp.getEmployeeCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getTotalGross()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getTotalNet()));
        verify(payslipRepository, never()).save(any());
        verify(payslipLineItemRepository, never()).save(any());
    }

    @Test
    void generateDraft_noEmployeesWithCompensation_rejected() {
        when(employeeCompensationRepository.findAllEffectiveAt(cycleEnd)).thenReturn(List.of());
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.generateDraft(generateReq(), actorEmail));
    }

    @Test
    void generateDraft_regenerateWhileNotDraft_rejected() {
        PayrollRun existing = PayrollRun.builder().id(UUID.randomUUID()).payGroupId(payGroupId)
                .cycleStart(cycleStart).cycleEnd(cycleEnd).status(PayrollRunStatus.SUBMITTED).build();
        when(payrollRunRepository.findByPayGroupIdAndCycleStartAndCycleEnd(payGroupId, cycleStart, cycleEnd)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> service.generateDraft(generateReq(), actorEmail));
    }

    private PayrollRun run(String status) {
        return PayrollRun.builder().id(UUID.randomUUID()).payGroupId(payGroupId).cycleStart(cycleStart).cycleEnd(cycleEnd)
                .status(status).totalGross(new BigDecimal("100000.00")).totalDeductions(BigDecimal.ZERO).totalNet(new BigDecimal("100000.00")).build();
    }

    @Test
    void submit_reconciliationMatches_transitionsToSubmitted() {
        PayrollRun draft = run(PayrollRunStatus.DRAFT);
        when(payrollRunRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(payslipLineItemRepository.sumAmountByComponentType(draft.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{SalaryComponentType.EARNING, new BigDecimal("100000.00")}));

        PayrollRunResponse resp = service.submit(draft.getId(), actorEmail);

        assertEquals(PayrollRunStatus.SUBMITTED, resp.getStatus());
        assertNotNull(resp.getSubmittedAt());
        // US-C09: legacy default (no active rule) snapshots a single HR_ADMIN-only stage.
        assertEquals(List.of("HR_ADMIN"), resp.getApprovalStages());
        assertEquals("HR_ADMIN", resp.getPendingApprovalStage());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_TRANSITION"), any(), any(), any());
    }

    @Test
    void submit_activeRuleRequiresEscalation_snapshotsTwoStageChain() {
        PayrollRun draft = run(PayrollRunStatus.DRAFT);
        when(payrollRunRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(payslipLineItemRepository.sumAmountByComponentType(draft.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{SalaryComponentType.EARNING, new BigDecimal("100000.00")}));
        UUID ruleId = UUID.randomUUID();
        when(approvalRuleEvaluationService.evaluatePayrollRun(new BigDecimal("100000.00"))).thenReturn(
                new ApprovalRuleEvaluationService.Decision(true, List.of("HR_ADMIN", "SUPER_ADMIN"), ruleId));

        PayrollRunResponse resp = service.submit(draft.getId(), actorEmail);

        assertEquals(List.of("HR_ADMIN", "SUPER_ADMIN"), resp.getApprovalStages());
        assertEquals("HR_ADMIN", resp.getPendingApprovalStage());
    }

    @Test
    void submit_reconciliationMismatch_rejected() {
        PayrollRun draft = run(PayrollRunStatus.DRAFT);
        when(payrollRunRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(payslipLineItemRepository.sumAmountByComponentType(draft.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{SalaryComponentType.EARNING, new BigDecimal("99999.00")}));

        assertThrows(IllegalStateException.class, () -> service.submit(draft.getId(), actorEmail));
    }

    @Test
    void submit_notDraft_rejected() {
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        assertThrows(IllegalStateException.class, () -> service.submit(submitted.getId(), actorEmail));
    }

    @Test
    void approve_fromSubmitted_succeeds() {
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        PayrollRunResponse resp = service.approve(submitted.getId(), actorEmail);

        assertEquals(PayrollRunStatus.APPROVED, resp.getStatus());
        assertNotNull(resp.getApprovedAt());
    }

    @Test
    void approve_notSubmitted_rejected() {
        PayrollRun draft = run(PayrollRunStatus.DRAFT);
        when(payrollRunRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThrows(IllegalStateException.class, () -> service.approve(draft.getId(), actorEmail));
    }

    @Test
    void approve_actorMissingPendingStageRole_rejected() {
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        submitted.setApprovalStages("HR_ADMIN,SUPER_ADMIN");
        submitted.setPendingApprovalStage("SUPER_ADMIN");
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));
        // Default test actor only holds HR_ADMIN, but the run is awaiting SUPER_ADMIN.

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.approve(submitted.getId(), actorEmail));
    }

    @Test
    void approve_twoStageChain_hrAdminAdvancesThenSuperAdminFinalizes() {
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        submitted.setApprovalStages("HR_ADMIN,SUPER_ADMIN");
        submitted.setPendingApprovalStage("HR_ADMIN");
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        PayrollRunResponse afterFirst = service.approve(submitted.getId(), actorEmail);

        assertEquals(PayrollRunStatus.SUBMITTED, afterFirst.getStatus());
        assertEquals("SUPER_ADMIN", afterFirst.getPendingApprovalStage());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_STAGE_APPROVED"), any(), any(), any());

        // A different actor, holding SUPER_ADMIN, clears the final stage.
        UUID superAdminActorId = UUID.randomUUID();
        String superAdminEmail = "super.admin@test.com";
        User superAdminActor = User.builder().id(superAdminActorId).email(superAdminEmail).roles(Set.of(superAdminRole)).build();
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminActor));

        PayrollRunResponse afterSecond = service.approve(submitted.getId(), superAdminEmail);

        assertEquals(PayrollRunStatus.APPROVED, afterSecond.getStatus());
        assertNull(afterSecond.getPendingApprovalStage());
        assertNotNull(afterSecond.getApprovedAt());
    }

    @Test
    void approve_superAdminActor_collapsesTwoStageChainInOneCall() {
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        submitted.setApprovalStages("HR_ADMIN,SUPER_ADMIN");
        submitted.setPendingApprovalStage("HR_ADMIN");
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));
        User superAdminActor = User.builder().id(actorId).email(actorEmail).roles(Set.of(superAdminRole)).build();
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(superAdminActor));

        PayrollRunResponse resp = service.approve(submitted.getId(), actorEmail);

        assertEquals(PayrollRunStatus.APPROVED, resp.getStatus());
        assertNull(resp.getPendingApprovalStage());
    }

    @Test
    void disburse_fromApproved_succeeds() {
        PayrollRun approved = run(PayrollRunStatus.APPROVED);
        when(payrollRunRepository.findById(approved.getId())).thenReturn(Optional.of(approved));

        PayrollRunResponse resp = service.disburse(approved.getId(), actorEmail);

        assertEquals(PayrollRunStatus.DISBURSED, resp.getStatus());
        assertNotNull(resp.getDisbursedAt());
    }

    @Test
    void rollback_fromApproved_revertsToDraft_withReasonAudited() {
        PayrollRun approved = run(PayrollRunStatus.APPROVED);
        when(payrollRunRepository.findById(approved.getId())).thenReturn(Optional.of(approved));
        when(payrollRunRepository.findLaterLockedRuns(payGroupId, cycleStart)).thenReturn(List.of());

        RollbackRunRequest req = new RollbackRunRequest();
        req.setReason("Reconciliation error found after approval");
        PayrollRunResponse resp = service.rollbackWholeRun(approved.getId(), req, actorEmail);

        assertEquals(PayrollRunStatus.DRAFT, resp.getStatus());
        assertEquals("Reconciliation error found after approval", resp.getLastRollbackReason());
        assertNotNull(resp.getLastRolledBackAt());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_TRANSITION"), any(), any(), any());
    }

    @Test
    void rollback_fromSubmitted_allowed_clearsPendingApprovalStage() {
        // US-C09: before this story, a SUBMITTED run had no path back to DRAFT at all — this
        // closes that gap (an approver rejecting mid-chain reuses rollback).
        PayrollRun submitted = run(PayrollRunStatus.SUBMITTED);
        submitted.setApprovalStages("HR_ADMIN,SUPER_ADMIN");
        submitted.setPendingApprovalStage("SUPER_ADMIN");
        when(payrollRunRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));
        when(payrollRunRepository.findLaterLockedRuns(payGroupId, cycleStart)).thenReturn(List.of());

        RollbackRunRequest req = new RollbackRunRequest();
        req.setReason("Found an error at the Super Admin stage");
        PayrollRunResponse resp = service.rollbackWholeRun(submitted.getId(), req, actorEmail);

        assertEquals(PayrollRunStatus.DRAFT, resp.getStatus());
        assertNull(resp.getPendingApprovalStage());
    }

    @Test
    void rollback_whileDraft_rejected() {
        PayrollRun draft = run(PayrollRunStatus.DRAFT);
        when(payrollRunRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        RollbackRunRequest req = new RollbackRunRequest();
        req.setReason("test");
        assertThrows(IllegalStateException.class, () -> service.rollbackWholeRun(draft.getId(), req, actorEmail));
    }

    @Test
    void rollback_laterCycleLocked_rejected() {
        PayrollRun approved = run(PayrollRunStatus.APPROVED);
        when(payrollRunRepository.findById(approved.getId())).thenReturn(Optional.of(approved));
        PayrollRun laterRun = run(PayrollRunStatus.APPROVED);
        when(payrollRunRepository.findLaterLockedRuns(payGroupId, cycleStart)).thenReturn(List.of(laterRun));

        RollbackRunRequest req = new RollbackRunRequest();
        req.setReason("test");
        assertThrows(IllegalStateException.class, () -> service.rollbackWholeRun(approved.getId(), req, actorEmail));
    }

    @Test
    void get_unknownRun_throwsNoSuchElement() {
        UUID missing = UUID.randomUUID();
        when(payrollRunRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.get(missing));
    }
}
