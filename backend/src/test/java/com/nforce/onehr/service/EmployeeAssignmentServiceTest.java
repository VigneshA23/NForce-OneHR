package com.nforce.onehr.service;

import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ONEHR-108 bulk-update scoping — every write re-verifies the target is still a *current*
 * direct report at call time (mirrors LeaveService's approve/reject re-verify pattern), and
 * one employee's failure never blocks another's in the same batch.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeAssignmentServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;

    @InjectMocks private EmployeeAssignmentService service;

    private final UUID managerId = UUID.randomUUID();
    private final UUID directReportId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    @BeforeEach
    void setUp() {
        Employee manager = Employee.builder().userId(managerId).fullName("Manager").build();
        // lenient: the "policy not found" test throws before either of these is reached.
        lenient().when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(directReportId));
    }

    @Test
    void bulkUpdateShift_succeeds_forCurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").build();
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One").build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(employee));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(directReportId), shiftId);

        assertEquals(1, result.getSucceededIds().size());
        assertTrue(result.getFailed().isEmpty());
        assertEquals(shift, employee.getShift());
        verify(employeeRepository).save(employee);
    }

    @Test
    void bulkUpdateShift_fails_forEmployeeNotACurrentDirectReport() {
        UUID shiftId = UUID.randomUUID();
        when(shiftRepository.findById(shiftId))
                .thenReturn(Optional.of(Shift.builder().id(shiftId).name("Regular Shift").build()));

        AssignmentBulkResultResponse result = service.bulkUpdateShift(managerEmail, List.of(strangerId), shiftId);

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
        verify(employeeRepository, never()).findById(strangerId);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void bulkUpdateShift_isPartial_whenOneOfTwoEmployeesIsNotADirectReport() {
        UUID shiftId = UUID.randomUUID();
        Shift shift = Shift.builder().id(shiftId).name("Regular Shift").build();
        Employee employee = Employee.builder().userId(directReportId).fullName("Report One").build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(employeeRepository.findById(directReportId)).thenReturn(Optional.of(employee));

        AssignmentBulkResultResponse result =
                service.bulkUpdateShift(managerEmail, List.of(directReportId, strangerId), shiftId);

        assertEquals(1, result.getSucceededIds().size());
        assertEquals(directReportId, result.getSucceededIds().get(0));
        assertEquals(1, result.getFailed().size());
        assertEquals(strangerId, result.getFailed().get(0).getEmployeeUserId());
    }

    @Test
    void bulkUpdateShift_throws_whenShiftDoesNotExist() {
        UUID unknownShiftId = UUID.randomUUID();
        when(shiftRepository.findById(unknownShiftId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.bulkUpdateShift(managerEmail, List.of(directReportId), unknownShiftId));
        verify(employeeRepository, never()).findById(any());
    }
}
