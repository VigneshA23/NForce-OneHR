package com.nforce.onehr.service;

import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * US-F09 — the caller's own yearly income-tax computation and month-wise TDS. Slabs, rebate, standard
 * deduction, surcharge and cess come from the tax_* tables per financial year and regime (US-F17 will
 * maintain them). Past months use the real released payslip; the rest are projected at the current
 * monthly earnings (no bonuses or revisions). Declarations, proofs and previous-employer income do
 * not exist yet, so they contribute 0.
 */
@Service
@RequiredArgsConstructor
public class IncomeTaxService {

    public record Slab(BigDecimal from, BigDecimal to, BigDecimal rate) {}
    public record SlabTax(BigDecimal from, BigDecimal to, BigDecimal rate, BigDecimal taxable, BigDecimal tax) {}
    public record Rules(List<Slab> slabs, BigDecimal standardDeduction, BigDecimal rebateThreshold, BigDecimal rebateMax,
                        BigDecimal cessRate, List<Slab> surcharge) {}
    /** Result of the pure maths: sections P, Q and T. */
    public record Tax(List<SlabTax> slabTax, BigDecimal slabTotal, BigDecimal rebate, BigDecimal grossTax,
                      BigDecimal surcharge, BigDecimal cess, BigDecimal netPayable) {}

    public record Month(String period, String label, boolean actual, BigDecimal gross, BigDecimal tds, BigDecimal cumulativeTds) {}
    public record Computation(int fyStartYear, String fyLabel, String regime, List<Month> months,
                              BigDecimal grossEarnings, BigDecimal previousEmployerIncome, BigDecimal standardDeduction,
                              BigDecimal netTaxableIncome, Tax tax, BigDecimal taxPaidTillNow, BigDecimal remainingTax,
                              boolean configured, BigDecimal deductions, BigDecimal otherIncome, BigDecimal previousEmployerTds) {}

    private static final int FY_START_MONTH = 4;

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final MySalaryService salaryService;
    private final TaxDeclarationService declarationService;
    private final PreviousIncomeService previousIncomeService;
    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository lineItemRepository;

