package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.ProfileResponse;
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
import org.mockito.Spy;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What a directory viewer gets for someone else's profile: masked without employee_sensitive:VIEW, audited with it. */
@ExtendWith(MockitoExtension.class)
class ProfileServiceSensitiveViewTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceService attendanceService;
    @Mock private AuditService auditService;
    @Spy private AuditSnapshotSerializer auditSnapshot = new AuditSnapshotSerializer(new ObjectMapper());
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of());
    @InjectMocks private ProfileService profileService;

    private final UUID targetId = UUID.randomUUID();
    private User target;

    @BeforeEach
    void setUp() {
        target = User.builder().id(targetId).email("target@test.com")
                .roles(Set.of(SeedMatrix.role("EMPLOYEE"))).build();
        Employee emp = Employee.builder().user(target).fullName("Target Person")
                .phone("9876543210").dateOfBirth(LocalDate.of(1990, 1, 2)).personalEmail("me@home.com")
                .address("1 Some Street").passportNumber("P1234567").nationalId("ABCDE1234F")
                .bankAccountNumber("123456781234").bankName("Some Bank").bankIfsc("SOME0001234").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(employeeRepository.findById(targetId)).thenReturn(Optional.of(emp));
    }

    private User viewer(String roleCode) {
        User v = User.builder().id(UUID.randomUUID()).email(roleCode.toLowerCase() + "@test.com")
                .roles(Set.of(SeedMatrix.role(roleCode))).build();
        when(userRepository.findByEmail(v.getEmail())).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void withoutThePermission_idsAreMaskedAndTheRestIsRemoved_andNothingIsAudited() {
        User v = viewer("MANAGER");

        ProfileResponse r = profileService.getProfileForViewer(v.getEmail(), targetId);

        assertTrue(r.isSensitiveRestricted());
        assertEquals("XXXX-1234", r.getBankAccountNumber());
        assertEquals("XXXX-234F", r.getNationalId());
        assertEquals("XXXX-4567", r.getPassportNumber());
        assertEquals("9876543210", r.getPhone()); // shown to every colleague as "Work Phone", so deliberately not masked
        assertNull(r.getDateOfBirth());
        assertNull(r.getPersonalEmail());
        assertNull(r.getAddress());
        assertNull(r.getBankName());
        assertNull(r.getBankIfsc());
        verifyNoInteractions(auditService);
    }

    @Test
    void withThePermission_theViewIsAuditedWithFieldNamesOnly() {
        User v = viewer("HR_ADMIN");

        ProfileResponse r = profileService.getProfileForViewer(v.getEmail(), targetId);

        assertFalse(r.isSensitiveRestricted());
        assertEquals("P1234567", r.getPassportNumber());
        assertEquals("SOME0001234", r.getBankIfsc()); // holders of VIEW still see both in full, unlike the person's own profile
        assertEquals(LocalDate.of(1990, 1, 2), r.getDateOfBirth());

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(v.getId()), eq("USER_SENSITIVE_DATA_VIEWED"), eq(targetId), isNull(), after.capture());
        assertTrue(after.getValue().contains("passportNumber") && after.getValue().contains("bankAccountNumber"));
        // the trail names the fields, never the values
        assertFalse(after.getValue().contains("P1234567"));
        assertFalse(after.getValue().contains("123456781234"));
        assertFalse(after.getValue().contains("9876543210"));
    }

    @Test
    void viewingYourOwnProfile_isNotAnAuditedSensitiveView() {
        User self = User.builder().id(targetId).email("self@test.com")
                .roles(Set.of(SeedMatrix.role("HR_ADMIN"))).build();
        when(userRepository.findByEmail("self@test.com")).thenReturn(Optional.of(self));

        profileService.getProfileForViewer("self@test.com", targetId);

        verify(auditService, never()).log(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
