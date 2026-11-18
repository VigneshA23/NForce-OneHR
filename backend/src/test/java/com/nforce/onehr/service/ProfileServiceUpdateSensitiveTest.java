package com.nforce.onehr.service;

import com.nforce.onehr.dto.ProfileResponse;
import com.nforce.onehr.dto.UpdateProfileRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An employee editing their own profile: bank / government-ID changes queue for HR approval, everything else saves. */
@ExtendWith(MockitoExtension.class)
class ProfileServiceUpdateSensitiveTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceService attendanceService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private ProfileChangeRequestService profileChangeRequestService;
    @Mock private EffectivePermissionService effectivePermissionService;
    @InjectMocks private ProfileService profileService;

    private final UUID userId = UUID.randomUUID();
    private Employee emp;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(userId).email("emp@test.com").roles(Set.of(SeedMatrix.role("EMPLOYEE"))).build();
        emp = Employee.builder().user(user).fullName("Pat Employee").phone("1111111111")
                .bankAccountNumber("111122223333").bankName("Old Bank").bankIfsc("OLD0001234")
                .nationalId("ABCDE1234F").passportNumber("P1234567").passportExpiry(LocalDate.of(2030, 1, 1)).build();
        when(userRepository.findByEmail("emp@test.com")).thenReturn(Optional.of(user));
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(emp));
    }

    @Test
    void changedBankAndIdFields_areQueuedNotSaved_andOtherFieldsStillSaveImmediately() {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setPhone("2222222222");
        req.setBankAccountNumber("999988887777");
        req.setNationalId("ZZZZZ9999Z");
        req.setBankName("New Bank");

        profileService.updateProfile("emp@test.com", req);

        // the ordinary field saved straight away ...
        assertEquals("2222222222", emp.getPhone());
        // ... the sensitive ones did not touch the employee
        assertEquals("111122223333", emp.getBankAccountNumber());
        assertEquals("ABCDE1234F", emp.getNationalId());
        assertEquals("Old Bank", emp.getBankName());

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> oldV = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> newV = ArgumentCaptor.forClass(Map.class);
        verify(profileChangeRequestService).submit(eq(userId), oldV.capture(), newV.capture());
        assertEquals(Map.of("bankAccountNumber", "999988887777", "nationalId", "ZZZZZ9999Z", "bankName", "New Bank"), newV.getValue());
        assertEquals("111122223333", oldV.getValue().get("bankAccountNumber"));
    }

    @Test
    void resendingTheCurrentValues_createsNoRequest() {
        // the edit form resends passport/bank name/IFSC as they are; that must not look like a change
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setPassportNumber("P1234567");
        req.setPassportExpiry(LocalDate.of(2030, 1, 1));
        req.setBankName(" Old Bank ");
        req.setBankIfsc("OLD0001234");

        profileService.updateProfile("emp@test.com", req);

        verify(profileChangeRequestService, never()).submit(any(), any(), any());
    }

    @Test
    void clearingThePassportExpiry_isAQueuedChangeToo() {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setClearPassportExpiry(true);

        profileService.updateProfile("emp@test.com", req);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> newV = ArgumentCaptor.forClass(Map.class);
        verify(profileChangeRequestService).submit(eq(userId), any(), newV.capture());
        assertTrue(newV.getValue().containsKey("passportExpiry"));
        assertNull(newV.getValue().get("passportExpiry"));
        assertEquals(LocalDate.of(2030, 1, 1), emp.getPassportExpiry()); // still on the profile until approved
    }

    @Test
    void theResponseTellsTheEmployeeAChangeIsWaiting() {
        when(profileChangeRequestService.hasPending(userId)).thenReturn(true);

        ProfileResponse r = profileService.updateProfile("emp@test.com", new UpdateProfileRequest());

        assertTrue(r.isPendingSensitiveChange());
        assertTrue(profileService.getProfile("emp@test.com").isPendingSensitiveChange()); // and on a plain profile load
    }
}
