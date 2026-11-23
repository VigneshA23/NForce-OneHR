package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayslipLineItem;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ArrearsRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.entity.PayslipSettings;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipLineItemRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.PayslipSettingsRepository;
import com.nforce.onehr.repository.SalaryComponentRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.PayslipPdfService.ArrearLine;
import com.nforce.onehr.service.PayslipPdfService.LeaveLine;
import com.nforce.onehr.service.PayslipPdfService.Line;
import com.nforce.onehr.service.PayslipPdfService.PayslipExtras;
import com.nforce.onehr.service.PayslipPdfService.PayslipPdfModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C12 — employee self-service payslip PDF.
 *
 * <p>Access is checked FIRST, before any lookup, via {@link PayslipAccessPolicy} (owner, HR_ADMIN or
 * SUPER_ADMIN; Manager never) — so a refused caller learns nothing about whether a payslip exists.
 * Only payslips whose run is APPROVED or DISBURSED are ever readable; DRAFT/SUBMITTED are filtered in
 * the query itself. An employee viewing their OWN payslip additionally requires {@code released}
 * (see {@link com.nforce.onehr.repository.PayslipRepository#findReleasedForEmployee}) — set in bulk
 * by {@code PayrollRunService#distributePayslips}, never automatically by the run reaching APPROVED/
 * DISBURSED. HR viewing someone else's payslip from the admin console is exempt from that gate —
 * deciding whether to distribute an unreleased payslip is the point of that screen.
 *
 * <p>A period is the {@code yyyy-MM} of the pay cycle's END date (a 26 Oct – 25 Nov cycle is
 * {@code 2026-11}). {@link #getPayslipPdf} already takes an explicit {@code employeeId} so the
 * HR-side {@code GET /payslips/{employeeId}/{period}} endpoint (Decision #4, not yet confirmed in
 * scope) is a controller-only follow-up; only the self-service endpoint is exposed today.
 */
@Service
public class PayslipService {

    /** Bytes + content type + filename — same shape as {@code ExpenseService.ReceiptFile}. */
    public record PayslipFile(byte[] data, String contentType, String fileName) {}

    static final Set<String> VISIBLE_RUN_STATUSES = Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED);
    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);
    private static final Set<String> DEDUCTION_TYPES = Set.of(
            SalaryComponentType.DEDUCTION, SalaryComponentType.AD_HOC_DEDUCTION);
    private static final Set<String> CONTRIBUTION_TYPES = Set.of(SalaryComponentType.CONTRIBUTION);
    private static final DateTimeFormatter PERIOD_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository lineItemRepository;
    private final EmployeeRepository employeeRepository;
    private final PayGroupRepository payGroupRepository;
    private final UserRepository userRepository;
    private final PayslipAccessPolicy accessPolicy;
    private final PayslipPdfService pdfService;
    private final PayslipSettingsRepository payslipSettingsRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final ArrearsRepository arrearsRepository;
    private final EmailService emailService;
    private final int fiscalYearStartMonth;

    // Same config key PayrollYtdService reads — one fiscal-year-start setting for the whole app,
    // not re-decided here.
    public PayslipService(PayslipRepository payslipRepository, PayslipLineItemRepository lineItemRepository,
                          EmployeeRepository employeeRepository, PayGroupRepository payGroupRepository,
                          UserRepository userRepository, PayslipAccessPolicy accessPolicy, PayslipPdfService pdfService,
                          PayslipSettingsRepository payslipSettingsRepository, SalaryComponentRepository salaryComponentRepository,
                          LeaveBalanceRepository leaveBalanceRepository, ArrearsRepository arrearsRepository, EmailService emailService,
                          @Value("${app.payroll.fiscal-year-start-month:4}") int fiscalYearStartMonth) {
        this.payslipRepository = payslipRepository;
        this.lineItemRepository = lineItemRepository;
        this.employeeRepository = employeeRepository;
        this.payGroupRepository = payGroupRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.pdfService = pdfService;
        this.payslipSettingsRepository = payslipSettingsRepository;
        this.salaryComponentRepository = salaryComponentRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.arrearsRepository = arrearsRepository;
        this.emailService = emailService;
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    /** Periods ({@code yyyy-MM}, newest first) for which the caller has a viewable payslip —
     * always self-service, so this is always released=true-gated (see {@link
     * com.nforce.onehr.repository.PayslipRepository#findReleasedForEmployee}'s own doc). */
    @Transactional(readOnly = true)
    public List<String> listMyPeriods(String actorEmail) {
        User actor = resolveUser(actorEmail);
        return payslipRepository.findReleasedForEmployee(actor.getId(), VISIBLE_RUN_STATUSES).stream()
                .map(p -> YearMonth.from(p.getPayrollRun().getCycleEnd()).toString())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /** US-F02 — latest released payslip of the caller (same released + APPROVED/DISBURSED gate as
     * {@link #listMyPeriods}, so drafts never show); empty when none. workingDays = days employed in the cycle. */
    public record LatestPayslipSummary(String period, LocalDate cycleStart, LocalDate cycleEnd,
                                       int workingDays, BigDecimal lopDays) {}

    @Transactional(readOnly = true)
    public java.util.Optional<LatestPayslipSummary> getMyLatestSummary(String actorEmail) {
        return payslipRepository.findReleasedForEmployee(resolveUser(actorEmail).getId(), VISIBLE_RUN_STATUSES)
                .stream().findFirst()
                .map(p -> new LatestPayslipSummary(YearMonth.from(p.getPayrollRun().getCycleEnd()).toString(),
                        p.getPayrollRun().getCycleStart(), p.getPayrollRun().getCycleEnd(),
                        p.getEmployedDays(), p.getLopDays()));
    }

    /** US-F08 on-screen payslip. Same data as the PDF, with PAN/UAN masked and YTD always filled in. */
    public record PayslipPreview(String companyName, String companyAddress, String periodLabel, LocalDate cycleStart, LocalDate cycleEnd,
                                 String employeeName, String employeeCode, LocalDate joiningDate, String department, String designation,
                                 String paymentMode, String uan, String pan, int totalDays, BigDecimal lopDays, BigDecimal daysPayable,
                                 List<Line> earnings, List<Line> contributions, List<Line> deductions,
                                 BigDecimal totalEarnings, BigDecimal totalDeductions, BigDecimal netPay) {}

    @Transactional(readOnly = true)
    public PayslipPreview getMyPreview(String actorEmail, String period) {
        UUID me = resolveUser(actorEmail).getId();
        YearMonth month = parsePeriod(period);
        Payslip payslip = payslipRepository.findReleasedForEmployeeBetween(me, month.atDay(1), month.atEndOfMonth(), VISIBLE_RUN_STATUSES)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("No payslip available for " + period));
        Employee e = employeeRepository.findById(me).orElseThrow(() -> new NoSuchElementException("Employee not found"));
        PayrollRun run = payslip.getPayrollRun();
        PayGroup pg = payGroupRepository.findById(run.getPayGroupId()).orElse(null);
        List<PayslipLineItem> items = lineItemRepository.findByPayslipId(payslip.getId());
        Map<UUID, SalaryComponent> components = salaryComponentRepository
                .findAllById(items.stream().map(PayslipLineItem::getComponentId).distinct().toList())
                .stream().collect(Collectors.toMap(SalaryComponent::getId, c -> c));
        Map<String, BigDecimal> ytd = ytdByComponentName(me, run.getCycleEnd());
        return new PayslipPreview(
                pg == null ? null : (pg.getLegalEntity() != null ? pg.getLegalEntity() : pg.getName()), pg == null ? null : pg.getAddress(),
                PERIOD_LABEL.format(month), run.getCycleStart(), run.getCycleEnd(),
                e.getFullName(), e.getEmployeeCode(), e.getJoiningDate(),
                e.getDepartment() == null ? null : e.getDepartment().getName(),
                e.getDesignation() == null ? null : e.getDesignation().getTitle(),
                paymentMode(e), mask(e.getUanNumber()), mask(e.getPanNumber()),
                payslip.getCycleDays(), payslip.getLopDays(), BigDecimal.valueOf(payslip.getEmployedDays()).subtract(payslip.getLopDays()).max(BigDecimal.ZERO),
                lines(items, EARNING_TYPES, components, ytd), lines(items, CONTRIBUTION_TYPES, components, ytd), lines(items, DEDUCTION_TYPES, components, ytd),
                payslip.getGrossPay(), payslip.getTotalDeductions(), payslip.getNetPay());
    }

    /** Keeps the last 4 characters only (PAN / UAN on a payslip). */
    static String mask(String id) {
        return id == null || id.length() <= 4 ? id : "X".repeat(id.length() - 4) + id.substring(id.length() - 4);
    }

    @Transactional(readOnly = true)
    public PayslipFile getMyPayslipPdf(String actorEmail, String period) {
        return getPayslipPdf(actorEmail, resolveUser(actorEmail).getId(), period);
    }

    @Transactional(readOnly = true)
    public PayslipFile getPayslipPdf(String actorEmail, UUID employeeId, String period) {
        Rendered r = renderForEmployee(actorEmail, employeeId, period);
        return new PayslipFile(r.pdf(), "application/pdf", "payslip-" + r.employee().getEmployeeCode() + "-" + r.month() + ".pdf");
    }

    /** Admin-triggered "Email Payslip" — same access/lookup/render path as {@link #getPayslipPdf},
     * just delivered as an attachment instead of a download. Fire-and-forget by design, same as
     * every other transactional email in this app (see {@code EmailService#sendAsync}'s own note):
     * a failed send never rolls back or re-throws, it only logs — the payslip itself was already
     * real before this was ever called.
     * @return true once Resend accepted the message, false on any send failure. */
    @Transactional(readOnly = true)
    public boolean sendPayslipEmail(String actorEmail, UUID employeeId, String period) {
        Rendered r = renderForEmployee(actorEmail, employeeId, period);
        User employeeUser = r.employee().getUser();
        if (employeeUser == null || employeeUser.getEmail() == null) {
            throw new IllegalStateException("This employee has no email address on file");
        }
        String periodLabel = PERIOD_LABEL.format(r.month());
        String fileName = "payslip-" + r.employee().getEmployeeCode() + "-" + r.month() + ".pdf";
        return emailService.sendPayslipEmail(employeeUser.getEmail(), r.employee().getFullName(), periodLabel, r.pdf(), fileName).join();
    }

    private record Rendered(Employee employee, YearMonth month, byte[] pdf) {}

    private Rendered renderForEmployee(String actorEmail, UUID employeeId, String period) {
        User actor = resolveUser(actorEmail);
        accessPolicy.requireCanView(actor, employeeId);
        YearMonth month = parsePeriod(period);

        // Self-service (actor viewing their OWN payslip) requires released=true — an employee must
        // never see their own payslip before HR distributes it. HR viewing someone ELSE's payslip
        // from the admin console (View/Email/bulk-zip/Distribute) deliberately skips that gate —
        // deciding whether/how to distribute an unreleased payslip is the whole point of that screen.
        boolean selfService = actor.getId().equals(employeeId);
        List<Payslip> candidates = selfService
                ? payslipRepository.findReleasedForEmployeeBetween(employeeId, month.atDay(1), month.atEndOfMonth(), VISIBLE_RUN_STATUSES)
                : payslipRepository.findVisibleForEmployeeBetween(employeeId, month.atDay(1), month.atEndOfMonth(), VISIBLE_RUN_STATUSES);
        Payslip payslip = candidates.stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No payslip available for " + period));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        PayslipSettings settings = payslipSettingsRepository.findById(payslip.getPayrollRun().getPayGroupId()).orElse(null);
        String password = (settings != null && settings.isPasswordProtected() && employee.getPanNumber() != null)
                ? employee.getPanNumber().toUpperCase(Locale.ENGLISH) : null;
        byte[] pdf = pdfService.render(toModel(payslip, employee, month, settings), password);
        return new Rendered(employee, month, pdf);
    }

    private PayslipPdfModel toModel(Payslip payslip, Employee employee, YearMonth month, PayslipSettings settings) {
        PayrollRun run = payslip.getPayrollRun();
        PayGroup payGroup = payGroupRepository.findById(run.getPayGroupId()).orElse(null);
        List<PayslipLineItem> items = lineItemRepository.findByPayslipId(payslip.getId());
        Map<UUID, SalaryComponent> components = salaryComponentRepository
                .findAllById(items.stream().map(PayslipLineItem::getComponentId).distinct().toList())
                .stream().collect(Collectors.toMap(SalaryComponent::getId, c -> c));
        Map<String, BigDecimal> ytdByName = (settings != null && settings.isIncludeYtdColumn())
                ? ytdByComponentName(payslip.getEmployeeId(), run.getCycleEnd())
                : Map.of();
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
                lines(items, EARNING_TYPES, components, ytdByName), lines(items, DEDUCTION_TYPES, components, ytdByName),
                payslip.getGrossPay(), payslip.getTotalDeductions(), payslip.getNetPay(),
                settings == null ? null : settings.getDocumentHeaderText(),
                settings == null ? null : settings.getFooterText(),
                settings == null ? null : settings.getLogo(),
                buildExtras(payslip, employee, settings, items, components, ytdByName),
                payGroup == null ? null : payGroup.getAddress());
    }

    /** Everything Keka restyle's Body/Header Customisation screen (§3.9) controls — see {@link
     * PayslipExtras}'s own doc for why these are bundled. A pay group that's never configured
     * this returns {@link PayslipExtras#none()}, matching the PDF's long-standing default. */
    private PayslipExtras buildExtras(Payslip payslip, Employee employee, PayslipSettings settings,
                                       List<PayslipLineItem> items, Map<UUID, SalaryComponent> components, Map<String, BigDecimal> ytdByName) {
        if (settings == null) return PayslipExtras.none();

        List<LeaveLine> leaveSummary = settings.isIncludeLeaveSummary()
                ? leaveBalanceRepository.findByEmployeeUserIdAndYear(payslip.getEmployeeId(), payslip.getPayrollRun().getCycleEnd().getYear())
                        .stream()
                        .map(b -> new LeaveLine(b.getLeaveType().getName(), b.getTotalDays().subtract(b.getUsedDays())))
                        .toList()
                : List.of();

        List<ArrearLine> arrearBreakup = settings.isIncludeArrearBreakup()
                ? arrearsRepository.findByEmployeeIdAndAppliedToRunId(payslip.getEmployeeId(), payslip.getPayrollRun().getId())
                        .stream()
                        .map(a -> new ArrearLine(a.getReason(), a.getSourceCycleStart(), a.getSourceCycleEnd(), a.getAmount()))
                        .toList()
                : List.of();

        List<Line> contributions = settings.isIncludeEmployerContributions()
                ? lines(items, CONTRIBUTION_TYPES, components, ytdByName)
                : List.of();

        return new PayslipExtras(
                settings.isExcludeNaFields(), settings.getEarningsHeading(), settings.getDeductionsHeading(),
                PayslipFields.decode(settings.getPayslipFields()),
                mask(employee.getUanNumber()), employee.getEsiNumber(), mask(employee.getPanNumber()), paymentMode(employee),
                settings.getLogoAlignment(), settings.getLogoSize(), settings.getLogoShape(), leaveSummary, arrearBreakup,
                settings.isIncludeYtdColumn(), contributions);
    }

    /** "Bank Transfer" once both bank account number and IFSC are on file, else "Cash" — no separate
     * payment-mode field exists on {@code Employee}; this is the same bank-details check {@link
     * PayrollRunService#bankTransferExcel} already uses to decide who makes it into that export. */
    private static String paymentMode(Employee employee) {
        boolean hasBankDetails = employee.getBankAccountNumber() != null && !employee.getBankAccountNumber().isBlank()
                && employee.getBankIfsc() != null && !employee.getBankIfsc().isBlank();
        return hasBankDetails ? "Bank Transfer" : "Cash";
    }

    /** [componentName -> SUM(amount)] from the start of this cycle-end's fiscal year through this
     * same cycle end (inclusive), across every APPROVED/DISBURSED payslip for this employee —
     * reuses the exact aggregation {@link PayrollYtdService} already built for US-C17, just scoped
     * to this payslip's own cycle end instead of "today". */
    private Map<String, BigDecimal> ytdByComponentName(UUID employeeId, LocalDate cycleEnd) {
        FiscalYear fy = FiscalYear.containing(cycleEnd, fiscalYearStartMonth);
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] row : lineItemRepository.sumByComponentForEmployee(employeeId, fy.start(), cycleEnd, VISIBLE_RUN_STATUSES)) {
            map.put((String) row[0], (BigDecimal) row[2]);
        }
        return map;
    }

    /**
     * Only earning/deduction/contribution types are printed; anything else is never shown. A
     * component the catalog has marked {@code shownOnPayslip = false} is dropped, and the rest
     * print in the catalog's own {@code displayOrder} (the "Order of Components" screen) —
     * falling back to {@code shown = true} / order {@code 0} for a component no longer in the
     * catalog, so an old payslip never loses a line item over a later catalog change. {@code
     * ytdByName} is empty unless "Include YTD" is on, in which case a line's own YTD sum already
     * includes this cycle's own amount (the query covers the fiscal year through this cycle end).
     */
    private static List<Line> lines(List<PayslipLineItem> items, Set<String> types, Map<UUID, SalaryComponent> components,
                                      Map<String, BigDecimal> ytdByName) {
        return items.stream()
                .filter(i -> types.contains(i.getComponentType()))
                .filter(i -> {
                    SalaryComponent c = components.get(i.getComponentId());
                    return c == null || c.isShownOnPayslip();
                })
                .sorted(Comparator.<PayslipLineItem>comparingInt(i -> {
                            SalaryComponent c = components.get(i.getComponentId());
                            return c == null ? 0 : c.getDisplayOrder();
                        })
                        .thenComparing(PayslipLineItem::getComponentName))
                .map(i -> new Line(i.getComponentName(), i.getAmount(), ytdByName.get(i.getComponentName())))
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
