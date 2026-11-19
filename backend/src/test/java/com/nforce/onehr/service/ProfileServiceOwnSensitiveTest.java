package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.OwnSensitiveProfileResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The eye button on a person's own profile: their own masked values, unmasked, and recorded in the audit log. */
@ExtendWith(MockitoExtension.class)
class ProfileServiceOwnSensitiveTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceService attendanceService;
    @Mock private AuditService auditService;
    @Mock private ProfileChangeRequestService profileChangeRequestService;
    @Mock private EffectivePermissionService effectivePermissionService;
    @Spy private AuditSnapshotSerializer auditSnapshot = new AuditSnapshotSerializer(new ObjectMapper());
    @InjectMocks private ProfileService profileService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(userRepository.findByEmail("me@test.com")).thenReturn(Optional.of(User.builder().id(userId).email("me@test.com").build()));
    }

    @Test
    void returnsTheCallersOwnRawValues_andAuditsTheViewWithFieldNamesOnly() {
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(Employee.builder()
                .bankAccountNumber("123456781234").nationalId("ABCDE1234F")
                .passportNumber("P1234567").bankIfsc("SOME0001234").build()));

        OwnSensitiveProfileResponse r = profileService.getOwnSensitive("me@test.com");

        assertEquals("123456781234", r.bankAccountNumber());
        assertEquals("ABCDE1234F", r.nationalId());
        assertEquals("P1234567", r.passportNumber());
        assertEquals("SOME0001234", r.bankIfsc());
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(userId), eq("USER_SENSITIVE_DATA_VIEWED"), eq(userId), isNull(), after.capture());
        assertTrue(after.getValue().contains("bankAccountNumber") && after.getValue().contains("nationalId")
                && after.getValue().contains("passportNumber") && after.getValue().contains("bankIfsc"));
        assertFalse(after.getValue().contains("123456781234"), "the trail names fields, never values");
        assertFalse(after.getValue().contains("ABCDE1234F"));
        assertFalse(after.getValue().contains("P1234567"));
        assertFalse(after.getValue().contains("SOME0001234"));
    }

    @Test
    void theOwnProfile_showsAllFourIdentifiersMasked_untilTheEyeIsPressed() {
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(Employee.builder()
                .bankAccountNumber("123456781234").nationalId("ABCDE1234F")
                .passportNumber("P1234567").bankIfsc("SOME0001234").build()));

        var profile = profileService.getProfile("me@test.com");

        assertEquals("•••• •••• 1234", profile.getBankAccountNumber());
        assertEquals("•••• •••• 234F", profile.getNationalId());
        assertEquals("•••• 4567", profile.getPassportNumber());
        assertEquals("•••• •••• 1234", profile.getBankIfsc());
        verify(auditService, never()).log(any(), any(), any(), any(), any()); // masked values are not an unmasked view
    }

    @Test
    void whenNothingSensitiveIsStored_thereIsNothingToRevealAndNothingToAudit() {
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(Employee.builder().build()));

        OwnSensitiveProfileResponse r = profileService.getOwnSensitive("me@test.com");

        assertNull(r.bankAccountNumber());
        assertNull(r.nationalId());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void aUserWithoutAnEmployeeRecord_getsNothing_andIsNotAudited() {
        when(employeeRepository.findById(userId)).thenReturn(Optional.empty());

        OwnSensitiveProfileResponse r = profileService.getOwnSensitive("me@test.com");

        assertNull(r.bankAccountNumber());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
