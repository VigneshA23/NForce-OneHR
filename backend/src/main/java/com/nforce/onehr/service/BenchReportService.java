package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchDtos.AllocationOption;
import com.nforce.onehr.dto.bench.BenchDtos.BenchCost;
import com.nforce.onehr.dto.bench.BenchDtos.BenchDashboard;
import com.nforce.onehr.dto.bench.BenchDtos.BenchListRow;
import com.nforce.onehr.dto.bench.BenchDtos.BucketCount;
import com.nforce.onehr.dto.bench.BenchDtos.CostRow;
import com.nforce.onehr.dto.bench.BenchDtos.DashboardPoint;
import com.nforce.onehr.entity.BenchAgingBucket;
import com.nforce.onehr.entity.BenchDashboardSnapshot;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.CompensationBand;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeCompensationBand;
import com.nforce.onehr.entity.EmployeeSkillRating;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.SkillTaxonomy;
import com.nforce.onehr.repository.BenchAgingBucketRepository;
import com.nforce.onehr.repository.BenchDashboardSnapshotRepository;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.CompensationBandRepository;
import com.nforce.onehr.repository.EmployeeCompensationBandRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeSkillRatingRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.SkillTaxonomyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HR-facing bench reads: the filterable list (US-A04 bucket, US-A08 skill search), the dashboard
 * (US-A03) and the aggregate cost view (US-A13). All of them start from the active bench episodes.
 */
@Service
@RequiredArgsConstructor
public class BenchReportService {

    public static final String MODE_AND = "AND";
    private static final int TREND_DAYS = 90;
    static final String UNBANDED = "Unbanded";

    private final BenchStatusRepository benchRepository;
    private final BenchAgingBucketRepository bucketRepository;
    private final BenchDashboardSnapshotRepository snapshotRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeSkillRatingRepository ratingRepository;
    private final SkillTaxonomyRepository taxonomyRepository;
    private final CompensationBandRepository bandRepository;
    private final EmployeeCompensationBandRepository employeeBandRepository;
    private final ProjectAllocationRepository allocationRepository;
    private final ProjectRepository projectRepository;

    /**
     * US-A08/US-A04: active bench list, optionally narrowed by aging bucket and by skills (AND =
     * must have every requested skill, OR = any of them). Default order is idle-longest first, then
     * best skill match — matchScore being how many of the requested skills the employee has.
     */
    @Transactional(readOnly = true)
    public List<BenchListRow> list(String bucket, Set<UUID> skillIds, String mode) {
        boolean all = MODE_AND.equalsIgnoreCase(mode);
        Set<UUID> wanted = skillIds == null ? Set.of() : skillIds;
        LocalDate today = LocalDate.now();

        List<BenchStatus> active = benchRepository.findByActiveTrue();
        List<UUID> employeeIds = active.stream().map(BenchStatus::getEmployeeUserId).toList();
        Map<UUID, String> names = employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        Map<UUID, SkillTaxonomy> taxonomy = taxonomyRepository.findAll().stream()
                .collect(Collectors.toMap(SkillTaxonomy::getId, Function.identity()));
        Map<UUID, List<EmployeeSkillRating>> ratings = ratingRepository.findByEmployeeUserIdIn(employeeIds).stream()
                .collect(Collectors.groupingBy(EmployeeSkillRating::getEmployeeUserId));

        List<BenchListRow> rows = new ArrayList<>();
        for (BenchStatus b : active) {
            if (bucket != null && !bucket.isBlank() && !bucket.equals(b.getAgingBucket())) continue;
            List<EmployeeSkillRating> mine = ratings.getOrDefault(b.getEmployeeUserId(), List.of());
            Set<UUID> mineIds = mine.stream().map(EmployeeSkillRating::getSkillId).collect(Collectors.toSet());
            int matchScore = (int) wanted.stream().filter(mineIds::contains).count();
            if (!wanted.isEmpty() && (all ? matchScore != wanted.size() : matchScore == 0)) continue;
            List<String> skills = mine.stream()
                    .filter(r -> taxonomy.containsKey(r.getSkillId()))
                    .map(r -> taxonomy.get(r.getSkillId()).getSkill() + " ("
                            + (r.getManagerRating() != null ? r.getManagerRating() : r.getSelfRating()) + "/5)")
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            rows.add(new BenchListRow(b.getId(), b.getEmployeeUserId(), names.get(b.getEmployeeUserId()),
                    b.getReasonCode(), b.getStartDate(), BenchAgingService.daysOnBench(b, today),
                    b.getAgingBucket(), matchScore, skills));
        }
        rows.sort(Comparator.comparingLong(BenchListRow::daysOnBench).reversed()
                .thenComparing(Comparator.comparingInt(BenchListRow::matchScore).reversed()));
        return rows;
    }

