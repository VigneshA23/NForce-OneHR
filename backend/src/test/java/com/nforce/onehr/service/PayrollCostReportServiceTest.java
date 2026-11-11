package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollCostReportResponse;
import com.nforce.onehr.dto.payroll.PayrollCostReportResponse.DepartmentCost;
import com.nforce.onehr.dto.payroll.PayrollCostReportResponse.ProjectCost;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * US-C16: cost report scoping (only APPROVED/DISBURSED runs), the exact department split, the
 * project-allocation proration (including the "no allocations anywhere" degrade-gracefully case),
 * and that every split ties back to the run's own totals.
 */
@ExtendWith(MockitoExtension.class)
class PayrollCostReportServiceTest {

    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private PayslipRepository payslipRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectAllocationRepository projectAllocationRepository;
    @Mock private ProjectRepository projectRepository;

    private PayrollCostReportService service;

    private final UUID runId = UUID.randomUUID();
    private final LocalDate cycleStart = LocalDate.of(2026, 10, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 10, 31);

    @BeforeEach
    void setUp() {
        service = new PayrollCostReportService(
                payrollRunRepository, payslipRepository, employeeRepository, projectAllocationRepository, projectRepository);
    }

    private PayrollRun run(String status) {
        return PayrollRun.builder().id(runId).cycleStart(cycleStart).cycleEnd(cycleEnd).status(status).build();
    }

    private Payslip payslip(UUID employeeId, String gross, String deductions, String net) {
        return Payslip.builder().employeeId(employeeId)
                .ctc(BigDecimal.ZERO).cycleDays(31).lopDays(BigDecimal.ZERO).employedDays(31).prorationFactor(BigDecimal.ONE)
                .grossPay(new BigDecimal(gross)).totalDeductions(new BigDecimal(deductions)).netPay(new BigDecimal(net))
                .build();
    }

    private Employee employeeIn(UUID id, Department department) {
        Employee e = new Employee();
        e.setUserId(id);
        e.setDepartment(department);
        return e;
    }

    private Department department(String name) {
        return Department.builder().id(UUID.randomUUID()).name(name).build();
    }

    private ProjectAllocation allocation(UUID employeeId, UUID projectId, int capacityPercent, LocalDate start, LocalDate end) {
        return ProjectAllocation.builder().employeeUserId(employeeId).projectId(projectId)
                .capacityPercent(capacityPercent).startDate(start).endDate(end).build();
    }

    // ── run status gate ──

