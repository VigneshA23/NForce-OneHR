package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryHoldRequest;
import com.nforce.onehr.dto.payroll.SalaryHoldResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class SalaryHoldServiceTest {

    @Mock private SalaryHoldRepository salaryHoldRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private SalaryHoldService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID runId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SalaryHoldService(salaryHoldRepository, payrollRunRepository, employeeRepository, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.existsById(employeeId)).thenReturn(true);
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());

        lenient().when(salaryHoldRepository.save(any(SalaryHold.class))).thenAnswer(inv -> {
            SalaryHold h = inv.getArgument(0);
            if (h.getId() == null) h.setId(UUID.randomUUID());
            return h;
        });
    }

    private PayrollRun draftRun() {
        return PayrollRun.builder().id(runId).status(PayrollRunStatus.DRAFT).build();
    }

    private CreateSalaryHoldRequest req() {
        CreateSalaryHoldRequest r = new CreateSalaryHoldRequest();
        r.setEmployeeId(employeeId);
        r.setReason("Under disciplinary review");
        return r;
    }

    @Test
    void create_validHold_succeeds() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(salaryHoldRepository.existsByPayrollRunIdAndEmployeeIdAndStatus(runId, employeeId, SalaryHoldStatus.ACTIVE)).thenReturn(false);

        SalaryHoldResponse resp = service.create(runId, req(), actorEmail);

        assertEquals(SalaryHoldStatus.ACTIVE, resp.getStatus());
        assertEquals(employeeId, resp.getEmployeeId());
        verify(auditService).log(eq(actorId), eq("PAYROLL_SALARY_HOLD_CREATED"), any(), isNull(), any());
    }

    @Test
    void create_runNotDraft_rejected() {
        PayrollRun submitted = PayrollRun.builder().id(runId).status(PayrollRunStatus.SUBMITTED).build();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(submitted));

        assertThrows(IllegalStateException.class, () -> service.create(runId, req(), actorEmail));
    }

    @Test
    void create_employeeNotFound_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(employeeRepository.existsById(employeeId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.create(runId, req(), actorEmail));
    }

    @Test
    void create_duplicateActiveHold_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        when(salaryHoldRepository.existsByPayrollRunIdAndEmployeeIdAndStatus(runId, employeeId, SalaryHoldStatus.ACTIVE)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.create(runId, req(), actorEmail));
    }

    @Test
    void release_activeHold_succeeds() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        UUID holdId = UUID.randomUUID();
        SalaryHold hold = SalaryHold.builder().id(holdId).payrollRun(draftRun()).employeeId(employeeId)
                .reason("test").status(SalaryHoldStatus.ACTIVE).createdBy(actorId).build();
        when(salaryHoldRepository.findById(holdId)).thenReturn(Optional.of(hold));

        SalaryHoldResponse resp = service.release(runId, holdId, actorEmail);

        assertEquals(SalaryHoldStatus.RELEASED, resp.getStatus());
        verify(auditService).log(eq(actorId), eq("PAYROLL_SALARY_HOLD_RELEASED"), any(), any(), any());
    }

    @Test
    void release_runNotDraft_rejected() {
        PayrollRun approved = PayrollRun.builder().id(runId).status(PayrollRunStatus.APPROVED).build();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(approved));

        assertThrows(IllegalStateException.class, () -> service.release(runId, UUID.randomUUID(), actorEmail));
    }

    @Test
    void release_holdBelongsToDifferentRun_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        UUID holdId = UUID.randomUUID();
        PayrollRun otherRun = PayrollRun.builder().id(UUID.randomUUID()).status(PayrollRunStatus.DRAFT).build();
        SalaryHold hold = SalaryHold.builder().id(holdId).payrollRun(otherRun).employeeId(employeeId)
                .reason("test").status(SalaryHoldStatus.ACTIVE).createdBy(actorId).build();
        when(salaryHoldRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThrows(IllegalArgumentException.class, () -> service.release(runId, holdId, actorEmail));
    }

    @Test
    void release_alreadyReleased_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        UUID holdId = UUID.randomUUID();
        SalaryHold hold = SalaryHold.builder().id(holdId).payrollRun(draftRun()).employeeId(employeeId)
                .reason("test").status(SalaryHoldStatus.RELEASED).createdBy(actorId).build();
        when(salaryHoldRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThrows(IllegalStateException.class, () -> service.release(runId, holdId, actorEmail));
    }

    @Test
    void list_returnsAllForRun() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(draftRun()));
        SalaryHold hold = SalaryHold.builder().id(UUID.randomUUID()).payrollRun(draftRun()).employeeId(employeeId)
                .reason("test").status(SalaryHoldStatus.ACTIVE).createdBy(actorId).build();
        when(salaryHoldRepository.findByPayrollRunId(runId)).thenReturn(List.of(hold));

        List<SalaryHoldResponse> result = service.list(runId);

        assertEquals(1, result.size());
    }

    @Test
    void list_unknownRun_throwsNoSuchElement() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.list(runId));
    }
}
