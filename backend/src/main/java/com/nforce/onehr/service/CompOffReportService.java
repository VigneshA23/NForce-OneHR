package com.nforce.onehr.service;

import com.nforce.onehr.dto.compoff.CompOffDtos.BalanceDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.CreditDto;
import com.nforce.onehr.dto.compoff.CompOffDtos.DepartmentRow;
import com.nforce.onehr.dto.compoff.CompOffDtos.Report;
import com.nforce.onehr.dto.compoff.CompOffDtos.SummaryRow;
import com.nforce.onehr.entity.CompOffCredit;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.CompOffCreditRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Employee-wise and department-wise Comp-Off summaries and their Excel export. Callers who can view
 * everyone (leave:VIEW:ALL) see the organisation; managers (leave:VIEW:TEAM) see their direct reports only.
 */
@Service
@RequiredArgsConstructor
public class CompOffReportService {

    private final CompOffCreditRepository creditRepository;
    private final CompOffService compOffService;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EffectivePermissionService permissionService;

    @Transactional(readOnly = true)
    public Report report(UUID employeeId, UUID departmentId, UUID managerId, LocalDate from, LocalDate to, String actorEmail) {
        return build(filteredCredits(employeeId, departmentId, managerId, from, to, actorEmail));
    }

    /** Ledger rows behind the summaries - generation, utilisation and expiry state per credit. */
    @Transactional(readOnly = true)
    public List<CreditDto> credits(UUID employeeId, UUID departmentId, UUID managerId, LocalDate from, LocalDate to, String actorEmail) {
        return compOffService.toCreditDtos(filteredCredits(employeeId, departmentId, managerId, from, to, actorEmail));
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID employeeId, UUID departmentId, UUID managerId, LocalDate from, LocalDate to, String actorEmail) {
        List<CompOffCredit> rows = filteredCredits(employeeId, departmentId, managerId, from, to, actorEmail);
        Report report = build(rows);
        List<CreditDto> ledger = compOffService.toCreditDtos(rows);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);

