package com.nforce.onehr.service;

import com.nforce.onehr.dto.org.CreateWeeklyOffPolicyRequest;
import com.nforce.onehr.dto.org.UpdateWeeklyOffPolicyRequest;
import com.nforce.onehr.dto.org.WeeklyOffPolicyResponse;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.AssetRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.BusinessUnitRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.ShiftVersionRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Code-review corrective pass, findings 6/7: WeeklyOffPolicy create/update validation — at least
 * one working day must always be enforceable ({@code WorkingDayService#nextWorkingDay} can never
 * terminate its search otherwise), and a blank-only offDays list must be rejected exactly like a
 * genuinely empty one, never silently normalized down to {@code ""}.
 */
@ExtendWith(MockitoExtension.class)
class OrgServiceWeeklyOffPolicyTest {

    @Mock private BusinessUnitRepository businessUnitRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private DesignationRepository designationRepo;
    @Mock private LocationRepository locationRepo;
    @Mock private ShiftRepository shiftRepo;
    @Mock private ShiftVersionRepository shiftVersionRepository;
    @Mock private ShiftVersionResolver shiftVersionResolver;
    @Mock private ShiftWeeklyOffRulesService shiftWeeklyOffRulesService;
    @Mock private WeeklyOffPolicyRepository weeklyOffPolicyRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private HolidayRepository holidayRepo;
    @Mock private AssetRepository assetRepo;
    @Mock private AttendanceRepository attendanceRepo;
    @Mock private EmployeeShiftAssignmentRepository employeeShiftAssignmentRepo;
    @Mock private com.nforce.onehr.repository.WeeklyOffPolicyVersionRepository weeklyOffPolicyVersionRepo;
    @Mock private com.nforce.onehr.repository.EmployeeWeeklyOffPolicyAssignmentRepository employeeWeeklyOffPolicyAssignmentRepo;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(businessUnitRepo, departmentRepo, designationRepo, locationRepo, shiftRepo,
                shiftVersionRepository, shiftVersionResolver, shiftWeeklyOffRulesService, weeklyOffPolicyRepo, employeeRepo, historyRepo,
                holidayRepo, assetRepo, attendanceRepo, employeeShiftAssignmentRepo, weeklyOffPolicyVersionRepo, employeeWeeklyOffPolicyAssignmentRepo);
        lenient().when(weeklyOffPolicyRepo.save(any())).thenAnswer(inv -> {
            WeeklyOffPolicy p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        lenient().when(employeeRepo.countByWeeklyOffPolicyId(any())).thenReturn(0L);
    }

    private CreateWeeklyOffPolicyRequest createReq(String name, List<String> offDays) {
        CreateWeeklyOffPolicyRequest req = new CreateWeeklyOffPolicyRequest();
        req.setName(name);
        req.setOffDays(offDays);
        return req;
    }

    private UpdateWeeklyOffPolicyRequest updateReq(String name, List<String> offDays) {
        UpdateWeeklyOffPolicyRequest req = new UpdateWeeklyOffPolicyRequest();
        req.setName(name);
        req.setOffDays(offDays);
        return req;
    }

    // ── Finding 6: at least one working day must always remain ──────────────────────────────

    @Test
    void createWeeklyOffPolicy_sixDaysOff_isAccepted() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        assertDoesNotThrow(() -> service.createWeeklyOffPolicy(
                createReq("Six Days Off", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"))));
    }

    @Test
    void createWeeklyOffPolicy_allSevenDaysOff_isRejected() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createWeeklyOffPolicy(createReq("No Working Days",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))));
        verify(weeklyOffPolicyRepo, never()).save(any());
    }

    @Test
    void createWeeklyOffPolicy_allSevenDaysOff_duplicatedEntriesStillRejected() {
        // Duplicates collapse during normalization — 7 distinct entries plus a repeat must still
        // be caught as "all 7," not slip through because the raw list happened to have 8 elements.
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createWeeklyOffPolicy(createReq("No Working Days",
                List.of("MONDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))));
    }

    @Test
    void updateWeeklyOffPolicy_allSevenDaysOff_isRejected() {
        UUID policyId = UUID.randomUUID();
        WeeklyOffPolicy existing = WeeklyOffPolicy.builder().id(policyId).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepo.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.updateWeeklyOffPolicy(policyId, updateReq("Standard",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))));
        assertEquals("SATURDAY,SUNDAY", existing.getOffDays(), "a rejected update must leave the existing policy untouched");
    }

    // ── Finding 7: a blank-only offDays list must be rejected, not normalized to "" ──────────

    @Test
    void createWeeklyOffPolicy_blankOnlyOffDaysList_isRejected_justLikeAnEmptyList() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createWeeklyOffPolicy(createReq("Blank Days", List.of(" ", "  "))));
        assertTrue(ex.getMessage().toLowerCase().contains("off day"));
        verify(weeklyOffPolicyRepo, never()).save(any());
    }

    @Test
    void createWeeklyOffPolicy_mixOfBlankAndValidDays_keepsOnlyTheValidOnes() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);
        ArgumentCaptor<WeeklyOffPolicy> captor = ArgumentCaptor.forClass(WeeklyOffPolicy.class);

        service.createWeeklyOffPolicy(createReq("Mostly Blank", List.of(" ", "SATURDAY", "  ", "SUNDAY")));

        verify(weeklyOffPolicyRepo).save(captor.capture());
        assertEquals("SATURDAY,SUNDAY", captor.getValue().getOffDays());
    }

    @Test
    void createWeeklyOffPolicy_validDays_isAcceptedAndPersisted() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        WeeklyOffPolicyResponse response = service.createWeeklyOffPolicy(createReq("Standard", List.of("SATURDAY", "SUNDAY")));

        assertEquals(List.of("SATURDAY", "SUNDAY"), response.getOffDays());
    }

    // ── Phase 3: policy-definition versioning — editing offDays creates a new version rather than
    //    mutating historical configuration; the name field stays immediate/unversioned. ─────────

    @Test
    void createWeeklyOffPolicy_savesAnInitialVersion_effectiveToday() {
        when(weeklyOffPolicyRepo.existsByNameIgnoreCase(any())).thenReturn(false);

        service.createWeeklyOffPolicy(createReq("Standard", List.of("SATURDAY", "SUNDAY")));

        ArgumentCaptor<com.nforce.onehr.entity.WeeklyOffPolicyVersion> captor =
                ArgumentCaptor.forClass(com.nforce.onehr.entity.WeeklyOffPolicyVersion.class);
        verify(weeklyOffPolicyVersionRepo).save(captor.capture());
        assertEquals("SATURDAY,SUNDAY", captor.getValue().getOffDays());
        assertEquals(java.time.LocalDate.now(), captor.getValue().getEffectiveFrom());
    }

    @Test
    void updateWeeklyOffPolicy_changedOffDays_createsANewVersion_replacingAnyVersionEffectiveTodayOrLater() {
        UUID policyId = UUID.randomUUID();
        WeeklyOffPolicy existing = WeeklyOffPolicy.builder().id(policyId).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepo.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        service.updateWeeklyOffPolicy(policyId, updateReq("Standard", List.of("FRIDAY")));

        verify(weeklyOffPolicyVersionRepo).deleteByWeeklyOffPolicyIdAndEffectiveFromGreaterThanEqual(policyId, java.time.LocalDate.now());
        ArgumentCaptor<com.nforce.onehr.entity.WeeklyOffPolicyVersion> captor =
                ArgumentCaptor.forClass(com.nforce.onehr.entity.WeeklyOffPolicyVersion.class);
        verify(weeklyOffPolicyVersionRepo).save(captor.capture());
        assertEquals("FRIDAY", captor.getValue().getOffDays());
        // The display cache is kept in sync with the new current version.
        assertEquals("FRIDAY", existing.getOffDays());
    }

    @Test
    void updateWeeklyOffPolicy_unchangedOffDays_renameOnly_createsNoNewVersion() {
        UUID policyId = UUID.randomUUID();
        WeeklyOffPolicy existing = WeeklyOffPolicy.builder().id(policyId).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepo.findById(policyId)).thenReturn(java.util.Optional.of(existing));

        service.updateWeeklyOffPolicy(policyId, updateReq("Standard (Renamed)", List.of("SATURDAY", "SUNDAY")));

        verifyNoInteractions(weeklyOffPolicyVersionRepo);
        assertEquals("Standard (Renamed)", existing.getName());
        assertEquals("SATURDAY,SUNDAY", existing.getOffDays());
    }

    // ── Audit fix (Phase 3 verification): "current employee" counts/guards must read the
    //    authoritative EmployeeWeeklyOffPolicyAssignment table, never the stale
    //    Employee.weeklyOffPolicy cache that bulkUpdateWeeklyOff/CSV import no longer write to. ──

    @Test
    void deleteWeeklyOffPolicy_blockedByCurrentAssignmentCount_fromTheAuthoritativeTable_notTheStaleCache() {
        UUID policyId = UUID.randomUUID();
        WeeklyOffPolicy existing = WeeklyOffPolicy.builder().id(policyId).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepo.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        // The stale Employee.weeklyOffPolicy cache says zero — exactly what happens once an
        // employee was ever reassigned via bulkUpdateWeeklyOff/CSV import post-Phase-3 — but the
        // authoritative assignment table says 3 employees are currently on this policy today.
        lenient().when(employeeRepo.countByWeeklyOffPolicyId(policyId)).thenReturn(0L);
        when(employeeWeeklyOffPolicyAssignmentRepo.countCurrentlyAssigned(eq(policyId), any())).thenReturn(3L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.deleteWeeklyOffPolicy(policyId));

        assertTrue(ex.getMessage().contains("3 current employees"));
        verify(weeklyOffPolicyRepo, never()).delete(any());
    }

    @Test
    void deleteWeeklyOffPolicy_allowedWhenAuthoritativeCurrentCountIsZero_evenIfStaleCacheDisagreed() {
        UUID policyId = UUID.randomUUID();
        WeeklyOffPolicy existing = WeeklyOffPolicy.builder().id(policyId).name("Standard").offDays("SATURDAY,SUNDAY").build();
        when(weeklyOffPolicyRepo.findById(policyId)).thenReturn(java.util.Optional.of(existing));
        when(employeeWeeklyOffPolicyAssignmentRepo.countCurrentlyAssigned(eq(policyId), any())).thenReturn(0L);
        when(employeeWeeklyOffPolicyAssignmentRepo.existsByWeeklyOffPolicyId(policyId)).thenReturn(false);

        assertDoesNotThrow(() -> service.deleteWeeklyOffPolicy(policyId));
        verify(weeklyOffPolicyRepo).delete(existing);
    }
}
