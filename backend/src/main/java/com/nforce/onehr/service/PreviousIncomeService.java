package com.nforce.onehr.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * US-F12 — income and TDS from the previous employer, one row per month from April up to the joining month.
 * Anything an employee submits is PENDING until a Super Admin approves it; a Super Admin's own entry is approved
 * straight away (and audited). Only APPROVED rows feed the income-tax computation.
 */
@Service
@RequiredArgsConstructor
public class PreviousIncomeService {

    private static final int FY_START_MONTH = 4;

    /** The 12 money columns, in grid order. */
    static final List<String> COLS = List.of("gross", "basic", "hra", "employee_pf", "esi", "lwf", "lwf_employer", "nps_employer",
            "professional_tax", "income_tax", "other_tax_free", "other_taxable");

    public record Row(String period, String label, List<BigDecimal> values, String status) {}
    public record Grid(boolean applicable, int fyStartYear, List<String> columns, List<Row> rows) {}
    public record RowInput(String period, List<BigDecimal> values) {}
    public record Approved(BigDecimal income, BigDecimal tds) {}

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Grid grid(UUID employeeId, int fy) {
        List<YearMonth> months = months(employeeId, fy);
        List<Row> rows = new ArrayList<>();
        for (YearMonth ym : months) {
            List<Row> saved = jdbc.query("SELECT " + String.join(",", COLS) + ", status FROM previous_employment WHERE employee_user_id = ? AND fy_start_year = ? AND period = ?",
                    (rs, i) -> {
                        List<BigDecimal> v = new ArrayList<>();
                        for (int c = 1; c <= COLS.size(); c++) v.add(rs.getBigDecimal(c));
                        return new Row(ym.toString(), label(ym), v, rs.getString(COLS.size() + 1));
                    }, employeeId, fy, ym.toString());
            rows.add(saved.isEmpty() ? new Row(ym.toString(), label(ym), java.util.Collections.nCopies(COLS.size(), BigDecimal.ZERO), "NOT_SUBMITTED") : saved.get(0));
        }
        return new Grid(!months.isEmpty(), fy, COLS, rows);
    }

    @Transactional
    public Grid save(UUID employeeId, int fy, List<RowInput> input, UUID actor, boolean admin) {
        List<String> allowed = months(employeeId, fy).stream().map(YearMonth::toString).toList();
        if (allowed.isEmpty()) throw new IllegalStateException("Previous employment income does not apply for this year");
        for (RowInput r : input) {
            if (!allowed.contains(r.period())) throw new IllegalArgumentException("Month " + r.period() + " is outside the previous-employment period");
            if (r.values() == null || r.values().size() != COLS.size() || r.values().stream().anyMatch(v -> v == null || v.signum() < 0))
                throw new IllegalArgumentException("Each month needs " + COLS.size() + " amounts, none negative");
            String status = admin ? "APPROVED" : "PENDING";
            String sets = String.join(", ", COLS.stream().map(c -> c + " = EXCLUDED." + c).toList());
            Object[] args = new Object[5 + COLS.size() + 2];
            args[0] = employeeId; args[1] = fy; args[2] = r.period();
            for (int i = 0; i < COLS.size(); i++) args[3 + i] = r.values().get(i);
            args[3 + COLS.size()] = status; args[4 + COLS.size()] = actor;
            jdbc.update("INSERT INTO previous_employment (employee_user_id, fy_start_year, period, " + String.join(",", COLS) + ", status, updated_by) VALUES (?,?,?,"
                    + "?,".repeat(COLS.size()) + "?,?) ON CONFLICT (employee_user_id, fy_start_year, period) DO UPDATE SET " + sets
                    + ", status = EXCLUDED.status, updated_by = EXCLUDED.updated_by, approved_by = NULL", java.util.Arrays.copyOf(args, 5 + COLS.size()));
        }
        if (admin) auditService.log(actor, "PREVIOUS_INCOME_ENTERED", employeeId, null,
                "{\"fy\":" + fy + ",\"months\":" + input.size() + "}");
        return grid(employeeId, fy);
    }

    @Transactional
    public Grid approve(UUID employeeId, int fy, UUID admin) {
        int n = jdbc.update("UPDATE previous_employment SET status = 'APPROVED', approved_by = ? WHERE employee_user_id = ? AND fy_start_year = ? AND status = 'PENDING'",
                admin, employeeId, fy);
        if (n > 0) auditService.log(admin, "PREVIOUS_INCOME_APPROVED", employeeId, null, "{\"fy\":" + fy + ",\"months\":" + n + "}");
        return grid(employeeId, fy);
    }

    @Transactional(readOnly = true)
    public Approved approved(UUID employeeId, int fy) {
        return jdbc.queryForObject("SELECT COALESCE(sum(gross), 0), COALESCE(sum(income_tax), 0) FROM previous_employment "
                + "WHERE employee_user_id = ? AND fy_start_year = ? AND status = 'APPROVED'",
                (rs, i) -> new Approved(rs.getBigDecimal(1), rs.getBigDecimal(2)), employeeId, fy);
    }

    /** April .. joining month (inclusive); empty when the employee joined on or before the start of the FY or after it ended. */
    private List<YearMonth> months(UUID employeeId, int fy) {
        List<LocalDate> j = jdbc.queryForList("SELECT joining_date FROM employees WHERE user_id = ?", LocalDate.class, employeeId);
        List<YearMonth> out = new ArrayList<>();
        if (j.isEmpty()) return out;
        LocalDate fyStart = LocalDate.of(fy, FY_START_MONTH, 1);
        LocalDate joined = j.get(0);
        if (!joined.isAfter(fyStart) || joined.isAfter(fyStart.plusYears(1).minusDays(1))) return out;
        for (YearMonth ym = YearMonth.from(fyStart); !ym.isAfter(YearMonth.from(joined)); ym = ym.plusMonths(1)) out.add(ym);
        return out;
    }

    private static String label(YearMonth ym) {
        String m = ym.getMonth().name();
        return m.charAt(0) + m.substring(1).toLowerCase() + " " + ym.getYear();
    }
}
