package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollJoinersExitsResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.PayslipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class PayrollJoinersExitsServiceTest {

    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks private PayrollJoinersExitsService service;

    private final UUID runId = UUID.randomUUID();
    private final LocalDate cycleStart = LocalDate.of(2026, 6, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28);

    private PayrollRun run() {
        return PayrollRun.builder().id(runId).cycleStart(cycleStart).cycleEnd(cycleEnd).build();
    }

    @Test
    void build_unknownRun_throwsNoSuchElement() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class, () -> service.build(runId));
    }

    @Test
    void build_noPayslips_queriesWithEmptyIdsAndReturnsEmptyLists() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of());
        when(employeeRepository.findJoinersInRange(List.of(), cycleStart, cycleEnd)).thenReturn(List.of());
        when(employeeRepository.findExitsInRange(List.of(), cycleStart, cycleEnd)).thenReturn(List.of());

        PayrollJoinersExitsResponse resp = service.build(runId);

        assertEquals(runId, resp.runId());
        assertEquals(cycleStart, resp.cycleStart());
        assertEquals(cycleEnd, resp.cycleEnd());
        assertTrue(resp.joiners().isEmpty());
        assertTrue(resp.exits().isEmpty());
    }

    @Test
    void build_resolvesJoinersAndExits_sortedByDateAscending() {
        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        Payslip p1 = Payslip.builder().id(UUID.randomUUID()).employeeId(emp1).build();
        Payslip p2 = Payslip.builder().id(UUID.randomUUID()).employeeId(emp2).build();
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(p1, p2));

        Employee laterJoiner = Employee.builder().userId(emp1).fullName("Later Joiner").joiningDate(LocalDate.of(2026, 6, 20)).build();
        Employee earlierJoiner = Employee.builder().userId(emp2).fullName("Earlier Joiner").joiningDate(LocalDate.of(2026, 6, 5)).build();
        when(employeeRepository.findJoinersInRange(anyList(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(List.of(laterJoiner, earlierJoiner));

        Employee exitEmp = Employee.builder().userId(emp1).fullName("Later Joiner").lastWorkingDay(LocalDate.of(2026, 6, 15)).build();
        when(employeeRepository.findExitsInRange(anyList(), eq(cycleStart), eq(cycleEnd))).thenReturn(List.of(exitEmp));

        PayrollJoinersExitsResponse resp = service.build(runId);

        assertEquals(2, resp.joiners().size());
        assertEquals(emp2, resp.joiners().get(0).employeeId()); // earlier joining date sorts first
        assertEquals(emp1, resp.joiners().get(1).employeeId());
        assertEquals(1, resp.exits().size());
        assertEquals(LocalDate.of(2026, 6, 15), resp.exits().get(0).date());
    }
}
