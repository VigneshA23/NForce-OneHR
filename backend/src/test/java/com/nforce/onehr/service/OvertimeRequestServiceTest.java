package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateOvertimeRequest;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.OvertimeRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ONEHR-140: approve()/reject() must notify the original requester exactly once per decision,
 * never the approver, and never twice for the same request.
 */
@ExtendWith(MockitoExtension.class)
class OvertimeRequestServiceTest {

    @Mock private OvertimeRequestRepository requestRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    // The real seed, plus custom roles: one granted overtime:APPROVE at TEAM, one at ALL, and one
    // granted only an unrelated module's APPROVE.
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_TEAM", Map.of("overtime:APPROVE", "TEAM"),
            "CUSTOM_ALL", Map.of("overtime:APPROVE", "ALL"),
            "CUSTOM_OTHER", Map.of("leave:APPROVE", "ALL")));

    @InjectMocks private OvertimeRequestService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String hrAdminEmail = "hr@test.com";
    private final String employeeEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        Role hrRole = Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build();
        User hrUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        lenient().when(userRepository.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrUser));
        Role employeeRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        lenient().when(requestRepository.save(any(OvertimeRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    private CreateOvertimeRequest overtimeRequest(LocalDate workDate) {
        return CreateOvertimeRequest.builder()
                .workDate(workDate)
                .requestedStart(workDate.atTime(18, 0))
                .requestedEnd(workDate.atTime(20, 0))
                .reason("Release deployment")
                .build();
    }

    private OvertimeRequest pendingRequest() {
        return OvertimeRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(LocalDate.of(2026, 8, 10))
                .requestedStart(LocalDateTime.of(2026, 8, 10, 18, 0))
                .requestedEnd(LocalDateTime.of(2026, 8, 10, 20, 0))
                .reason("Release deployment")
                .status("PENDING")
                .build();
    }

    // ---------------------------------------------------------------- submit / joining-date boundary

    @Test
    void submit_rejectsWorkDateBeforeJoiningDate() {
        Employee employee = Employee.builder().userId(employeeId).joiningDate(LocalDate.of(2026, 8, 15)).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(overtimeRequest(LocalDate.of(2026, 8, 10)), employeeEmail));
        assertTrue(ex.getMessage().contains("Overtime requests cannot be made prior to your joining date"));
        verify(requestRepository, never()).save(any());
    }

    @Test
    void submit_allowsPastWorkDateOnOrAfterJoiningDate() {
        Employee employee = Employee.builder().userId(employeeId).joiningDate(LocalDate.of(2026, 8, 1)).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        OvertimeRequestResponse resp = service.submit(overtimeRequest(LocalDate.of(2026, 8, 5)), employeeEmail);

        assertEquals(LocalDate.of(2026, 8, 5), resp.getWorkDate());
    }

    @Test
    void approve_notifiesOriginalRequester_notApprover() {
        OvertimeRequest req = pendingRequest();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));

        OvertimeRequestResponse resp = service.approve(req.getId(), "Approved", hrAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("OVERTIME_APPROVED"), any(), any(), any());
        verify(notificationService, never()).send(eq(hrAdminId), any(), any(), any(), any());
    }

    @Test
    void reject_notifiesOriginalRequesterWithReason() {
        OvertimeRequest req = pendingRequest();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));

        OvertimeRequestResponse resp = service.reject(req.getId(), "Budget frozen this month", hrAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("OVERTIME_REJECTED"), any(),
                contains("Budget frozen this month"), any());
    }

    @Test
    void reject_withNoComment_stillNotifiesWithoutThrowing() {
        OvertimeRequest req = pendingRequest();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));

        assertDoesNotThrow(() -> service.reject(req.getId(), null, hrAdminEmail));
        verify(notificationService, times(1)).send(eq(employeeId), eq("OVERTIME_REJECTED"), any(), any(), any());
    }

    @Test
    void approve_calledTwice_sendsNotificationOnlyOnce() {
        OvertimeRequest req = pendingRequest();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));

        service.approve(req.getId(), null, hrAdminEmail);
        assertThrows(IllegalArgumentException.class, () -> service.approve(req.getId(), null, hrAdminEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("OVERTIME_APPROVED"), any(), any(), any());
    }

    @Test
    void approve_byUnauthorizedActor_sendsNoNotification() {
        OvertimeRequest req = pendingRequest();
        req.setAssignedApproverId(UUID.randomUUID());
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        Role managerRole = Role.builder().id(2).code("MANAGER").displayName("Manager").build();
        User strangerManager = User.builder().id(UUID.randomUUID()).email("stranger@test.com").roles(Set.of(managerRole)).build();
        when(userRepository.findByEmail("stranger@test.com")).thenReturn(Optional.of(strangerManager));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> service.approve(req.getId(), null, "stranger@test.com"));
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- permission-based access (custom roles)

    private static final List<String> CUSTOM = List.of("CUSTOM_TEAM", "CUSTOM_ALL", "CUSTOM_OTHER");

    /** "SUPER_ADMIN+MANAGER" -> those roles: system roles as seeded, custom roles with their own ids. */
    private static Set<Role> roles(String codes) {
        return Arrays.stream(codes.split("\\+"))
                .map(c -> CUSTOM.contains(c)
                        ? Role.builder().id(100 + CUSTOM.indexOf(c)).code(c).displayName(c).build()
                        : SeedMatrix.role(c))
                .collect(Collectors.toSet());
    }

    private User actorWith(String codes) {
        User actor = User.builder().id(UUID.randomUUID()).email("actor@test.com").roles(roles(codes)).build();
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(actor));
        return actor;
    }

    private void assertReview(boolean allowed, OvertimeRequest req) {
        if (allowed) {
            assertEquals("APPROVED", service.approve(req.getId(), null, "actor@test.com").getStatus());
        } else {
            assertThrows(AccessDeniedException.class, () -> service.approve(req.getId(), null, "actor@test.com"));
        }
    }

    @ParameterizedTest
    @CsvSource({"HR_ADMIN,2", "SUPER_ADMIN,2", "CUSTOM_ALL,2", "MANAGER,1", "CUSTOM_TEAM,1", "CUSTOM_OTHER,1", "EMPLOYEE,1"})
    void listPendingForApprover_everyPendingRequestOnlyForApproveAllHolders(String codes, int expected) {
        User actor = actorWith(codes);
        OvertimeRequest assignedToActor = pendingRequest();
        assignedToActor.setAssignedApproverId(actor.getId());
        when(requestRepository.findByStatus("PENDING")).thenReturn(List.of(assignedToActor, pendingRequest()));

        assertEquals(expected, service.listPendingForApprover("actor@test.com").size());
    }

    @ParameterizedTest
    @CsvSource({"HR_ADMIN,true", "SUPER_ADMIN,true", "CUSTOM_ALL,true", "MANAGER,false", "CUSTOM_TEAM,false", "EMPLOYEE,false"})
    void approve_unrelatedRequest_onlyApproveAllHoldersMayReview(String codes, boolean allowed) {
        actorWith(codes);
        OvertimeRequest req = pendingRequest(); // assigned to someone else; actor isn't the requester's manager
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertReview(allowed, req);
    }

    @ParameterizedTest
    @CsvSource({"MANAGER,true", "CUSTOM_TEAM,true", "CUSTOM_OTHER,false", "EMPLOYEE,false"})
    void approve_asRequestersCurrentManager_needsApproveTeam(String codes, boolean allowed) {
        User actor = actorWith(codes);
        OvertimeRequest req = pendingRequest();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(actor.getId()).build()));

        assertReview(allowed, req);
    }

    @ParameterizedTest
    @CsvSource({"MANAGER,true", "HR_ADMIN,true", "SUPER_ADMIN,false", "EMPLOYEE,false", "SUPER_ADMIN+MANAGER,true",
            "CUSTOM_TEAM,true", "CUSTOM_ALL,true", "CUSTOM_OTHER,false", "SUPER_ADMIN+CUSTOM_TEAM,true"})
    void submit_explicitApprover_mustHoldApproveTeamOutsideSuperAdmin(String codes, boolean eligible) {
        User candidate = User.builder().id(UUID.randomUUID()).email("approver@test.com").roles(roles(codes)).build();
        when(userRepository.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        CreateOvertimeRequest req = overtimeRequest(LocalDate.of(2026, 8, 10));
        req.setManagerUserId(candidate.getId());

        if (eligible) {
            assertEquals(candidate.getId(), service.submit(req, employeeEmail).getAssignedApproverId());
        } else {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
            assertEquals("Selected user is not an eligible approver", ex.getMessage());
        }
    }
}
