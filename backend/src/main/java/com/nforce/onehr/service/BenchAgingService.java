package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchDtos.AgingConfigRequest;
import com.nforce.onehr.dto.bench.BenchDtos.AgingConfigResponse;
import com.nforce.onehr.dto.bench.BenchDtos.BucketInput;
import com.nforce.onehr.entity.BenchAgingBucket;
import com.nforce.onehr.entity.BenchDashboardSnapshot;
import com.nforce.onehr.entity.BenchSettings;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.repository.BenchAgingBucketRepository;
import com.nforce.onehr.repository.BenchDashboardSnapshotRepository;
import com.nforce.onehr.repository.BenchSettingsRepository;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * US-A04 (aging buckets), US-A05 (threshold notification) and the US-A03 nightly snapshot.
 *
 * <p>{@link #recomputeAll()} is the one nightly pass: it refreshes every active episode's stored
 * bucket, fires the one-time threshold notification (deduped by {@code notifiedAt}) and captures
 * the dashboard snapshot, so there is a single scheduled entry point (see
 * {@link BenchNightlyScheduler}) rather than three crons.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BenchAgingService {

    public static final String NOTIFICATION_TYPE = "BENCH_THRESHOLD";
    private static final int DEFAULT_THRESHOLD_DAYS = 30;

    private final BenchAgingBucketRepository bucketRepository;
    private final BenchSettingsRepository settingsRepository;
    private final BenchStatusRepository benchRepository;
    private final BenchDashboardSnapshotRepository snapshotRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final NotificationService notificationService;

    // ---- config ----

    @Transactional(readOnly = true)
    public AgingConfigResponse getConfig() {
        return new AgingConfigResponse(
                bucketRepository.findAllByOrderByMinDaysAsc().stream()
                        .map(b -> new BucketInput(b.getBucketLabel(), b.getMinDays(), b.getMaxDays())).toList(),
                thresholdDays());
    }

    /**
     * Replaces the whole bucket set. Buckets must start at day 0, be contiguous and non-overlapping,
     * and only the last may be open-ended — otherwise some days-on-bench value would match no bucket
     * (or two), which the nightly job and every report would then have to special-case.
     */
    @Transactional
    public AgingConfigResponse saveConfig(AgingConfigRequest req) {
        List<BucketInput> sorted = new ArrayList<>(req.buckets());
        sorted.sort(Comparator.comparingInt(BucketInput::minDays));
        validateContiguous(sorted);

        bucketRepository.deleteAll();
        bucketRepository.flush();
        bucketRepository.saveAll(sorted.stream().map(b -> BenchAgingBucket.builder()
                .bucketLabel(b.label().trim()).minDays(b.minDays()).maxDays(b.maxDays()).build()).toList());

        BenchSettings settings = settingsRepository.findById(BenchSettings.SINGLETON_ID)
                .orElseGet(() -> BenchSettings.builder().id(BenchSettings.SINGLETON_ID).build());
        settings.setNotifyThresholdDays(req.notifyThresholdDays());
        settingsRepository.save(settings);

        refreshStoredBuckets(benchRepository.findByActiveTrue(), sorted.stream().map(b -> BenchAgingBucket.builder()
                .bucketLabel(b.label().trim()).minDays(b.minDays()).maxDays(b.maxDays()).build()).toList());
        return getConfig();
    }

    static void validateContiguous(List<BucketInput> sorted) {
        if (sorted.get(0).minDays() != 0) {
            throw new IllegalArgumentException("The first bucket must start at day 0");
        }
        for (int i = 0; i < sorted.size(); i++) {
            BucketInput b = sorted.get(i);
            boolean last = i == sorted.size() - 1;
            if (b.label() == null || b.label().isBlank()) {
                throw new IllegalArgumentException("Every bucket needs a label");
            }
            if (b.maxDays() == null && !last) {
                throw new IllegalArgumentException("Only the last bucket can be open-ended");
            }
            if (b.maxDays() != null && b.maxDays() < b.minDays()) {
                throw new IllegalArgumentException("Bucket \"" + b.label() + "\": max days cannot be below min days");
            }
            if (!last && sorted.get(i + 1).minDays() != b.maxDays() + 1) {
                throw new IllegalArgumentException("Buckets must be contiguous: \"" + b.label()
                        + "\" ends at day " + b.maxDays() + " but the next one starts at day " + sorted.get(i + 1).minDays());
            }
        }
        long distinct = sorted.stream().map(b -> b.label().trim().toLowerCase()).distinct().count();
        if (distinct != sorted.size()) {
            throw new IllegalArgumentException("Bucket labels must be unique");
        }
    }

    public int thresholdDays() {
        return settingsRepository.findById(BenchSettings.SINGLETON_ID)
                .map(BenchSettings::getNotifyThresholdDays).orElse(DEFAULT_THRESHOLD_DAYS);
    }

    // ---- bucket resolution ----

    @Transactional(readOnly = true)
    public String bucketFor(long daysOnBench) {
        return resolveBucket(bucketRepository.findAllByOrderByMinDaysAsc(), daysOnBench);
    }

    /** The bucket whose [minDays, maxDays] contains {@code days}; the open-ended last bucket takes the rest. */
    public static String resolveBucket(List<BenchAgingBucket> buckets, long days) {
        for (BenchAgingBucket b : buckets) {
            if (days >= b.getMinDays() && (b.getMaxDays() == null || days <= b.getMaxDays())) {
                return b.getBucketLabel();
            }
        }
        return null;
    }

    public static long daysOnBench(BenchStatus b, LocalDate today) {
        return Math.max(0, ChronoUnit.DAYS.between(b.getStartDate(), today));
    }

    // ---- nightly job ----

    @Transactional
    public void recomputeAll() {
        LocalDate today = LocalDate.now();
        List<BenchAgingBucket> buckets = bucketRepository.findAllByOrderByMinDaysAsc();
        List<BenchStatus> active = benchRepository.findByActiveTrue();
        refreshStoredBuckets(active, buckets);
        notifyThresholdCrossings(active, today);
        captureSnapshot(today);
        log.info("Bench nightly pass complete: {} active episode(s)", active.size());
    }

    private void refreshStoredBuckets(List<BenchStatus> active, List<BenchAgingBucket> buckets) {
        LocalDate today = LocalDate.now();
        for (BenchStatus b : active) {
            b.setAgingBucket(resolveBucket(buckets, daysOnBench(b, today)));
        }
        benchRepository.saveAll(active);
    }

    /**
     * US-A05: notifies the report's current manager once, the first night their days-on-bench is at
     * or above the threshold. notifiedAt is only stamped when a manager actually received it, so an
     * employee with no manager yet is retried the next night rather than silently never notified.
     */
    private void notifyThresholdCrossings(List<BenchStatus> active, LocalDate today) {
        int threshold = thresholdDays();
        for (BenchStatus b : active) {
            long days = daysOnBench(b, today);
            if (b.getNotifiedAt() != null || days < threshold) continue;
            Optional<EmployeeManagerHistory> manager =
                    managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(b.getEmployeeUserId());
            if (manager.isEmpty()) continue;
            String name = employeeRepository.findById(b.getEmployeeUserId())
                    .map(e -> e.getFullName()).orElse("A team member");
            notificationService.send(manager.get().getManagerUserId(), NOTIFICATION_TYPE,
                    "Team member on bench for " + threshold + "+ days",
                    name + " has been on bench for " + days + " days.", "/bench");
            b.setNotifiedAt(Instant.now());
            benchRepository.save(b);
        }
    }

    @Transactional
    public BenchDashboardSnapshot captureSnapshot(LocalDate date) {
        return snapshotRepository.save(BenchDashboardSnapshot.builder()
                .snapshotDate(date)
                .benchedCount((int) benchRepository.countByActiveTrue())
                .totalHeadcount((int) employeeRepository.countActiveEmployees())
                .build());
    }
}
