package com.nforce.onehr.service;

import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** Sensitive profile fields are visible to employees:VIEW:ALL holders (seeded: HR_ADMIN + SUPER_ADMIN). */
@ExtendWith(MockitoExtension.class)
class ProfileServiceSensitiveAccessTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceService attendanceService;
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_HR_VIEWER", Map.of("employees:VIEW", "ALL"),
            "CUSTOM_OTHER", Map.of("employees:EDIT", "ALL")));
    @InjectMocks private ProfileService profileService;

    private boolean access(String roleCode) {
        Role role = roleCode.startsWith("CUSTOM_")
                ? Role.builder().id(100).code(roleCode).displayName(roleCode).build()
                : SeedMatrix.role(roleCode);
        String email = roleCode.toLowerCase() + "@test.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(email).roles(Set.of(role)).build()));
        return profileService.hasSensitiveAccess(email);
    }

    @Test
    void systemRoles_unchanged() {
        assertEquals(false, access("EMPLOYEE"));
        assertEquals(false, access("MANAGER"));
        assertEquals(true, access("HR_ADMIN"));
        assertEquals(true, access("SUPER_ADMIN"));
    }

    @Test
    void customRole_withEmployeesViewAll_hasAccess_withoutIt_doesNot() {
        assertEquals(true, access("CUSTOM_HR_VIEWER"));
        assertEquals(false, access("CUSTOM_OTHER"));
    }
}