            Sheet emp = wb.createSheet("Employee summary");
            header(emp, head, "Code", "Employee", "Department", "Manager", "Earned", "Available", "Utilized", "Pending", "Expired", "Deducted");
            int r = 1;
            for (SummaryRow s : report.employees()) {
                row(emp, r++, s.employeeCode(), s.employeeName(), s.departmentName(), s.managerName(), s.earned(),
                        s.available(), s.utilized(), s.pending(), s.expired(), s.adjusted());
            }
            Sheet dept = wb.createSheet("Department summary");
            header(dept, head, "Department", "Employees", "Earned", "Available", "Utilized", "Pending", "Expired", "Deducted");
            r = 1;
            for (DepartmentRow d : report.departments()) {
                row(dept, r++, d.departmentName(), d.employees(), d.earned(), d.available(), d.utilized(), d.pending(),
                        d.expired(), d.adjusted());
            }
            Sheet led = wb.createSheet("Credits");
            header(led, head, "Code", "Employee", "Department", "Source", "Work date", "Earned on", "Expires on", "Credit",
                    "Used", "Deducted", "Expired", "Remaining", "Status", "Reason");
            r = 1;
            for (CreditDto c : ledger) {
                row(led, r++, c.employeeCode(), c.employeeName(), c.departmentName(), c.source(), c.workDate(), c.earnedOn(),
                        c.expiresOn(), c.creditDays(), c.usedDays(), c.deductedDays(), c.expiredDays(), c.remainingDays(),
                        c.status(), c.reason());
            }
            for (Sheet s : List.of(emp, dept, led)) {
                for (int i = 0; i < s.getRow(0).getLastCellNum(); i++) s.setColumnWidth(i, 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<CompOffCredit> filteredCredits(UUID employeeId, UUID departmentId, UUID managerId, LocalDate from,
                                                LocalDate to, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail).orElseThrow(() -> new IllegalStateException("Actor not found"));
        Set<UUID> scope = null; // null = everyone
        if (!permissionService.hasAuthority(actor, "leave:VIEW:ALL")) {
            scope = Set.copyOf(historyRepository.findCurrentDirectReportIds(actor.getId()));
        }
        if (managerId != null) {
            Set<UUID> reports = Set.copyOf(historyRepository.findCurrentDirectReportIds(managerId));
            scope = scope == null ? reports : scope.stream().filter(reports::contains).collect(Collectors.toSet());
        }
        if (employeeId != null) {
            final Set<UUID> s = scope;
            scope = (s == null || s.contains(employeeId)) ? Set.of(employeeId) : Set.of();
        }
        List<CompOffCredit> credits = scope == null ? creditRepository.findAllByOrderByEarnedOnDesc()
                : scope.isEmpty() ? List.of() : creditRepository.findByEmployeeUserIdInOrderByEarnedOnDesc(scope);
        Map<UUID, Employee> emps = compOffService.employeesById(
                credits.stream().map(CompOffCredit::getEmployeeUserId).collect(Collectors.toSet()));
        return credits.stream()
                .filter(c -> from == null || !c.getEarnedOn().isBefore(from))
                .filter(c -> to == null || !c.getEarnedOn().isAfter(to))
                .filter(c -> departmentId == null || (emps.get(c.getEmployeeUserId()) != null
                        && emps.get(c.getEmployeeUserId()).getDepartment() != null
                        && departmentId.equals(emps.get(c.getEmployeeUserId()).getDepartment().getId())))
                .toList();
    }

    private Report build(List<CompOffCredit> credits) {
        Map<UUID, List<CompOffCredit>> byEmployee = credits.stream()
                .collect(Collectors.groupingBy(CompOffCredit::getEmployeeUserId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Employee> emps = compOffService.employeesById(byEmployee.keySet());
        Map<UUID, String> managers = new java.util.HashMap<>();
        Map<UUID, UUID> managerIds = new java.util.HashMap<>();
        if (!byEmployee.isEmpty()) {
            for (Object[] m : historyRepository.findCurrentManagerInfoByEmployeeIds(byEmployee.keySet())) {
                managers.put((UUID) m[0], m[3] != null ? (String) m[3] : (String) m[2]);
                managerIds.put((UUID) m[0], (UUID) m[1]);
            }
        }
        List<SummaryRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, List<CompOffCredit>> e : byEmployee.entrySet()) {
            Employee emp = emps.get(e.getKey());
            BalanceDto b = compOffService.balance(e.getKey(), e.getValue());
            rows.add(new SummaryRow(e.getKey(), emp != null ? emp.getEmployeeCode() : null,
                    emp != null ? emp.getFullName() : "Unknown",
                    emp != null && emp.getDepartment() != null ? emp.getDepartment().getName() : "Unassigned",
                    managers.get(e.getKey()), b.earned(), b.available(), b.utilized(), b.pending(), b.expired(), b.adjusted(),
                    emp != null && emp.getDepartment() != null ? emp.getDepartment().getId() : null, managerIds.get(e.getKey())));
        }
        rows.sort(java.util.Comparator.comparing(SummaryRow::employeeName, String.CASE_INSENSITIVE_ORDER));

        Map<String, List<SummaryRow>> byDept = rows.stream()
                .collect(Collectors.groupingBy(SummaryRow::departmentName, java.util.TreeMap::new, Collectors.toList()));
        List<DepartmentRow> depts = byDept.entrySet().stream().map(d -> new DepartmentRow(d.getKey(), d.getValue().size(),
                sum(d.getValue(), SummaryRow::earned), sum(d.getValue(), SummaryRow::available),
                sum(d.getValue(), SummaryRow::utilized), sum(d.getValue(), SummaryRow::pending),
                sum(d.getValue(), SummaryRow::expired), sum(d.getValue(), SummaryRow::adjusted))).toList();
        return new Report(rows, depts);
    }

    private static BigDecimal sum(List<SummaryRow> rows, java.util.function.Function<SummaryRow, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void header(Sheet sheet, CellStyle style, String... titles) {
        Row r = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(titles[i]);
            c.setCellStyle(style);
        }
    }

    private static void row(Sheet sheet, int index, Object... values) {
        Row r = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            Cell c = r.createCell(i);
            if (v instanceof BigDecimal d) c.setCellValue(d.doubleValue());
            else if (v instanceof Number n) c.setCellValue(n.doubleValue());
            else if (v != null) c.setCellValue(v.toString());
        }
    }
}
