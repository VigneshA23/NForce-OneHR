package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PenaltyAnalyticsResponse;
import com.nforce.onehr.dto.attendance.PenaltyAnalyticsResponse.Bucket;
import com.nforce.onehr.dto.attendance.PenaltyAnalyticsResponse.EmployeeRow;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.AttendancePenaltySpecifications;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PN-06: penalty analytics by rule, department, month and employee, over the caller's scope - the
 * whole organisation for HR, only the caller's direct reports for a manager (the exact scope the
 * Regularize and Cancel Penalties list uses, see {@link AttendancePenaltyService#viewScopeIds}).
 *
 * <p>Counts every penalty that matches the filters, whatever its status, so cancelled and reversed
 * ones can be examined. {@code deductionDays} only adds up the standing ones: a penalty that was
 * cancelled or reversed no longer costs the employee anything.
 */
@Service
@RequiredArgsConstructor
public class PenaltyAnalyticsService {

    /** Guards the query: a range this wide is already a multi-year export, not a dashboard. */
    static final int MAX_RANGE_DAYS = 731;
    private static final String UNASSIGNED = "Unassigned";

    private final AttendancePenaltyService attendancePenaltyService;
    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public PenaltyAnalyticsResponse analyse(String actorEmail, LocalDate from, LocalDate to,
                                            String rule, String department, String status) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("A date range is required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The start date must not be after the end date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("The date range is too wide: choose at most two years");
        }

        boolean organisation = attendancePenaltyService.hasOrganisationViewScope(actorEmail);
        List<UUID> scopeIds = new ArrayList<>(attendancePenaltyService.viewScopeIds(actorEmail));
        Map<UUID, Employee> employees = scopeIds.isEmpty() ? Map.of()
                : employeeRepository.findAllByIdWithScheduleDetails(scopeIds).stream()
                        .collect(Collectors.toMap(Employee::getUserId, Function.identity(), (a, b) -> a));

        Set<String> departments = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        employees.values().forEach(e -> departments.add(departmentOf(e)));

        List<UUID> filteredIds = employees.values().stream()
                .filter(e -> department == null || department.isBlank() || department.equalsIgnoreCase(departmentOf(e)))
                .map(Employee::getUserId)
                .toList();

        List<AttendancePenalty> penalties = filteredIds.isEmpty() ? List.of()
                : attendancePenaltyRepository.findAll(Specification
                        .where(AttendancePenaltySpecifications.employeeUserIdIn(filteredIds))
                        .and(AttendancePenaltySpecifications.incidentDateBetween(from, to))
                        .and(AttendancePenaltySpecifications.statusEquals(status))
                        .and(AttendancePenaltySpecifications.discrepancyTypeEquals(rule)));

        return PenaltyAnalyticsResponse.builder()
                .from(from).to(to)
                .scope(organisation ? "ORGANISATION" : "TEAM")
                .summary(summarise(penalties))
                .byRule(group(penalties, p -> p.getDiscrepancyType(), MyPenaltyService::ruleLabel, true))
                .byDepartment(group(penalties, p -> departmentOf(employees.get(p.getEmployeeUserId())), Function.identity(), true))
                .byMonth(byMonth(penalties, from, to))
                .byEmployee(byEmployee(penalties, employees))
                .departments(new ArrayList<>(departments))
                .build();
    }

    // ── aggregation ─────────────────────────────────────────────────────────────────────────

    private static boolean standing(AttendancePenalty p) {
        return AttendancePenaltyStatus.PENDING_REVIEW.equals(p.getStatus());
    }

    private static BigDecimal days(AttendancePenalty p) {
        return standing(p) && p.getDeductionDays() != null ? p.getDeductionDays() : BigDecimal.ZERO;
    }

    private PenaltyAnalyticsResponse.Summary summarise(List<AttendancePenalty> penalties) {
        int standing = 0, cancelled = 0, reversed = 0;
        BigDecimal deduction = BigDecimal.ZERO;
        for (AttendancePenalty p : penalties) {
            if (standing(p)) standing++;
            else if (AttendancePenaltyStatus.CANCELLED.equals(p.getStatus())) cancelled++;
            else if (AttendancePenaltyStatus.REVERSED.equals(p.getStatus())) reversed++;
            deduction = deduction.add(days(p));
        }
        return PenaltyAnalyticsResponse.Summary.builder()
                .total(penalties.size()).standing(standing).cancelled(cancelled).reversed(reversed)
                .deductionDays(deduction)
                .employeesAffected((int) penalties.stream().map(AttendancePenalty::getEmployeeUserId).distinct().count())
                .build();
    }

    /** Mutable tally behind one {@link Bucket}. */
    private static final class Tally {
        int total, standing, cancelled, reversed;
        BigDecimal deduction = BigDecimal.ZERO;

        void add(AttendancePenalty p) {
            total++;
            if (standing(p)) standing++;
            else if (AttendancePenaltyStatus.CANCELLED.equals(p.getStatus())) cancelled++;
            else if (AttendancePenaltyStatus.REVERSED.equals(p.getStatus())) reversed++;
            deduction = deduction.add(days(p));
        }

        Bucket toBucket(String key, String label) {
            return Bucket.builder().key(key).label(label).total(total).standing(standing)
                    .cancelled(cancelled).reversed(reversed).deductionDays(deduction).build();
        }
    }

    private List<Bucket> group(List<AttendancePenalty> penalties, Function<AttendancePenalty, String> keyOf,
                               Function<String, String> labelOf, boolean worstFirst) {
        Map<String, Tally> tallies = new HashMap<>();
        for (AttendancePenalty p : penalties) {
            tallies.computeIfAbsent(keyOf.apply(p), k -> new Tally()).add(p);
        }
        List<Bucket> buckets = tallies.entrySet().stream()
                .map(e -> e.getValue().toBucket(e.getKey(), labelOf.apply(e.getKey())))
                .collect(Collectors.toCollection(ArrayList::new));
        if (worstFirst) {
            buckets.sort(Comparator.comparingInt(Bucket::getTotal).reversed()
                    .thenComparing(Bucket::getLabel, String.CASE_INSENSITIVE_ORDER));
        }
        return buckets;
    }

    /** Every month from {@code from} to {@code to}, so a quiet month shows as zero rather than vanishing from the trend. */
    private List<Bucket> byMonth(List<AttendancePenalty> penalties, LocalDate from, LocalDate to) {
        Map<YearMonth, Tally> tallies = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            tallies.put(m, new Tally());
        }
        for (AttendancePenalty p : penalties) {
            tallies.computeIfAbsent(YearMonth.from(p.getIncidentDate()), k -> new Tally()).add(p);
        }
        return tallies.entrySet().stream()
                .map(e -> e.getValue().toBucket(e.getKey().toString(), e.getKey().toString()))
                .toList();
    }

    private List<EmployeeRow> byEmployee(List<AttendancePenalty> penalties, Map<UUID, Employee> employees) {
        Map<UUID, List<AttendancePenalty>> perEmployee = penalties.stream()
                .collect(Collectors.groupingBy(AttendancePenalty::getEmployeeUserId));
        List<EmployeeRow> rows = new ArrayList<>();
        perEmployee.forEach((id, list) -> {
            Tally t = new Tally();
            list.forEach(t::add);
            Employee e = employees.get(id);
            rows.add(EmployeeRow.builder()
                    .employeeUserId(id)
                    .fullName(e != null ? e.getFullName() : "Unknown")
                    .employeeCode(e != null ? e.getEmployeeCode() : null)
                    .department(departmentOf(e))
                    .total(t.total).standing(t.standing).cancelled(t.cancelled).reversed(t.reversed)
                    .deductionDays(t.deduction)
                    .topRule(topRule(list))
                    .build());
        });
        rows.sort(Comparator.comparing(EmployeeRow::getDeductionDays).reversed()
                .thenComparing(Comparator.comparingInt(EmployeeRow::getStanding).reversed())
                .thenComparing(Comparator.comparingInt(EmployeeRow::getTotal).reversed())
                .thenComparing(EmployeeRow::getFullName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** The rule behind most of the employee's standing penalties (all of them when none stand). */
    private String topRule(List<AttendancePenalty> list) {
        List<AttendancePenalty> basis = list.stream().filter(PenaltyAnalyticsService::standing).toList();
        if (basis.isEmpty()) basis = list;
        return basis.stream()
                .collect(Collectors.groupingBy(AttendancePenalty::getDiscrepancyType, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(en -> MyPenaltyService.ruleLabel(en.getKey()))
                .orElse(null);
    }

    private static String departmentOf(Employee e) {
        return e != null && e.getDepartment() != null && e.getDepartment().getName() != null
                ? e.getDepartment().getName() : UNASSIGNED;
    }
}
