package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayslipLineItem;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.PayslipPdfService.Line;
import com.nforce.onehr.service.PayslipPdfService.PayslipPdfModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * US-C12 — employee self-service payslip PDF.
 *
 * <p>Access is checked FIRST, before any lookup, via {@link PayslipAccessPolicy} (owner, HR_ADMIN or
 * SUPER_ADMIN; Manager never) — so a refused caller learns nothing about whether a payslip exists.
 * Only payslips whose run is APPROVED or DISBURSED are ever readable; DRAFT/SUBMITTED are filtered in
 * the query itself.
 *
 * <p>A period is the {@code yyyy-MM} of the pay cycle's END date (a 26 Oct – 25 Nov cycle is
 * {@code 2026-11}). {@link #getPayslipPdf} already takes an explicit {@code employeeId} so the
 * HR-side {@code GET /payslips/{employeeId}/{period}} endpoint (Decision #4, not yet confirmed in
 * scope) is a controller-only follow-up; only the self-service endpoint is exposed today.
 */
@Service
@RequiredArgsConstructor
public class PayslipService {

    /** Bytes + content type + filename — same shape as {@code ExpenseService.ReceiptFile}. */
    public record PayslipFile(byte[] data, String contentType, String fileName) {}

    static final Set<String> VISIBLE_RUN_STATUSES = Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED);
    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);
    private static final Set<String> DEDUCTION_TYPES = Set.of(
            SalaryComponentType.DEDUCTION, SalaryComponentType.AD_HOC_DEDUCTION);
    private static final DateTimeFormatter PERIOD_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository lineItemRepository;
    private final EmployeeRepository employeeRepository;
    private final PayGroupRepository payGroupRepository;
    private final UserRepository userRepository;
    private final PayslipAccessPolicy accessPolicy;
    private final PayslipPdfService pdfService;

    /** Periods ({@code yyyy-MM}, newest first) for which the caller has a viewable payslip. */
    @Transactional(readOnly = true)
    public List<String> listMyPeriods(String actorEmail) {
        User actor = resolveUser(actorEmail);
        return payslipRepository.findVisibleForEmployee(actor.getId(), VISIBLE_RUN_STATUSES).stream()
                .map(p -> YearMonth.from(p.getPayrollRun().getCycleEnd()).toString())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    @Transactional(readOnly = true)
    public PayslipFile getMyPayslipPdf(String actorEmail, String period) {
        return getPayslipPdf(actorEmail, resolveUser(actorEmail).getId(), period);
    }

    @Transactional(readOnly = true)
    public PayslipFile getPayslipPdf(String actorEmail, UUID employeeId, String period) {
        User actor = resolveUser(actorEmail);
        accessPolicy.requireCanView(actor, employeeId);
        YearMonth month = parsePeriod(period);

        Payslip payslip = payslipRepository.findVisibleForEmployeeBetween(
                        employeeId, month.atDay(1), month.atEndOfMonth(), VISIBLE_RUN_STATUSES).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No payslip available for " + period));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        byte[] pdf = pdfService.render(toModel(payslip, employee, month));
        return new PayslipFile(pdf, "application/pdf", "payslip-" + employee.getEmployeeCode() + "-" + month + ".pdf");
    }

    private PayslipPdfModel toModel(Payslip payslip, Employee employee, YearMonth month) {
        PayrollRun run = payslip.getPayrollRun();
        PayGroup payGroup = payGroupRepository.findById(run.getPayGroupId()).orElse(null);
        List<PayslipLineItem> items = lineItemRepository.findByPayslipId(payslip.getId());
        return new PayslipPdfModel(
                payGroup == null ? null : (payGroup.getLegalEntity() != null ? payGroup.getLegalEntity() : payGroup.getName()),
                payGroup == null ? "INR" : payGroup.getCurrency(),
                PERIOD_LABEL.format(month),
                run.getCycleStart(), run.getCycleEnd(),
                employee.getFullName(), employee.getEmployeeCode(),
                employee.getDepartment() == null ? null : employee.getDepartment().getName(),
                employee.getDesignation() == null ? null : employee.getDesignation().getTitle(),
                employee.getJoiningDate(),
                payslip.getCycleDays(), payslip.getEmployedDays(), payslip.getLopDays(),
                lines(items, EARNING_TYPES), lines(items, DEDUCTION_TYPES),
                payslip.getGrossPay(), payslip.getTotalDeductions(), payslip.getNetPay());
    }

    /** Only earning/deduction types are printed; anything else (e.g. a contribution) is never shown. */
    private static List<Line> lines(List<PayslipLineItem> items, Set<String> types) {
        return items.stream()
                .filter(i -> types.contains(i.getComponentType()))
                .sorted(Comparator.comparing(PayslipLineItem::getComponentName))
                .map(i -> new Line(i.getComponentName(), i.getAmount()))
                .toList();
    }

    private static YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Period must be in yyyy-MM format, e.g. 2026-11");
        }
    }

    private User resolveUser(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
