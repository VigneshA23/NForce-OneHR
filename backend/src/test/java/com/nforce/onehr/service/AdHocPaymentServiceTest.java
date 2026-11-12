package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AddAdHocPaymentRequest;
import com.nforce.onehr.dto.payroll.AdHocPaymentResponse;
import com.nforce.onehr.dto.payroll.BulkAdHocPaymentResult;
import com.nforce.onehr.dto.payroll.BulkAddAdHocPaymentRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class AdHocPaymentServiceTest {

    @Mock private AdHocPaymentRepository adHocPaymentRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private AdHocPaymentService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID runId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdHocPaymentService(adHocPaymentRepository, payrollRunRepository, payslipRepository,
                salaryComponentRepository, employeeRepository, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.existsById(employeeId)).thenReturn(true);
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());

        lenient().when(adHocPaymentRepository.save(any(AdHocPayment.class))).thenAnswer(inv -> {
            AdHocPayment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        lenient().when(adHocPaymentRepository.saveAll(any())).thenAnswer(inv -> {
            List<AdHocPayment> payments = inv.getArgument(0);
            payments.forEach(p -> { if (p.getId() == null) p.setId(UUID.randomUUID()); });
            return payments;
        });
        lenient().when(employeeRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    private PayrollRun draftRun() {
        return PayrollRun.builder().id(runId).status(PayrollRunStatus.DRAFT).build();
    }

    private AddAdHocPaymentRequest req(String componentName, BigDecimal amount) {
        AddAdHocPaymentRequest r = new AddAdHocPaymentRequest();
        r.setEmployeeId(employeeId);
        r.setComponentName(componentName);
        r.setAmount(amount);
        r.setReason("Referral bonus");
        return r;
    }

    private SalaryComponent adHocEarningCatalog() {
        return SalaryComponent.builder().id(UUID.randomUUID()).name("Referral Bonus")
                .type(SalaryComponentType.AD_HOC_EARNING).active(true).build();
    }

    @Test
    void add_validAdHocEarning_succeeds() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId))
                .thenReturn(Optional.of(Payslip.builder().id(UUID.randomUUID()).build()));
        when(salaryComponentRepository.findByNameIgnoreCase("Referral Bonus")).thenReturn(Optional.of(adHocEarningCatalog()));

        AdHocPaymentResponse resp = service.add(runId, req("Referral Bonus", new BigDecimal("5000.00")), actorEmail);

        assertEquals("Referral Bonus", resp.getComponentName());
        assertEquals(0, new BigDecimal("5000.00").compareTo(resp.getAmount()));
        verify(auditService).log(eq(actorId), eq("PAYROLL_ADHOC_PAYMENT_ADDED"), any(), isNull(), any());
    }

    @Test
    void add_runNotDraft_rejected() {
        PayrollRun submitted = PayrollRun.builder().id(runId).status(PayrollRunStatus.SUBMITTED).build();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(submitted));

        assertThrows(IllegalStateException.class, () -> service.add(runId, req("Referral Bonus", BigDecimal.TEN), actorEmail));
    }

    @Test
    void add_employeeNotInRun_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.add(runId, req("Referral Bonus", BigDecimal.TEN), actorEmail));
    }

    @Test
    void add_unknownComponent_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId))
                .thenReturn(Optional.of(Payslip.builder().id(UUID.randomUUID()).build()));
        when(salaryComponentRepository.findByNameIgnoreCase("Nonexistent")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.add(runId, req("Nonexistent", BigDecimal.TEN), actorEmail));
    }

    @Test
    void add_inactiveComponent_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId))
                .thenReturn(Optional.of(Payslip.builder().id(UUID.randomUUID()).build()));
        SalaryComponent inactive = SalaryComponent.builder().id(UUID.randomUUID()).name("Referral Bonus")
                .type(SalaryComponentType.AD_HOC_EARNING).active(false).build();
        when(salaryComponentRepository.findByNameIgnoreCase("Referral Bonus")).thenReturn(Optional.of(inactive));

        assertThrows(IllegalArgumentException.class, () -> service.add(runId, req("Referral Bonus", BigDecimal.TEN), actorEmail));
    }

    @Test
    void add_wrongComponentType_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId))
                .thenReturn(Optional.of(Payslip.builder().id(UUID.randomUUID()).build()));
        SalaryComponent recurring = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic")
                .type(SalaryComponentType.EARNING).active(true).build();
        when(salaryComponentRepository.findByNameIgnoreCase("Basic")).thenReturn(Optional.of(recurring));

        assertThrows(IllegalArgumentException.class, () -> service.add(runId, req("Basic", BigDecimal.TEN), actorEmail));
    }

    @Test
    void bulkAdd_appliesToEveryEmployeeWithAPayslipInRun_skippingAlreadyCovered() {
        UUID alreadyCoveredEmployeeId = UUID.randomUUID();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(salaryComponentRepository.findByNameIgnoreCase("Food Allowance")).thenReturn(Optional.of(
                SalaryComponent.builder().id(UUID.randomUUID()).name("Food Allowance")
                        .type(SalaryComponentType.AD_HOC_EARNING).active(true).build()));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(
                Payslip.builder().employeeId(employeeId).build(),
                Payslip.builder().employeeId(alreadyCoveredEmployeeId).build()));
        when(adHocPaymentRepository.findEmployeeIdsByPayrollRunIdAndComponentName(runId, "Food Allowance"))
                .thenReturn(List.of(alreadyCoveredEmployeeId));

        BulkAddAdHocPaymentRequest req = new BulkAddAdHocPaymentRequest();
        req.setComponentName("Food Allowance");
        req.setAmount(new BigDecimal("1100.00"));
        req.setReason("Monthly food allowance");

        BulkAdHocPaymentResult result = service.bulkAdd(runId, req, actorEmail);

        assertEquals(1, result.getAdded().size());
        assertEquals(employeeId, result.getAdded().get(0).getEmployeeId());
        assertEquals(1, result.getSkippedCount());
        verify(auditService).log(eq(actorId), eq("PAYROLL_ADHOC_PAYMENT_BULK_ADDED"), eq(runId), isNull(), any());
    }

    @Test
    void bulkAdd_runNotDraft_rejected() {
        PayrollRun submitted = PayrollRun.builder().id(runId).status(PayrollRunStatus.SUBMITTED).build();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(submitted));

        BulkAddAdHocPaymentRequest req = new BulkAddAdHocPaymentRequest();
        req.setComponentName("Food Allowance");
        req.setAmount(BigDecimal.TEN);
        req.setReason("x");

        assertThrows(IllegalStateException.class, () -> service.bulkAdd(runId, req, actorEmail));
    }

    @Test
    void bulkAdd_unknownComponent_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(salaryComponentRepository.findByNameIgnoreCase("Nonexistent")).thenReturn(Optional.empty());

        BulkAddAdHocPaymentRequest req = new BulkAddAdHocPaymentRequest();
        req.setComponentName("Nonexistent");
        req.setAmount(BigDecimal.TEN);
        req.setReason("x");

        assertThrows(NoSuchElementException.class, () -> service.bulkAdd(runId, req, actorEmail));
    }

    @Test
    void remove_draftRun_succeeds() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        UUID paymentId = UUID.randomUUID();
        AdHocPayment payment = AdHocPayment.builder().id(paymentId).payrollRun(draftRun()).employeeId(employeeId)
                .componentName("Referral Bonus").amount(BigDecimal.TEN).reason("x").addedBy(actorId).build();
        when(adHocPaymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        service.remove(runId, paymentId, actorEmail);

        verify(adHocPaymentRepository).delete(payment);
        verify(auditService).log(eq(actorId), eq("PAYROLL_ADHOC_PAYMENT_REMOVED"), any(), any(), isNull());
    }

    @Test
    void remove_runNotDraft_rejected() {
        PayrollRun approved = PayrollRun.builder().id(runId).status(PayrollRunStatus.APPROVED).build();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(approved));

        UUID paymentId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.remove(runId, paymentId, actorEmail));
    }

    @Test
    void remove_paymentBelongsToDifferentRun_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        UUID paymentId = UUID.randomUUID();
        PayrollRun otherRun = PayrollRun.builder().id(UUID.randomUUID()).status(PayrollRunStatus.DRAFT).build();
        AdHocPayment payment = AdHocPayment.builder().id(paymentId).payrollRun(otherRun).employeeId(employeeId)
                .componentName("Referral Bonus").amount(BigDecimal.TEN).reason("x").addedBy(actorId).build();
        when(adHocPaymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThrows(IllegalArgumentException.class, () -> service.remove(runId, paymentId, actorEmail));
    }

    @Test
    void list_returnsAllForRun() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        AdHocPayment payment = AdHocPayment.builder().id(UUID.randomUUID()).payrollRun(draftRun()).employeeId(employeeId)
                .componentName("Referral Bonus").amount(BigDecimal.TEN).reason("x").addedBy(actorId).build();
        when(adHocPaymentRepository.findByPayrollRunId(runId)).thenReturn(List.of(payment));

        List<AdHocPaymentResponse> result = service.list(runId);

        assertEquals(1, result.size());
        assertEquals("Referral Bonus", result.get(0).getComponentName());
    }
}