    /** Pure tax maths on net taxable income (after the standard deduction). Rounding: whole rupees at the end. */
    static Tax compute(Rules r, BigDecimal income) {
        List<SlabTax> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Slab s : r.slabs()) {
            BigDecimal top = s.to() == null ? income : income.min(s.to());
            BigDecimal taxable = top.subtract(s.from()).max(BigDecimal.ZERO);
            BigDecimal tax = taxable.multiply(s.rate());
            rows.add(new SlabTax(s.from(), s.to(), s.rate(), taxable, tax));
            total = total.add(tax);
        }
        BigDecimal rebate = income.compareTo(r.rebateThreshold()) <= 0 ? total.min(r.rebateMax()) : BigDecimal.ZERO;
        BigDecimal grossTax = total.subtract(rebate);
        BigDecimal rate = BigDecimal.ZERO;
        for (Slab s : r.surcharge()) if (income.compareTo(s.from()) > 0) rate = s.rate();
        BigDecimal surcharge = grossTax.multiply(rate);
        BigDecimal cess = grossTax.add(surcharge).multiply(r.cessRate());
        BigDecimal net = grossTax.add(surcharge).add(cess).setScale(0, RoundingMode.HALF_UP);
        return new Tax(rows, total, rebate, grossTax, surcharge, cess, net);
    }

    @Transactional(readOnly = true)
    public Computation compute(String actorEmail, Integer fyStartYear) {
        return compute(userRepository.findByEmail(actorEmail).orElseThrow(() -> new IllegalStateException("Actor not found")).getId(), fyStartYear);
    }

    @Transactional(readOnly = true)
    public Computation compute(UUID employeeId, Integer fyStartYear) {
        FiscalYear current = FiscalYear.containing(LocalDate.now(), FY_START_MONTH);
        int year = fyStartYear != null ? fyStartYear : current.start().getYear();
        String regime = declarationService.regimeOf(employeeId);
        Rules rules = rules(year, regime);

        BigDecimal annual = salaryService.currentAnnualEarnings(employeeId);
        BigDecimal projectedMonthly = annual == null ? BigDecimal.ZERO : annual.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        LocalDate fyStart = LocalDate.of(year, FY_START_MONTH, 1);
        List<Payslip> payslips = payslipRepository.findReleasedForEmployeeBetween(employeeId, fyStart,
                fyStart.plusYears(1).minusDays(1), PayslipService.VISIBLE_RUN_STATUSES);

        List<Month> months = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        int projected = 0;
        for (int i = 0; i < 12; i++) {
            YearMonth ym = YearMonth.from(fyStart.plusMonths(i));
            Payslip p = payslips.stream().filter(x -> YearMonth.from(x.getPayrollRun().getCycleEnd()).equals(ym)).findFirst().orElse(null);
            BigDecimal g = p != null ? p.getGrossPay() : projectedMonthly;
            BigDecimal tds = p != null ? tdsOf(p) : null;
            gross = gross.add(g);
            if (p != null) paid = paid.add(tds); else projected++;
            months.add(new Month(ym.toString(), ym.getMonth().name().substring(0, 1) + ym.getMonth().name().substring(1).toLowerCase() + " " + ym.getYear(),
                    p != null, g, tds, null));
        }
        PreviousIncomeService.Approved prev = previousIncomeService.approved(employeeId, year);
        BigDecimal previous = prev.income();
        paid = paid.add(prev.tds());
        TaxDeclarationService.Totals decl = declarationService.totals(employeeId, year);
        BigDecimal taxable = gross.add(previous).add(decl.otherIncome()).subtract(rules.standardDeduction()).subtract(decl.deductions()).max(BigDecimal.ZERO);
        Tax tax = compute(rules, taxable);
        BigDecimal remaining = tax.netPayable().subtract(paid).max(BigDecimal.ZERO);

        // Month-on-month TDS: real deductions for past months, the remaining tax spread over the projected months.
        BigDecimal perMonth = projected == 0 ? BigDecimal.ZERO : remaining.divide(BigDecimal.valueOf(projected), 0, RoundingMode.HALF_UP);
        BigDecimal cumulative = BigDecimal.ZERO;
        List<Month> out = new ArrayList<>();
        for (Month m : months) {
            BigDecimal t = m.actual() ? m.tds() : perMonth;
            cumulative = cumulative.add(t);
            out.add(new Month(m.period(), m.label(), m.actual(), m.gross(), t, cumulative));
        }
        return new Computation(year, "FY " + year + "-" + String.valueOf(year + 1).substring(2), regime, out, gross, previous,
                rules.standardDeduction(), taxable, tax, paid, remaining, !rules.slabs().isEmpty(),
                decl.deductions(), decl.otherIncome(), prev.tds());
    }

    /** TDS already deducted on a payslip: any deduction line named TDS / Income Tax (payroll does not compute it yet, so usually 0). */
    private BigDecimal tdsOf(Payslip p) {
        return lineItemRepository.findByPayslipId(p.getId()).stream()
                .filter(i -> i.getComponentName() != null && i.getComponentName().toLowerCase().matches(".*\\b(tds|income tax)\\b.*"))
                .map(i -> i.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Rules rules(int year, String regime) {
        List<Slab> slabs = jdbc.query("SELECT from_amount, to_amount, rate FROM tax_slabs WHERE fy_start_year = ? AND regime = ? ORDER BY from_amount",
                (rs, i) -> new Slab(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), year, regime);
        List<Slab> surcharge = jdbc.query("SELECT above_amount, NULL, rate FROM tax_surcharge WHERE fy_start_year = ? AND regime = ? ORDER BY above_amount",
                (rs, i) -> new Slab(rs.getBigDecimal(1), null, rs.getBigDecimal(3)), year, regime);
        List<Rules> cfg = jdbc.query("SELECT standard_deduction, rebate_threshold, rebate_max, cess_rate FROM tax_year_config WHERE fy_start_year = ? AND regime = ?",
                (rs, i) -> new Rules(slabs, rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), surcharge), year, regime);
        return cfg.isEmpty() ? new Rules(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()) : cfg.get(0);
    }
}
