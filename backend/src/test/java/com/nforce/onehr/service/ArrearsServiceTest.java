package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.ArrearsResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.ArrearsRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class ArrearsServiceTest {

    @Mock private ArrearsRepository arrearsRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    @InjectMocks private ArrearsService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();
    private final LocalDate cycleStart = LocalDate.of(2026, 6, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(arrearsRepository.save(any(Arrears.class))).thenAnswer(inv -> {
            Arrears a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    @Test
    void createPending_zeroAmount_returnsNullAndDoesNotSave() {
        Arrears result = service.createPending(employeeId, payGroupId, BigDecimal.ZERO, "reason",
                ArrearsSourceType.LOP_REVERSAL, cycleStart, cycleEnd, actorEmail);

        assertNull(result);
        verify(arrearsRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void createPending_positiveAmount_savesPendingRowAndAudits() {
        Arrears result = service.createPending(employeeId, payGroupId, new BigDecimal("1500.00"), "backdated revision",
                ArrearsSourceType.SALARY_REVISION, cycleStart, cycleEnd, actorEmail);

        assertNotNull(result);
        assertEquals(employeeId, result.getEmployeeId());
        assertEquals(payGroupId, result.getPayGroupId());
        assertEquals(0, new BigDecimal("1500.00").compareTo(result.getAmount()));
        assertEquals(ArrearsSourceType.SALARY_REVISION, result.getSourceType());
        assertEquals(actorId, result.getCreatedBy());
        verify(auditService).log(eq(actorId), eq("ARREARS_CREATED"), any(), isNull(), any());
    }

    @Test
    void createPending_negativeAmount_isStillSaved_asARecovery() {
        Arrears result = service.createPending(employeeId, payGroupId, new BigDecimal("-500.00"), "LOP reversal recovery",
                ArrearsSourceType.LOP_REVERSAL, cycleStart, cycleEnd, actorEmail);

        assertNotNull(result);
        assertEquals(0, new BigDecimal("-500.00").compareTo(result.getAmount()));
        verify(arrearsRepository).save(any(Arrears.class));
    }

    @Test
    void findPendingFor_delegatesToRepositoryWithPendingStatus() {
        Arrears pending = Arrears.builder().id(UUID.randomUUID()).employeeId(employeeId).status(ArrearsStatus.PENDING).build();
        when(arrearsRepository.findByEmployeeIdAndStatus(employeeId, ArrearsStatus.PENDING)).thenReturn(List.of(pending));

        List<Arrears> result = service.findPendingFor(employeeId);

        assertEquals(List.of(pending), result);
    }

    @Test
    void markApplied_setsStatusAndRunId_andSaves() {
        UUID runId = UUID.randomUUID();
        Arrears row = Arrears.builder().id(UUID.randomUUID()).employeeId(employeeId).status(ArrearsStatus.PENDING).build();

        service.markApplied(row, runId);

        assertEquals(ArrearsStatus.APPLIED, row.getStatus());
        assertEquals(runId, row.getAppliedToRunId());
        verify(arrearsRepository).save(row);
    }

    @Test
    void listForRun_noPayslips_returnsEmptyListWithoutQueryingArrears() {
        UUID runId = UUID.randomUUID();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of());

        List<ArrearsResponse> result = service.listForRun(runId);

        assertTrue(result.isEmpty());
        verifyNoInteractions(arrearsRepository);
    }

    @Test
    void listForRun_resolvesEmployeeNamesAndSortsNewestFirst() {
        UUID runId = UUID.randomUUID();
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(employeeId).build();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip));

        Employee employee = Employee.builder().userId(employeeId).fullName("Chandu Shyam").build();
        when(employeeRepository.findAllByIdWithDepartment(List.of(employeeId))).thenReturn(List.of(employee));

        Arrears older = Arrears.builder().id(UUID.randomUUID()).employeeId(employeeId).amount(new BigDecimal("100"))
                .reason("older").sourceType(ArrearsSourceType.LOP_REVERSAL).status(ArrearsStatus.APPLIED)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        Arrears newer = Arrears.builder().id(UUID.randomUUID()).employeeId(employeeId).amount(new BigDecimal("200"))
                .reason("newer").sourceType(ArrearsSourceType.SALARY_REVISION).status(ArrearsStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 6, 1, 0, 0)).build();
        when(arrearsRepository.findByEmployeeIdIn(List.of(employeeId))).thenReturn(List.of(older, newer));

        List<ArrearsResponse> result = service.listForRun(runId);

        assertEquals(2, result.size());
        assertEquals(newer.getId(), result.get(0).getId());
        assertEquals(older.getId(), result.get(1).getId());
        assertEquals("Chandu Shyam", result.get(0).getEmployeeName());
    }

    @Test
    void listForRun_unknownEmployeeName_fallsBackToUnknown() {
        UUID runId = UUID.randomUUID();
        Payslip payslip = Payslip.builder().id(UUID.randomUUID()).employeeId(employeeId).build();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip));
        when(employeeRepository.findAllByIdWithDepartment(List.of(employeeId))).thenReturn(List.of());

        Arrears row = Arrears.builder().id(UUID.randomUUID()).employeeId(employeeId).amount(BigDecimal.TEN)
                .reason("r").sourceType(ArrearsSourceType.HOLD_RELEASE).status(ArrearsStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
        when(arrearsRepository.findByEmployeeIdIn(List.of(employeeId))).thenReturn(List.of(row));

        List<ArrearsResponse> result = service.listForRun(runId);

        assertEquals("Unknown", result.get(0).getEmployeeName());
    }
}