    @Test
    void runNotFound_throwsNoSuchElement() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.buildReport(runId));
    }

    @Test
    void draftRun_isRejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.DRAFT)));

        assertThrows(IllegalStateException.class, () -> service.buildReport(runId));
        verifyNoInteractions(payslipRepository);
    }

    @Test
    void submittedRun_isRejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.SUBMITTED)));

        assertThrows(IllegalStateException.class, () -> service.buildReport(runId));
    }

    @Test
    void approvedRun_isReportable() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of());
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of());
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.buildReport(runId));
    }

    @Test
    void disbursedRun_isReportable() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.DISBURSED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of());
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of());
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.buildReport(runId));
    }

    // ── department split ──

    @Test
    void splitsByDepartment_andBucketsNoDepartmentAsUnassigned() {
        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();
        UUID emp3 = UUID.randomUUID();
        Department engineering = department("Engineering");

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(
                payslip(emp1, "1000.00", "100.00", "900.00"),
                payslip(emp2, "2000.00", "200.00", "1800.00"),
                payslip(emp3, "500.00", "50.00", "450.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of(
                employeeIn(emp1, engineering), employeeIn(emp2, engineering), employeeIn(emp3, null)));
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of());
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(2, report.byDepartment().size());
        DepartmentCost eng = report.byDepartment().stream().filter(d -> "Engineering".equals(d.departmentName())).findFirst().orElseThrow();
        assertEquals(2, eng.employeeCount());
        assertEquals(0, new BigDecimal("3000.00").compareTo(eng.grossPay()));
        assertEquals(0, new BigDecimal("2700.00").compareTo(eng.netPay()));

        DepartmentCost unassigned = report.byDepartment().stream().filter(d -> d.departmentId() == null).findFirst().orElseThrow();
        assertEquals("Unassigned", unassigned.departmentName());
        assertEquals(1, unassigned.employeeCount());
        assertEquals(0, new BigDecimal("500.00").compareTo(unassigned.grossPay()));
    }

    @Test
    void runTotals_equalTheSumOfAllPayslips() {
        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(
                payslip(emp1, "1000.00", "100.00", "900.00"),
                payslip(emp2, "2000.00", "200.00", "1800.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of());
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(2, report.employeeCount());
        assertEquals(0, new BigDecimal("3000.00").compareTo(report.grossPay()));
        assertEquals(0, new BigDecimal("300.00").compareTo(report.totalDeductions()));
        assertEquals(0, new BigDecimal("2700.00").compareTo(report.netPay()));
    }

    // ── project split / degrade-gracefully ──

    @Test
    void noAllocationsAnywhere_entireRunLandsInUnallocated() {
        UUID emp1 = UUID.randomUUID();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip(emp1, "1000.00", "100.00", "900.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of());
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(1, report.byProject().size());
        ProjectCost unallocated = report.byProject().get(0);
        assertNull(unallocated.projectId());
        assertEquals("Unallocated", unallocated.projectName());
        assertEquals(0, new BigDecimal("1000.00").compareTo(unallocated.grossPay()));
        assertEquals(0, new BigDecimal("900.00").compareTo(unallocated.netPay()));
    }

    @Test
    void fullyAllocatedEmployee_contributesNothingToUnallocated() {
        UUID emp1 = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        Project pa = Project.builder().id(projectA).code("PA").name("Project A").build();
        Project pb = Project.builder().id(projectB).code("PB").name("Project B").build();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip(emp1, "1000.00", "0.00", "1000.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of(
                allocation(emp1, projectA, 60, cycleStart, cycleEnd),
                allocation(emp1, projectB, 40, cycleStart, cycleEnd)));
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of(pa, pb));

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(3, report.byProject().size());
        ProjectCost costA = findProject(report, projectA);
        ProjectCost costB = findProject(report, projectB);
        ProjectCost unallocated = findUnallocated(report);
        assertEquals(0, new BigDecimal("600.00").compareTo(costA.grossPay()));
        assertEquals(0, new BigDecimal("400.00").compareTo(costB.grossPay()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(unallocated.grossPay()));
    }

    @Test
    void partiallyAllocatedEmployee_remainderGoesToUnallocated() {
        UUID emp1 = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        Project pa = Project.builder().id(projectA).code("PA").name("Project A").build();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip(emp1, "1000.00", "0.00", "1000.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of(
                allocation(emp1, projectA, 30, cycleStart, cycleEnd)));
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of(pa));

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(0, new BigDecimal("300.00").compareTo(findProject(report, projectA).grossPay()));
        assertEquals(0, new BigDecimal("700.00").compareTo(findUnallocated(report).grossPay()));
    }

    @Test
    void allocationOutsideTheRunsCycle_isIgnored() {
        UUID emp1 = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run(PayrollRunStatus.APPROVED)));
        when(payslipRepository.findByPayrollRunId(runId)).thenReturn(List.of(payslip(emp1, "1000.00", "0.00", "1000.00")));
        when(employeeRepository.findAllByIdWithDepartment(anyCollection())).thenReturn(List.of());
        // Allocation ended well before this run's cycle starts — no overlap.
        when(projectAllocationRepository.findByEmployeeUserIdIn(anyCollection())).thenReturn(List.of(
                allocation(emp1, projectA, 100, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))));
        when(projectRepository.findAllById(anyCollection())).thenReturn(List.of());

        PayrollCostReportResponse report = service.buildReport(runId);

        assertEquals(1, report.byProject().size());
        assertEquals("Unallocated", report.byProject().get(0).projectName());
        assertEquals(0, new BigDecimal("1000.00").compareTo(report.byProject().get(0).grossPay()));
    }

    private static ProjectCost findProject(PayrollCostReportResponse report, UUID projectId) {
        return report.byProject().stream().filter(p -> projectId.equals(p.projectId())).findFirst().orElseThrow();
    }

    private static ProjectCost findUnallocated(PayrollCostReportResponse report) {
        return report.byProject().stream().filter(p -> p.projectId() == null).findFirst().orElseThrow();
    }
}
