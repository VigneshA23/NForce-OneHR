package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExpenseClaim;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ExpenseClaimRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditTargetResolverTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private ExpenseClaimRepository expenseClaimRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private WebClockInRequestRepository webClockInRequestRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;

    @InjectMocks private AuditTargetResolver resolver;

    @Test
    void resolve_targetIdNull_returnsDash() {
        assertEquals("—", resolver.resolve("EMPLOYEE_UPDATED", null));
    }

    @Test
    void resolve_employeeAction_resolvesEmployeeName() {
        UUID targetId = UUID.randomUUID();
        when(employeeRepository.findById(targetId)).thenReturn(Optional.of(
                Employee.builder().userId(targetId).fullName("Vikram Rao").build()));

        assertEquals("Vikram Rao", resolver.resolve("EMPLOYEE_UPDATED", targetId));
    }

    @Test
    void resolve_leaveRequestAction_resolvesOwningEmployeeName() {
        UUID targetId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(leaveRequestRepository.findById(targetId)).thenReturn(Optional.of(
                LeaveRequest.builder().id(targetId).employeeUserId(employeeId).build()));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).fullName("Neha Joshi").build()));

        assertEquals("Leave: Neha Joshi", resolver.resolve("LEAVE_REQUEST_APPROVED", targetId));
    }

    @Test
    void resolve_expenseAction_resolvesOwningEmployeeName() {
        UUID targetId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(expenseClaimRepository.findById(targetId)).thenReturn(Optional.of(
                ExpenseClaim.builder().id(targetId).employeeUserId(employeeId).build()));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).fullName("Arjun Mehta").build()));

        assertEquals("Expense: Arjun Mehta", resolver.resolve("EXPENSE_FINAL_APPROVED", targetId));
    }

    @Test
    void resolve_assetAssignedAction_resolvesRecipientDirectly() {
        // AssetService passes the recipient employee's own id as target_id (Asset/AssetAssignment
        // use Long primary keys that can't fit AuditService.log's UUID target slot) — target_id
        // IS the affected employee's id directly here, same convention as EMPLOYEE_*/USER_*.
        UUID targetId = UUID.randomUUID();
        when(employeeRepository.findById(targetId)).thenReturn(Optional.empty());
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
                User.builder().id(targetId).email("priya.nair@nforceone.com").build()));

        assertEquals("priya.nair@nforceone.com", resolver.resolve("ASSET_ASSIGNED", targetId));
    }

    @Test
    void resolve_assetCreatedAction_fallsBackToActor_noDistinctAffectedEmployee() {
        // ASSET_CREATED has no distinct affected employee — falls back to resolving target_id
        // (the actor's own id at that call site) directly, which is the correct/intended fallback.
        UUID actorAsTargetId = UUID.randomUUID();
        when(employeeRepository.findById(actorAsTargetId)).thenReturn(Optional.of(
                Employee.builder().userId(actorAsTargetId).fullName("Priya Nair").build()));

        assertEquals("Priya Nair", resolver.resolve("ASSET_CREATED", actorAsTargetId));
    }

    @Test
    void resolve_regularizationApprovedAction_resolvesEmployeeDirectlyWithoutRequestLookup() {
        // RegularizationService.approve/reject pass the affected employee's own id as target_id
        // (not the RegularizationRequest.id) — must resolve directly, no repository lookup.
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).fullName("Karan Shah").build()));

        assertEquals("Karan Shah", resolver.resolve("REGULARIZATION_APPROVED", employeeId));
        verifyNoInteractions(regularizationRequestRepository);
    }

    @Test
    void resolve_webClockInApprovedAction_resolvesEmployeeDirectlyWithoutRequestLookup() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).fullName("Divya Iyer").build()));

        assertEquals("Divya Iyer", resolver.resolve("WEB_CLOCK_IN_APPROVED", employeeId));
        verifyNoInteractions(webClockInRequestRepository);
    }

    @Test
    void resolveEmployeeCode_leaveRequestAction_returnsAffectedEmployeesCode() {
        UUID targetId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(leaveRequestRepository.findById(targetId)).thenReturn(Optional.of(
                LeaveRequest.builder().id(targetId).employeeUserId(employeeId).build()));
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(
                Employee.builder().userId(employeeId).employeeCode("NF-00042").build()));

        assertEquals("NF-00042", resolver.resolveEmployeeCode("LEAVE_REQUEST_APPROVED", targetId));
    }

    @Test
    void resolveEmployeeCode_targetIdNull_returnsEmptyString() {
        assertEquals("", resolver.resolveEmployeeCode("EMPLOYEE_UPDATED", null));
    }

    @Test
    void resolveEmployeeCode_noEmployeeRecord_returnsEmptyStringNeverUuid() {
        UUID targetId = UUID.randomUUID();
        when(employeeRepository.findById(targetId)).thenReturn(Optional.empty());

        String code = resolver.resolveEmployeeCode("ASSET_CREATED", targetId);

        assertEquals("", code);
    }

    @Test
    void resolve_unresolvableTarget_fallsBackToShortenedId() {
        UUID targetId = UUID.randomUUID();
        when(employeeRepository.findById(targetId)).thenReturn(Optional.empty());
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        String label = resolver.resolve("LOGIN_FAILED", targetId);

        assertTrue(label.endsWith("…"));
        assertNotNull(label);
    }

    @Test
    void resolve_repositoryThrows_returnsShortIdWithoutPropagating() {
        UUID targetId = UUID.randomUUID();
        when(leaveRequestRepository.findById(targetId)).thenThrow(new RuntimeException("db down"));

        String label = resolver.resolve("LEAVE_REQUEST_APPROVED", targetId);

        assertTrue(label.endsWith("…"));
    }
}
