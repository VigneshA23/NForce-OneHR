package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchDtos.BenchCost;
import com.nforce.onehr.dto.bench.BenchDtos.BenchDashboard;
import com.nforce.onehr.dto.bench.BenchDtos.BenchListRow;
import com.nforce.onehr.entity.BenchAgingBucket;
import com.nforce.onehr.entity.BenchDashboardSnapshot;
import com.nforce.onehr.entity.BenchReasonCode;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.CompensationBand;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeCompensationBand;
import com.nforce.onehr.entity.EmployeeSkillRating;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * US-A08 (skill AND/OR search, idle-longest-first ordering), US-A04 (bucket filter), US-A03
 * (dashboard reconciles with employee headcount, trend ends on today) and US-A13 (aggregate cost,
 * unbanded kept out of the money).
 */
@ExtendWith(MockitoExtension.class)
class BenchReportServiceTest {

    @Mock private BenchStatusRepository benchRepository;
    @Mock private BenchAgingBucketRepository bucketRepository;
    @Mock private BenchDashboardSnapshotRepository snapshotRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeSkillRatingRepository ratingRepository;
    @Mock private SkillTaxonomyRepository taxonomyRepository;
    @Mock private CompensationBandRepository bandRepository;
    @Mock private EmployeeCompensationBandRepository employeeBandRepository;
    @Mock private ProjectAllocationRepository allocationRepository;
    @Mock private ProjectRepository projectRepository;

    private BenchReportService service;

    private final UUID javaId = UUID.randomUUID();
    private final UUID sql = UUID.randomUUID();
    private final UUID ann = UUID.randomUUID();   // 40 days, Java + SQL
    private final UUID bob = UUID.randomUUID();   // 10 days, Java only
    private final UUID cat = UUID.randomUUID();   // 50 days, no skills

    @BeforeEach
    void setUp() {
        service = new BenchReportService(benchRepository, bucketRepository, snapshotRepository, employeeRepository,
                ratingRepository, taxonomyRepository, bandRepository, employeeBandRepository, allocationRepository, projectRepository);
        lenient().when(benchRepository.findByActiveTrue()).thenReturn(List.of(
                bench(ann, 40, "31-60 days"), bench(bob, 10, "0-15 days"), bench(cat, 50, "31-60 days")));
        lenient().when(employeeRepository.findAllById(any())).thenReturn(List.of(
                emp(ann, "Ann"), emp(bob, "Bob"), emp(cat, "Cat")));
        lenient().when(taxonomyRepository.findAll()).thenReturn(List.of(
                SkillTaxonomy.builder().id(javaId).skill("Java").category("Eng").build(),
                SkillTaxonomy.builder().id(sql).skill("SQL").category("Eng").build()));
        lenient().when(ratingRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of(
                rating(ann, javaId, 4), rating(ann, sql, 3), rating(bob, javaId, 5)));
    }

    private BenchStatus bench(UUID employee, int daysAgo, String bucket) {
        return BenchStatus.builder().id(UUID.randomUUID()).employeeUserId(employee)
                .reasonCode(BenchReasonCode.ROLE_ENDED).startDate(LocalDate.now().minusDays(daysAgo))
                .agingBucket(bucket).build();
    }

    private Employee emp(UUID id, String name) {
        return Employee.builder().userId(id).fullName(name).build();
    }

    private EmployeeSkillRating rating(UUID employee, UUID skill, int self) {
        return EmployeeSkillRating.builder().employeeUserId(employee).skillId(skill).selfRating(self).build();
    }

    // ---- list ----

    @Test
    void list_withoutFilters_ordersIdleLongestFirst() {
        List<String> names = service.list(null, null, "OR").stream().map(BenchListRow::employeeName).toList();

        assertEquals(List.of("Cat", "Ann", "Bob"), names);
    }

    @Test
    void list_skillsOr_keepsAnyMatch_andRanksByMatchThenIdleness() {
        List<BenchListRow> rows = service.list(null, Set.of(javaId, sql), "OR");

        assertEquals(List.of("Ann", "Bob"), rows.stream().map(BenchListRow::employeeName).toList());
        assertEquals(2, rows.get(0).matchScore());
        assertEquals(1, rows.get(1).matchScore());
    }

    @Test
    void list_skillsAnd_requiresEveryRequestedSkill() {
        List<BenchListRow> rows = service.list(null, Set.of(javaId, sql), "AND");

        assertEquals(List.of("Ann"), rows.stream().map(BenchListRow::employeeName).toList());
    }

