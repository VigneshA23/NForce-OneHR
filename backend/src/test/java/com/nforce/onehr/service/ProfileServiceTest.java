package com.nforce.onehr.service;

import com.nforce.onehr.dto.ProfileResponse;
import com.nforce.onehr.dto.UpdateProfileRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. Scoped to
 *  the US-C15/US-C21 addition (panNumber/uanNumber/esiNumber) only, not a full ProfileService
 *  suite (no pre-existing test file to extend — this is the first one). */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceService attendanceService;
    @Mock private EffectivePermissionService effectivePermissionService;

    @InjectMocks private ProfileService service;

    private final UUID userId = UUID.randomUUID();
    private final String email = "employee@test.com";

    private User user() {
        return User.builder().id(userId).email(email).active(true).build();
    }

    private Employee employee() {
        return Employee.builder().userId(userId).fullName("Test Employee")
                .panNumber("ABCDE1234F").uanNumber("123456789012").esiNumber("3112345678901234")
                .build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(user()));
        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void getProfile_masksPanUanEsi() {
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(employee()));

        ProfileResponse resp = service.getProfile(email);

        assertEquals("•••• •••• 234F", resp.getPanNumber());
        assertEquals("•••• •••• 9012", resp.getUanNumber());
        assertEquals("•••• •••• •••• 1234", resp.getEsiNumber());
    }

    @Test
    void updateProfile_panIsTrimmedAndUppercased() {
        Employee emp = employee();
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(emp));
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setPanNumber(" fghij5678k ");

        service.updateProfile(email, req);

        assertEquals("FGHIJ5678K", emp.getPanNumber());
    }

    @Test
    void updateProfile_uanAndEsiAreTrimmedButNotCaseChanged() {
        Employee emp = employee();
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(emp));
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setUanNumber(" 987654321098 ");
        req.setEsiNumber(" abc123 ");

        service.updateProfile(email, req);

        assertEquals("987654321098", emp.getUanNumber());
        assertEquals("abc123", emp.getEsiNumber());
    }

    @Test
    void updateProfile_fieldsOmitted_leavesExistingValuesUntouched() {
        Employee emp = employee();
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(emp));
        UpdateProfileRequest req = new UpdateProfileRequest(); // no statutory fields set

        service.updateProfile(email, req);

        assertEquals("ABCDE1234F", emp.getPanNumber());
        assertEquals("123456789012", emp.getUanNumber());
        assertEquals("3112345678901234", emp.getEsiNumber());
    }

    @Test
    void getProfileForViewer_withoutSensitiveAccess_nullsOutPanUanEsi() {
        UUID viewerId = UUID.randomUUID();
        User viewer = User.builder().id(viewerId).email("manager@test.com").build();
        when(userRepository.findByEmail("manager@test.com")).thenReturn(Optional.of(viewer));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(employee()));
        when(effectivePermissionService.hasAuthority(viewer, "employees:VIEW:ALL")).thenReturn(false);

        ProfileResponse resp = service.getProfileForViewer("manager@test.com", userId);

        assertNull(resp.getPanNumber());
        assertNull(resp.getUanNumber());
        assertNull(resp.getEsiNumber());
    }

    @Test
    void getProfileForViewer_withSensitiveAccess_showsMaskedPanUanEsi() {
        UUID viewerId = UUID.randomUUID();
        User viewer = User.builder().id(viewerId).email("hr@test.com").build();
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(viewer));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(employeeRepository.findById(userId)).thenReturn(Optional.of(employee()));
        when(effectivePermissionService.hasAuthority(viewer, "employees:VIEW:ALL")).thenReturn(true);

        ProfileResponse resp = service.getProfileForViewer("hr@test.com", userId);

        assertNotNull(resp.getPanNumber());
        assertNotNull(resp.getUanNumber());
        assertNotNull(resp.getEsiNumber());
    }
}