    /**
     * US-A03: live headcount from the same sources as the Employee module (active bench rows vs.
     * active, non-deleted employees), per-bucket counts, and the last 90 days of nightly snapshots
     * with today's live point appended so the chart is never a day stale.
     */
    @Transactional(readOnly = true)
    public BenchDashboard dashboard() {
        LocalDate today = LocalDate.now();
        List<BenchStatus> active = benchRepository.findByActiveTrue();
        int benched = active.size();
        int total = (int) employeeRepository.countActiveEmployees();

        Map<String, Long> perBucket = active.stream()
                .collect(Collectors.groupingBy(b -> b.getAgingBucket() == null ? "Unassigned" : b.getAgingBucket(),
                        Collectors.counting()));
        List<BucketCount> buckets = new ArrayList<>();
        for (BenchAgingBucket b : bucketRepository.findAllByOrderByMinDaysAsc()) {
            buckets.add(new BucketCount(b.getBucketLabel(), perBucket.getOrDefault(b.getBucketLabel(), 0L)));
        }

        List<DashboardPoint> trend = new ArrayList<>();
        for (BenchDashboardSnapshot s : snapshotRepository
                .findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(today.minusDays(TREND_DAYS))) {
            if (!s.getSnapshotDate().equals(today)) {
                trend.add(new DashboardPoint(s.getSnapshotDate(), s.getBenchedCount(), s.getTotalHeadcount()));
            }
        }
        trend.add(new DashboardPoint(today, benched, total));

        double percent = total == 0 ? 0 : BigDecimal.valueOf(benched * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new BenchDashboard(benched, total, percent, buckets, trend);
    }

    /**
     * US-A13: benched headcount per compensation band x that band's average CTC. Aggregate only —
     * the result carries counts and band averages, never anything about an individual, and the band
     * tables hold no per-person salary to leak.
     */
    @Transactional(readOnly = true)
    public BenchCost cost() {
        List<UUID> benchedIds = benchRepository.findByActiveTrue().stream().map(BenchStatus::getEmployeeUserId).toList();
        Map<UUID, UUID> bandOf = employeeBandRepository.findByEmployeeUserIdIn(benchedIds).stream()
                .collect(Collectors.toMap(EmployeeCompensationBand::getEmployeeUserId, EmployeeCompensationBand::getBandId));
        Map<UUID, CompensationBand> bands = bandRepository.findAll().stream()
                .collect(Collectors.toMap(CompensationBand::getId, Function.identity()));

        Map<UUID, Long> perBand = benchedIds.stream().map(bandOf::get).filter(id -> id != null && bands.containsKey(id))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        long unbanded = benchedIds.size() - perBand.values().stream().mapToLong(Long::longValue).sum();

        List<CostRow> rows = bands.values().stream()
                .filter(b -> perBand.containsKey(b.getId()))
                .sorted(Comparator.comparing(CompensationBand::getAvgCtc))
                .map(b -> new CostRow(b.getBandLabel(), perBand.get(b.getId()), b.getAvgCtc(),
                        b.getAvgCtc().multiply(BigDecimal.valueOf(perBand.get(b.getId())))))
                .toList();
        BigDecimal total = rows.stream().map(CostRow::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BenchCost(rows, total, unbanded);
    }

    /** US-A06: the allocations of an episode's employee, newest first, for the "link allocation" choice. */
    @Transactional(readOnly = true)
    public List<AllocationOption> allocationOptions(UUID benchStatusId) {
        BenchStatus bench = benchRepository.findById(benchStatusId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Bench status not found"));
        List<ProjectAllocation> allocations = allocationRepository.findByEmployeeUserIdIn(List.of(bench.getEmployeeUserId()));
        Map<UUID, String> projectNames = projectRepository
                .findAllById(allocations.stream().map(ProjectAllocation::getProjectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        return allocations.stream()
                .sorted(Comparator.comparing(ProjectAllocation::getStartDate).reversed())
                .map(a -> new AllocationOption(a.getId(), projectNames.get(a.getProjectId()),
                        a.getCapacityPercent(), a.getStartDate(), a.getEndDate())).toList();
    }
}
