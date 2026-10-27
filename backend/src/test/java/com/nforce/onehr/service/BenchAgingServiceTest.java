package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchDtos.AgingConfigRequest;
import com.nforce.onehr.dto.bench.BenchDtos.BucketInput;
import com.nforce.onehr.entity.BenchAgingBucket;
import com.nforce.onehr.entity.BenchDashboardSnapshot;
import com.nforce.onehr.entity.BenchReasonCode;
import com.nforce.onehr.entity.BenchSettings;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.repository.BenchAgingBucketRepository;
import com.nforce.onehr.repository.BenchDashboardSnapshotRepository;
import com.nforce.onehr.repository.BenchSettingsRepository;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US-A04 (bucket boundaries, nightly refresh), US-A05 (one notification per crossing, deduped by
 * notifiedAt) and the nightly snapshot.
 */
@ExtendWith(MockitoExtension.class)
class BenchAgingServiceTest {

    @Mock private BenchAgingBucketRepository bucketRepository;
    @Mock private BenchSettingsRepository settingsRepository;
    @Mock private BenchStatusRepository benchRepository;
    @Mock private BenchDashboardSnapshotRepository snapshotRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private NotificationService notificationService;

    private BenchAgingService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();

    private final List<BenchAgingBucket> defaults = List.of(
            bucket("0-15 days", 0, 15), bucket("16-30 days", 16, 30),
            bucket("31-60 days", 31, 60), bucket("60+ days", 61, null));

    private static BenchAgingBucket bucket(String label, int min, Integer max) {
        return BenchAgingBucket.builder().bucketLabel(label).minDays(min).maxDays(max).build();
    }

    @BeforeEach
    void setUp() {
        service = new BenchAgingService(bucketRepository, settingsRepository, benchRepository, snapshotRepository,
                employeeRepository, managerHistoryRepository, notificationService);
        lenient().when(bucketRepository.findAllByOrderByMinDaysAsc()).thenReturn(defaults);
        lenient().when(settingsRepository.findById(BenchSettings.SINGLETON_ID))
                .thenReturn(Optional.of(BenchSettings.builder().id(1).notifyThresholdDays(30).build()));
        lenient().when(benchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private BenchStatus benched(int daysAgo) {
        return BenchStatus.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .reasonCode(BenchReasonCode.PROJECT_ENDED).startDate(LocalDate.now().minusDays(daysAgo)).build();
    }

    // ---- bucket resolution ----

    @Test
    void resolveBucket_putsEachBoundaryDayInTheRightBucket() {
        assertEquals("0-15 days", BenchAgingService.resolveBucket(defaults, 0));
        assertEquals("0-15 days", BenchAgingService.resolveBucket(defaults, 15));
        assertEquals("16-30 days", BenchAgingService.resolveBucket(defaults, 16));
        assertEquals("31-60 days", BenchAgingService.resolveBucket(defaults, 60));
        assertEquals("60+ days", BenchAgingService.resolveBucket(defaults, 61));
        assertEquals("60+ days", BenchAgingService.resolveBucket(defaults, 4000));
    }

    // ---- config validation ----

    @Test
    void validateContiguous_acceptsAContiguousSetEndingOpen() {
        assertDoesNotThrow(() -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("a", 0, 10), new BucketInput("b", 11, null))));
    }

    @Test
    void validateContiguous_rejectsAGap() {
        assertThrows(IllegalArgumentException.class, () -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("a", 0, 10), new BucketInput("b", 12, null))));
    }

    @Test
    void validateContiguous_rejectsAnOverlap() {
        assertThrows(IllegalArgumentException.class, () -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("a", 0, 10), new BucketInput("b", 10, null))));
    }

    @Test
    void validateContiguous_rejectsNotStartingAtZero() {
        assertThrows(IllegalArgumentException.class, () -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("a", 1, 10), new BucketInput("b", 11, null))));
    }

    @Test
    void validateContiguous_rejectsAnOpenEndedBucketThatIsNotLast() {
        assertThrows(IllegalArgumentException.class, () -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("a", 0, null), new BucketInput("b", 11, null))));
    }

    @Test
    void validateContiguous_rejectsDuplicateLabels() {
        assertThrows(IllegalArgumentException.class, () -> BenchAgingService.validateContiguous(List.of(
                new BucketInput("same", 0, 10), new BucketInput("SAME", 11, null))));
    }

    @Test
    void saveConfig_storesBucketsAndThreshold_andRefreshesStoredBuckets() {
        BenchStatus b = benched(20);
        when(benchRepository.findByActiveTrue()).thenReturn(List.of(b));

        service.saveConfig(new AgingConfigRequest(
                List.of(new BucketInput("fresh", 0, 9), new BucketInput("stale", 10, null)), 45));

        verify(bucketRepository).deleteAll();
        ArgumentCaptor<BenchSettings> settings = ArgumentCaptor.forClass(BenchSettings.class);
        verify(settingsRepository).save(settings.capture());
        assertEquals(45, settings.getValue().getNotifyThresholdDays());
        assertEquals("stale", b.getAgingBucket());
    }

    @Test
    void saveConfig_rejectsAnInvalidSetBeforeTouchingTheDatabase() {
        assertThrows(IllegalArgumentException.class, () -> service.saveConfig(new AgingConfigRequest(
                List.of(new BucketInput("a", 0, 10), new BucketInput("b", 20, null)), 30)));
        verify(bucketRepository, never()).deleteAll();
    }

    // ---- nightly job ----

    @Test
    void recomputeAll_refreshesBuckets_notifiesTheManagerOnce_andSnapshots() {
        BenchStatus b = benched(35);
        when(benchRepository.findByActiveTrue()).thenReturn(List.of(b));
        when(managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().managerUserId(managerId).build()));
        when(employeeRepository.findById(employeeId))
                .thenReturn(Optional.of(Employee.builder().userId(employeeId).fullName("Asha Rao").build()));
        when(benchRepository.countByActiveTrue()).thenReturn(1L);
        when(employeeRepository.countActiveEmployees()).thenReturn(10L);

        service.recomputeAll();

        assertEquals("31-60 days", b.getAgingBucket());
        assertNotNull(b.getNotifiedAt());
        verify(notificationService).send(eq(managerId), eq("BENCH_THRESHOLD"), any(), any(), any());
        ArgumentCaptor<BenchDashboardSnapshot> snap = ArgumentCaptor.forClass(BenchDashboardSnapshot.class);
        verify(snapshotRepository).save(snap.capture());
        assertEquals(1, snap.getValue().getBenchedCount());
        assertEquals(10, snap.getValue().getTotalHeadcount());
    }

    @Test
    void recomputeAll_neverNotifiesTheSameCrossingTwice() {
        BenchStatus b = benched(40);
        b.setNotifiedAt(java.time.Instant.now());
        when(benchRepository.findByActiveTrue()).thenReturn(List.of(b));

        service.recomputeAll();

        verifyNoInteractions(notificationService);
    }

    @Test
    void recomputeAll_doesNotNotifyBeforeTheThreshold() {
        BenchStatus b = benched(29);
        when(benchRepository.findByActiveTrue()).thenReturn(List.of(b));

        service.recomputeAll();

        verifyNoInteractions(notificationService);
        assertNull(b.getNotifiedAt());
    }

    @Test
    void recomputeAll_retriesLaterWhenThereIsNoManagerYet() {
        BenchStatus b = benched(45);
        when(benchRepository.findByActiveTrue()).thenReturn(List.of(b));
        when(managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        service.recomputeAll();

        verifyNoInteractions(notificationService);
        assertNull(b.getNotifiedAt());
    }
}
