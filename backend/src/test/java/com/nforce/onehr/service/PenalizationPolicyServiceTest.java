package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.*;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Versioning/immutability/audit behavior of Organization Masters -> Penalization Policy. */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyServiceTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;
    @Mock private AttendanceProperties attendanceProperties;

    private PenalizationPolicyService service;
    private final UUID actorId = UUID.randomUUID();
    private final UUID defaultPolicyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PenalizationPolicyService(versionRepository, tierRepository, lateHoursTierRepository,
                penalisationPolicyRepository, userRepository, auditService, snapshotSerializer, attendanceProperties);
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(userRepository.findByEmail("hr@test.com"))
                .thenReturn(Optional.of(User.builder().id(actorId).email("hr@test.com").build()));
        lenient().when(versionRepository.save(any())).thenAnswer(inv -> {
            PenalizationPolicyVersion v = inv.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
        lenient().when(snapshotSerializer.toJson(any())).thenReturn("{}");
        lenient().when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        lenient().when(lateHoursTierRepository.findByPolicyVersionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        // Only consulted when no current version exists yet (first-ever save) — see
        // PenalizationPolicyService.resolveDefaultPolicyId.
        lenient().when(penalisationPolicyRepository.findAll()).thenReturn(List.of(
                PenalisationPolicy.builder().id(defaultPolicyId)
                        .createdAt(java.time.LocalDateTime.of(2025, 1, 1, 0, 0)).build()));
    }

    private PenalizationPolicyRequest minimalRequest() {
        PenalizationPolicyRequest req = new PenalizationPolicyRequest();
        req.setBasicInfo(new BasicInfoConfigDto());
        req.setNoAttendance(new NoAttendanceConfigDto());
        LateArrivalConfigDto la = new LateArrivalConfigDto();
        la.setGracePeriodMinutes(10);
        req.setLateArrival(la);
        req.setWorkHoursShortage(new WorkHoursShortageConfigDto());
        req.setMissingLogs(new MissingLogsConfigDto());
        return req;
    }

    @Test
    void firstSave_createsVersion1_andAuditsCreated() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        PenalizationPolicyResponse response = service.save(null, minimalRequest(), "hr@test.com");

        assertEquals(1, response.getVersion());
        assertEquals(10, response.getLateArrival().getGracePeriodMinutes());
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_CREATED"), any(), isNull(), any());
    }

    @Test
    void secondSave_createsVersion2_closesVersion1_neverMutatesItsConfig() {
        PenalizationPolicyVersion v1 = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1)
                .lateArrivalEnabled(true).laGracePeriodMinutes(10)
                .effectiveFrom(java.time.LocalDateTime.of(2026, 8, 1, 0, 0))
                .build();
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(v1.getPolicyId())).thenReturn(Optional.of(v1));

        PenalizationPolicyRequest req = minimalRequest();
        req.getLateArrival().setGracePeriodMinutes(15);
        req.getLateArrival().setEnabled(true);
        PenalizationPolicyResponse v2Response = service.save(v1.getPolicyId(), req, "hr@test.com");

        assertEquals(2, v2Response.getVersion());
        assertEquals(15, v2Response.getLateArrival().getGracePeriodMinutes());
        assertEquals(v1.getPolicyId(), v2Response.getPolicyId(), "policyId must stay stable across versions");

        // V1's own grace period value is never rewritten — only effectiveTo (a temporal
        // boundary, not a configuration value) is set on it.
        assertEquals(10, v1.getLaGracePeriodMinutes());
        assertNotNull(v1.getEffectiveTo());

        ArgumentCaptor<PenalizationPolicyVersion> savedV1 = ArgumentCaptor.forClass(PenalizationPolicyVersion.class);
        verify(versionRepository, times(2)).save(savedV1.capture());
        assertEquals(10, savedV1.getAllValues().get(0).getLaGracePeriodMinutes());

        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_UPDATED"), any(), any(), any());
    }

    @Test
    void getCurrent_whenNoVersionExists_isEmpty() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());

        assertTrue(service.getCurrent(null).isEmpty());
    }

    private WorkHoursTierDto tier(String thresholdPercent, String deductionDays) {
        WorkHoursTierDto t = new WorkHoursTierDto();
        t.setThresholdPercent(new java.math.BigDecimal(thresholdPercent));
        t.setDeductionDays(new java.math.BigDecimal(deductionDays));
        return t;
    }

    // ── Section 21: Work Hours Shortage tier validation (PenalizationPolicyService.validateTiers) ──

    @Test
    void save_duplicateTierThresholds_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("20", "1"),
                tier("20", "2")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("Work Hours Shortage tier thresholds must be distinct", ex.getMessage());
        verify(versionRepository, never()).save(any());
        verifyNoInteractions(tierRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void save_tierThresholdOver100_rejected_doesNotSaveVersion() {
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("101", "1")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(null, req, "hr@test.com"));

        assertEquals("Work Hours Shortage tier thresholds cannot exceed 100%", ex.getMessage());
        verify(versionRepository, never()).save(any());
        verifyNoInteractions(tierRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void save_validDistinctTiers_accepted_savedInOrder() {
        when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        PenalizationPolicyRequest req = minimalRequest();
        req.getWorkHoursShortage().setTiers(List.of(
                tier("20", "1"),
                tier("50", "2"),
                tier("80", "3")));

        PenalizationPolicyResponse response = service.save(null, req, "hr@test.com");

        assertEquals(1, response.getVersion(), "validation passing must not change ordinary first-save behavior");
        verify(auditService).log(eq(actorId), eq("PENALIZATION_POLICY_CREATED"), any(), isNull(), any());

        ArgumentCaptor<com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier> tierCaptor =
                ArgumentCaptor.forClass(com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier.class);
        verify(tierRepository, times(3)).save(tierCaptor.capture());
        List<com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier> savedTiers = tierCaptor.getAllValues();
        assertEquals(new java.math.BigDecimal("20"), savedTiers.get(0).getThresholdPercent());
        assertEquals(new java.math.BigDecimal("50"), savedTiers.get(1).getThresholdPercent());
        assertEquals(new java.math.BigDecimal("80"), savedTiers.get(2).getThresholdPercent());
    }
}