    @Test
    void list_filtersByAgingBucket() {
        List<BenchListRow> rows = service.list("0-15 days", null, "OR");

        assertEquals(List.of("Bob"), rows.stream().map(BenchListRow::employeeName).toList());
    }

    @Test
    void list_showsSkillsWithTheEffectiveRating() {
        BenchListRow ann = service.list(null, null, "OR").stream().filter(r -> r.employeeName().equals("Ann")).findFirst().orElseThrow();

        assertEquals(List.of("Java (4/5)", "SQL (3/5)"), ann.skills());
    }

    // ---- dashboard ----

    @Test
    void dashboard_reconcilesAgainstEmployeeHeadcount_andEndsTheTrendOnToday() {
        when(employeeRepository.countActiveEmployees()).thenReturn(12L);
        when(bucketRepository.findAllByOrderByMinDaysAsc()).thenReturn(List.of(
                BenchAgingBucket.builder().bucketLabel("0-15 days").minDays(0).maxDays(15).build(),
                BenchAgingBucket.builder().bucketLabel("31-60 days").minDays(31).maxDays(60).build()));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(snapshotRepository.findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(any())).thenReturn(List.of(
                BenchDashboardSnapshot.builder().snapshotDate(yesterday).benchedCount(2).totalHeadcount(12).build(),
                // a snapshot already taken today must not duplicate today's live point
                BenchDashboardSnapshot.builder().snapshotDate(LocalDate.now()).benchedCount(9).totalHeadcount(12).build()));

        BenchDashboard d = service.dashboard();

        assertEquals(3, d.benchedCount());
        assertEquals(12, d.totalHeadcount());
        assertEquals(25.0, d.benchPercent());
        assertEquals(2, d.trend().size());
        assertEquals(yesterday, d.trend().get(0).date());
        assertEquals(LocalDate.now(), d.trend().get(1).date());
        assertEquals(3, d.trend().get(1).benchedCount());
        assertEquals(1L, d.buckets().get(0).count());
        assertEquals(2L, d.buckets().get(1).count());
    }

    @Test
    void dashboard_percentIsZeroWhenThereIsNoHeadcount() {
        when(employeeRepository.countActiveEmployees()).thenReturn(0L);
        when(bucketRepository.findAllByOrderByMinDaysAsc()).thenReturn(List.of());
        when(snapshotRepository.findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(any())).thenReturn(List.of());

        assertEquals(0.0, service.dashboard().benchPercent());
    }

    // ---- allocation options (US-A06) ----

    @Test
    void allocationOptions_listsTheEpisodeEmployeesAllocationsNewestFirst() {
        BenchStatus b = bench(ann, 5, "0-15 days");
        UUID projectId = UUID.randomUUID();
        when(benchRepository.findById(b.getId())).thenReturn(java.util.Optional.of(b));
        when(allocationRepository.findByEmployeeUserIdIn(List.of(ann))).thenReturn(List.of(
                com.nforce.onehr.entity.ProjectAllocation.builder().id(UUID.randomUUID()).employeeUserId(ann)
                        .projectId(projectId).capacityPercent(50).startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 2, 1)).build(),
                com.nforce.onehr.entity.ProjectAllocation.builder().id(UUID.randomUUID()).employeeUserId(ann)
                        .projectId(projectId).capacityPercent(100).startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 7, 1)).build()));
        when(projectRepository.findAllById(any())).thenReturn(List.of(
                com.nforce.onehr.entity.Project.builder().id(projectId).name("Apollo").build()));

        var options = service.allocationOptions(b.getId());

        assertEquals(2, options.size());
        assertEquals(100, options.get(0).capacityPercent());
        assertEquals("Apollo", options.get(0).projectName());
    }

    // ---- cost ----

    @Test
    void cost_multipliesBandHeadcountByAverageCtc_andReportsUnbandedSeparately() {
        UUID band = UUID.randomUUID();
        when(employeeBandRepository.findByEmployeeUserIdIn(any())).thenReturn(List.of(
                EmployeeCompensationBand.builder().employeeUserId(ann).bandId(band).build(),
                EmployeeCompensationBand.builder().employeeUserId(bob).bandId(band).build()));
        when(bandRepository.findAll()).thenReturn(List.of(
                CompensationBand.builder().id(band).bandLabel("B2").avgCtc(new BigDecimal("1000000.00")).build()));

        BenchCost cost = service.cost();

        assertEquals(1, cost.rows().size());
        assertEquals(2, cost.rows().get(0).benchedCount());
        assertEquals(0, new BigDecimal("2000000.00").compareTo(cost.totalCost()));
        assertEquals(1, cost.unbandedCount());
    }
}
