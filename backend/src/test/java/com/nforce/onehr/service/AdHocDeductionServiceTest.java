package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AdHocDeductionInstallmentResponse;
import com.nforce.onehr.dto.payroll.AdHocDeductionResponse;
import com.nforce.onehr.dto.payroll.CreateAdHocDeductionRequest;
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
class AdHocDeductionServiceTest {

    @Mock private AdHocDeductionRepository adHocDeductionRepository;
    @Mock private AdHocDeductionInstallmentRepository adHocDeductionInstallmentRepository;
    @Mock private SalaryComponentRepository salaryComponentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private AdHocDeductionService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdHocDeductionService(adHocDeductionRepository, adHocDeductionInstallmentRepository,
                salaryComponentRepository, employeeRepository, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.existsById(employeeId)).thenReturn(true);
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());

        lenient().when(adHocDeductionRepository.save(any(AdHocDeduction.class))).thenAnswer(inv -> {
            AdHocDeduction d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    private CreateAdHocDeductionRequest req(String componentName, BigDecimal total, BigDecimal installment) {
        CreateAdHocDeductionRequest r = new CreateAdHocDeductionRequest();
        r.setEmployeeId(employeeId);
        r.setComponentName(componentName);
        r.setTotalAmount(total);
        r.setInstallmentAmount(installment);
        r.setReason("Asset damage");
        return r;
    }

    private SalaryComponent adHocDeductionCatalog() {
        return SalaryComponent.builder().id(UUID.randomUUID()).name("Asset Damage Recovery (ADR)")
                .type(SalaryComponentType.AD_HOC_DEDUCTION).active(true).build();
    }

    @Test
    void create_validAdHocDeduction_succeeds_withRemainingBalanceEqualToTotal() {
        when(salaryComponentRepository.findByNameIgnoreCase("Asset Damage Recovery (ADR)")).thenReturn(Optional.of(adHocDeductionCatalog()));

        AdHocDeductionResponse resp = service.create(req("Asset Damage Recovery (ADR)", new BigDecimal("6000.00"), new BigDecimal("2000.00")), actorEmail);

        assertEquals(AdHocDeductionStatus.ACTIVE, resp.getStatus());
        assertEquals(0, new BigDecimal("6000.00").compareTo(resp.getRemainingBalance()));
        verify(auditService).log(eq(actorId), eq("PAYROLL_ADHOC_DEDUCTION_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_wrongComponentType_rejected() {
        SalaryComponent recurring = SalaryComponent.builder().id(UUID.randomUUID()).name("Basic")
                .type(SalaryComponentType.EARNING).active(true).build();
        when(salaryComponentRepository.findByNameIgnoreCase("Basic")).thenReturn(Optional.of(recurring));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(req("Basic", BigDecimal.TEN, BigDecimal.ONE), actorEmail));
    }

    @Test
    void create_inactiveComponent_rejected() {
        SalaryComponent inactive = SalaryComponent.builder().id(UUID.randomUUID()).name("Asset Damage Recovery (ADR)")
                .type(SalaryComponentType.AD_HOC_DEDUCTION).active(false).build();
        when(salaryComponentRepository.findByNameIgnoreCase("Asset Damage Recovery (ADR)")).thenReturn(Optional.of(inactive));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(req("Asset Damage Recovery (ADR)", BigDecimal.TEN, BigDecimal.ONE), actorEmail));
    }

    @Test
    void create_unknownComponent_rejected() {
        when(salaryComponentRepository.findByNameIgnoreCase("Nonexistent")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.create(req("Nonexistent", BigDecimal.TEN, BigDecimal.ONE), actorEmail));
    }

    @Test
    void create_employeeNotFound_rejected() {
        when(employeeRepository.existsById(employeeId)).thenReturn(false);

        assertThrows(NoSuchElementException.class,
                () -> service.create(req("Asset Damage Recovery (ADR)", BigDecimal.TEN, BigDecimal.ONE), actorEmail));
    }

    @Test
    void cancel_activeDeduction_succeeds() {
        UUID deductionId = UUID.randomUUID();
        AdHocDeduction deduction = AdHocDeduction.builder().id(deductionId).employeeId(employeeId)
                .componentName("Asset Damage Recovery (ADR)").totalAmount(new BigDecimal("6000.00"))
                .installmentAmount(new BigDecimal("2000.00")).remainingBalance(new BigDecimal("4000.00"))
                .status(AdHocDeductionStatus.ACTIVE).createdBy(actorId).build();
        when(adHocDeductionRepository.findById(deductionId)).thenReturn(Optional.of(deduction));

        AdHocDeductionResponse resp = service.cancel(deductionId, actorEmail);

        assertEquals(AdHocDeductionStatus.CANCELLED, resp.getStatus());
        verify(auditService).log(eq(actorId), eq("PAYROLL_ADHOC_DEDUCTION_CANCELLED"), any(), any(), any());
    }

    @Test
    void cancel_alreadyCompleted_rejected() {
        UUID deductionId = UUID.randomUUID();
        AdHocDeduction deduction = AdHocDeduction.builder().id(deductionId).employeeId(employeeId)
                .componentName("Asset Damage Recovery (ADR)").totalAmount(new BigDecimal("6000.00"))
                .installmentAmount(new BigDecimal("2000.00")).remainingBalance(BigDecimal.ZERO)
                .status(AdHocDeductionStatus.COMPLETED).createdBy(actorId).build();
        when(adHocDeductionRepository.findById(deductionId)).thenReturn(Optional.of(deduction));

        assertThrows(IllegalStateException.class, () -> service.cancel(deductionId, actorEmail));
    }

    @Test
    void listByEmployee_returnsAll() {
        AdHocDeduction deduction = AdHocDeduction.builder().id(UUID.randomUUID()).employeeId(employeeId)
                .componentName("Asset Damage Recovery (ADR)").totalAmount(BigDecimal.TEN).installmentAmount(BigDecimal.ONE)
                .remainingBalance(BigDecimal.TEN).status(AdHocDeductionStatus.ACTIVE).createdBy(actorId).build();
        when(adHocDeductionRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)).thenReturn(List.of(deduction));

        List<AdHocDeductionResponse> result = service.listByEmployee(employeeId);

        assertEquals(1, result.size());
    }

    @Test
    void listInstallments_returnsHistory() {
        UUID deductionId = UUID.randomUUID();
        AdHocDeduction deduction = AdHocDeduction.builder().id(deductionId).employeeId(employeeId)
                .componentName("Asset Damage Recovery (ADR)").totalAmount(BigDecimal.TEN).installmentAmount(BigDecimal.ONE)
                .remainingBalance(BigDecimal.TEN).status(AdHocDeductionStatus.ACTIVE).createdBy(actorId).build();
        when(adHocDeductionRepository.findById(deductionId)).thenReturn(Optional.of(deduction));

        PayrollRun run = PayrollRun.builder().id(UUID.randomUUID()).build();
        AdHocDeductionInstallment installment = AdHocDeductionInstallment.builder().id(UUID.randomUUID())
                .adHocDeduction(deduction).payrollRun(run).amount(new BigDecimal("2000.00")).build();
        when(adHocDeductionInstallmentRepository.findByAdHocDeductionId(deductionId)).thenReturn(List.of(installment));

        List<AdHocDeductionInstallmentResponse> result = service.listInstallments(deductionId);

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("2000.00").compareTo(result.get(0).getAmount()));
    }
}
