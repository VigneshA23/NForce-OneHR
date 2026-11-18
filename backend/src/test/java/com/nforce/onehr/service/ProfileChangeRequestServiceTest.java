package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.ProfileChangeRequestResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ProfileChangeRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProfileChangeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The approval gate for an employee's own bank / government-ID edit. */
@ExtendWith(MockitoExtension.class)
class ProfileChangeRequestServiceTest {

    @Mock private ProfileChangeRequestRepository requestRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Spy private AuditSnapshotSerializer auditSnapshot = new AuditSnapshotSerializer(new ObjectMapper());
    @Mock private NotificationService notificationService;
    @Mock private EffectivePermissionService effectivePermissionService;
    @InjectMocks private ProfileChangeRequestService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();
    private User employee;
    private User approver;
    private Employee emp;

    @BeforeEach
    void setUp() {
        employee = User.builder().id(employeeId).email("emp@test.com").build();
        approver = User.builder().id(approverId).email("hr@test.com").build();
        emp = Employee.builder().fullName("Pat Employee").bankAccountNumber("111122223333")
                .bankName("Old Bank").passportExpiry(LocalDate.of(2030, 1, 1)).build();
        // lenient: not every test touches every collaborator
        Mockito.lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(emp));
        Mockito.lenient().when(userRepository.findByEmail("emp@test.com")).thenReturn(Optional.of(employee));
        Mockito.lenient().when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(approver));
        Mockito.lenient().when(effectivePermissionService.hasAuthority(approver, ProfileChangeRequestService.APPROVE_AUTHORITY))
                .thenReturn(true);
    }

    private ProfileChangeRequest pending(Map<String, String> oldV, Map<String, String> newV) {
        ProfileChangeRequest r = ProfileChangeRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .oldValues(oldV).newValues(newV).status("PENDING").build();
        when(requestRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void submit_queuesTheChange_auditsFieldNamesOnly_andNotifiesApproversButNotTheRequester() {
        when(requestRepository.findByEmployeeUserIdAndStatus(employeeId, "PENDING")).thenReturn(Optional.empty());
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(effectivePermissionService.activeUserIdsWithAuthority("employee_sensitive", "APPROVE", "ALL"))
                .thenReturn(Set.of(approverId, employeeId));

        service.submit(employeeId, map("bankAccountNumber", "111122223333"), map("bankAccountNumber", "999988887777"));

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(employeeId), eq("PROFILE_SENSITIVE_CHANGE_REQUESTED"), eq(employeeId), isNull(), after.capture());
        assertTrue(after.getValue().contains("bankAccountNumber"));
        assertFalse(after.getValue().contains("999988887777"));
        verify(notificationService).send(eq(approverId), eq("PROFILE_CHANGE_SUBMITTED"), any(), any(), any());
        verify(notificationService, never()).send(eq(employeeId), eq("PROFILE_CHANGE_SUBMITTED"), any(), any(), any());
        verify(employeeRepository, never()).save(any()); // nothing is written to the employee yet
    }

    @Test
    void submit_replacesAnExistingPendingRequestInsteadOfStackingAnother() {
        ProfileChangeRequest existing = ProfileChangeRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .oldValues(map("bankName", "A")).newValues(map("bankName", "B")).status("PENDING").build();
        when(requestRepository.findByEmployeeUserIdAndStatus(employeeId, "PENDING")).thenReturn(Optional.of(existing));
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(effectivePermissionService.activeUserIdsWithAuthority(any(), any(), any())).thenReturn(Set.of());

        service.submit(employeeId, map("bankName", "A"), map("bankName", "C"));

        assertEquals("C", existing.getNewValues().get("bankName"));
        verify(requestRepository).save(existing);
    }

    @Test
    void approve_appliesTheValuesToTheEmployee_marksItApproved_andNotifiesTheRequester() {
        ProfileChangeRequest r = pending(map("bankAccountNumber", "111122223333", "passportExpiry", "2030-01-01"),
                map("bankAccountNumber", "999988887777", "passportExpiry", null));

        service.approve(r.getId(), "ok", "hr@test.com");

        assertEquals("999988887777", emp.getBankAccountNumber());
        assertNull(emp.getPassportExpiry()); // a null value clears the field
        assertEquals("APPROVED", r.getStatus());
        assertEquals(approverId, r.getReviewedBy());
        verify(employeeRepository).save(emp);
        verify(auditService).log(eq(approverId), eq("PROFILE_SENSITIVE_CHANGE_APPROVED"), eq(employeeId), isNull(), any());
        verify(notificationService).send(eq(employeeId), eq("PROFILE_CHANGE_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_leavesTheEmployeeUntouched_andTellsTheRequesterWhy() {
        ProfileChangeRequest r = pending(map("bankAccountNumber", "111122223333"), map("bankAccountNumber", "999988887777"));

        service.reject(r.getId(), "Wrong account", "hr@test.com");

        assertEquals("111122223333", emp.getBankAccountNumber());
        assertEquals("REJECTED", r.getStatus());
        verify(employeeRepository, never()).save(any());
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(eq(employeeId), eq("PROFILE_CHANGE_REJECTED"), any(), msg.capture(), any());
        assertTrue(msg.getValue().contains("Wrong account"));
    }

    @Test
    void youCannotDecideYourOwnChange_evenWithTheApprovePermission() {
        when(effectivePermissionService.hasAuthority(employee, ProfileChangeRequestService.APPROVE_AUTHORITY)).thenReturn(true);
        ProfileChangeRequest r = pending(map("bankName", "Old Bank"), map("bankName", "New Bank"));

        assertThrows(AccessDeniedException.class, () -> service.approve(r.getId(), null, "emp@test.com"));

        assertEquals("Old Bank", emp.getBankName());
        assertEquals("PENDING", r.getStatus());
    }

    @Test
    void withoutTheApprovePermission_nothingCanBeDecidedOrListed() {
        User nobody = User.builder().id(UUID.randomUUID()).email("nobody@test.com").build();
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.of(nobody));
        when(effectivePermissionService.hasAuthority(nobody, ProfileChangeRequestService.APPROVE_AUTHORITY)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.listPending("nobody@test.com"));
        assertThrows(AccessDeniedException.class, () -> service.approve(UUID.randomUUID(), null, "nobody@test.com"));
    }

    @Test
    void pendingList_masksIds_andHidesTheApproversOwnRequest() {
        ProfileChangeRequest others = ProfileChangeRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .oldValues(map("bankAccountNumber", "111122223333", "bankName", "Old Bank"))
                .newValues(map("bankAccountNumber", "999988887777", "bankName", "New Bank")).status("PENDING").build();
        ProfileChangeRequest own = ProfileChangeRequest.builder().id(UUID.randomUUID()).employeeUserId(approverId)
                .oldValues(map("bankName", "x")).newValues(map("bankName", "y")).status("PENDING").build();
        when(requestRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(others, own));

        List<ProfileChangeRequestResponse> list = service.listPending("hr@test.com");

        assertEquals(1, list.size());
        assertFalse(list.get(0).isUnmasked());
        assertEquals("XXXX-7777", list.get(0).getFields().get(0).getNewValue());
        assertEquals("XXXX-3333", list.get(0).getFields().get(0).getOldValue());
        assertEquals("New Bank", list.get(0).getFields().get(1).getNewValue()); // not an identifier: shown as is
    }

    @Test
    void detail_showsRawValues_andIsAuditedAsAnUnmaskedView() {
        ProfileChangeRequest r = pending(map("bankAccountNumber", "111122223333"), map("bankAccountNumber", "999988887777"));

        ProfileChangeRequestResponse res = service.detail(r.getId(), "hr@test.com");

        assertTrue(res.isUnmasked());
        assertEquals("999988887777", res.getFields().get(0).getNewValue());
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(approverId), eq("USER_SENSITIVE_DATA_VIEWED"), eq(employeeId), isNull(), after.capture());
        assertFalse(after.getValue().contains("999988887777")); // the trail names fields, never values
    }

    @Test
    void anAlreadyDecidedRequestCannotBeDecidedAgain() {
        ProfileChangeRequest r = pending(map("bankName", "A"), map("bankName", "B"));
        r.setStatus("APPROVED");

        assertThrows(IllegalArgumentException.class, () -> service.approve(r.getId(), null, "hr@test.com"));
    }
}
