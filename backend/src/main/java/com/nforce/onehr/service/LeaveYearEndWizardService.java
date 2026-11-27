package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.AdjustYearEndLineRequest;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunDetail;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunLineDto;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunSummary;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveCarryForwardTransfer;
import com.nforce.onehr.entity.LeaveEncashment;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveYearEndRun;
import com.nforce.onehr.entity.LeaveYearEndRunLine;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveCarryForwardTransferRepository;
import com.nforce.onehr.repository.LeaveEncashmentRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.LeaveYearEndRunLineRepository;
import com.nforce.onehr.repository.LeaveYearEndRunRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.math.BigDecimal.ZERO;

/**
 * LV-05: leave year-end as a wizard, so a year is closed only when HR says so.
 * <ol>
 *   <li><b>Preview</b>: for every active employee (leavers are settled by their exit, LV-04) and every regular paid
 *       type, the closing unused days (as the V310 job computed them: days left + valid carried-forward − pending)
 *       are split per the plan on 31 December: <b>carry</b> up to its caps (days and/or percent) → <b>encash</b> up
 *       to its cap → the rest <b>lapses</b>. The encashment amount = days × monthly Basic (or Basic + DA) ÷ 26/30.</li>
 *   <li><b>Adjust</b>: HR may change carry and encash per line (carry + encash ≤ closing); each change is audited.</li>
 *   <li><b>Confirm</b>: writes the carry-forward transfers, creates next year's balance rows for every employee in the
 *       run (carrying something or not), queues the encashments for payroll ({@link LeaveEncashmentService}) and
 *       tells each employee their outcome (in-app and by email).</li>
 *   <li><b>Rollback</b> of the confirmed run, within {@code app.leave.year-end.rollback-days} (default 7), as long as
 *       no carried-forward day has been used or expired and no encashment has gone past a draft payroll run.</li>
 * </ol>
 * One PREVIEW (a new preview replaces it) and one CONFIRMED run per year. Balances closed before LV-05 by the nightly
 * job keep their transfers and are left out.
 */
@Slf4j
@Service
public class LeaveYearEndWizardService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final LeaveYearEndRunRepository runRepository;
    private final LeaveYearEndRunLineRepository lineRepository;
    private final LeaveCarryForwardTransferRepository transferRepository;
    private final LeaveEncashmentRepository encashmentRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveTypeRepository typeRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeaveYearEndService yearEndService;
    private final LeaveBalanceService balanceService;
    private final LeaveEncashmentService encashmentService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final int rollbackDays;
    private final boolean emailEnabled;

    public LeaveYearEndWizardService(LeaveYearEndRunRepository runRepository, LeaveYearEndRunLineRepository lineRepository,
                                     LeaveCarryForwardTransferRepository transferRepository, LeaveEncashmentRepository encashmentRepository,
                                     LeaveBalanceRepository balanceRepository, LeaveTypeRepository typeRepository,
                                     EmployeeRepository employeeRepository, UserRepository userRepository,
                                     LeaveYearEndService yearEndService, LeaveBalanceService balanceService,
                                     LeaveEncashmentService encashmentService, NotificationService notificationService,
                                     EmailService emailService, AuditService auditService, AuditSnapshotSerializer auditSnapshot,
                                     @Value("${app.leave.year-end.rollback-days:7}") int rollbackDays,
                                     @Value("${app.leave.notices.email:true}") boolean emailEnabled) {
        this.runRepository = runRepository;
        this.lineRepository = lineRepository;
        this.transferRepository = transferRepository;
        this.encashmentRepository = encashmentRepository;
        this.balanceRepository = balanceRepository;
        this.typeRepository = typeRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.yearEndService = yearEndService;
        this.balanceService = balanceService;
        this.encashmentService = encashmentService;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.auditService = auditService;
        this.auditSnapshot = auditSnapshot;
        this.rollbackDays = rollbackDays;
        this.emailEnabled = emailEnabled;
    }

    // ── The split (pure) ────────────────────────────────────────────────────────────────────────

    /** How a line's closing days are split: carry → encash → lapse. */
    public record Split(BigDecimal carry, BigDecimal encash, BigDecimal lapse) {}

    /** Carry up to the plan's caps, then encash up to its cap, the rest lapses. Nothing carried or encashed without a rule. */
    public static Split split(BigDecimal closing, Optional<LeavePlanCatalog.TypeRule> rule) {
        BigDecimal unused = closing.max(ZERO);
        BigDecimal carry = LeaveYearEndService.target(rule, unused);
        BigDecimal rest = unused.subtract(carry);
        BigDecimal encash = ZERO;
        LeavePlanCatalog.YearEnd y = rule.map(LeavePlanCatalog.TypeRule::yearEnd).orElse(null);
        if (y != null && y.encashEnabled() && rest.signum() > 0) {
            encash = y.encashMaxDays() == null ? rest : rest.min(y.encashMaxDays());
        }
        return new Split(carry, encash, rest.subtract(encash));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<YearEndRunSummary> runs(int fromYear) {
        return runRepository.findByFromYearOrderByCreatedAtDesc(fromYear).stream()
                .map(r -> summary(r, lineRepository.findByRunId(r.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public YearEndRunDetail detail(UUID runId) {
        LeaveYearEndRun run = requireRun(runId);
        int closedBefore = (int) transferRepository.findByFromYear(run.getFromYear()).stream().filter(t -> t.getRunId() == null).count();
        return detail(run, closedBefore, 0);
    }

    // ── Preview ─────────────────────────────────────────────────────────────────────────────────

    /** A fresh preview of {@code fromYear} (replacing the previous one). Refused once the year has a confirmed run. */
    @Transactional
    public YearEndRunDetail preview(int fromYear, String actorEmail) {
        User actor = requireActor(actorEmail);
        if (fromYear >= LeaveBalanceService.leaveYearOf(balanceService.today())) {
            throw new IllegalArgumentException("Leave year " + fromYear + " hasn't ended yet.");
        }
        if (runRepository.findByFromYearAndStatus(fromYear, LeaveYearEndRun.CONFIRMED).isPresent()) {
            throw new IllegalStateException("Leave year " + fromYear + " is already closed. Roll that run back to preview it again.");
        }
        runRepository.findByFromYearAndStatus(fromYear, LeaveYearEndRun.PREVIEW).ifPresent(old -> {
            lineRepository.deleteAll(lineRepository.findByRunId(old.getId()));
            runRepository.delete(old);
            runRepository.flush();
        });
        LeaveYearEndRun run = runRepository.save(LeaveYearEndRun.builder().fromYear(fromYear).status(LeaveYearEndRun.PREVIEW)
                .createdBy(actor.getId()).build());

        LocalDate yearEnd = LeaveBalanceService.yearEnd(fromYear);
        Map<UUID, LeaveEmployeeProfile> profiles = new HashMap<>();
        employeeRepository.findLeaveProfiles().forEach(p -> profiles.put(p.userId(), p));
        Set<String> closed = new HashSet<>();
        transferRepository.findByFromYear(fromYear).forEach(t -> closed.add(t.getEmployeeUserId() + ":" + t.getLeaveType().getId()));
        List<LeaveType> types = typeRepository.findAll().stream().filter(LeaveType::isAnnualBalance).toList();
        int alreadyClosed = 0, leavers = 0;
        List<LeaveYearEndRunLine> lines = new ArrayList<>();
        for (UUID employeeId : employeeRepository.findActiveEmployeeUserIds()) {
            LeaveEmployeeProfile p = profiles.get(employeeId);
            if (p != null && p.lastWorkingDay() != null && !p.lastWorkingDay().isAfter(yearEnd)) {
                leavers++; // settled by their exit (LV-04), never encashed twice
                continue;
            }
            for (LeaveType type : types) {
                if (closed.contains(employeeId + ":" + type.getId())) {
                    alreadyClosed++;
                    continue;
                }
                Optional<LeavePlanCatalog.TypeRule> rule = yearEndService.closingRule(employeeId, type, fromYear);
                LeaveBalance balance = balanceService.ensureBalance(employeeId, type, fromYear);
                BigDecimal closing = yearEndService.unusedAtYearEnd(balance);
                if (rule.isEmpty() && closing.signum() == 0) continue;
                lines.add(line(run.getId(), employeeId, type, fromYear, closing, rule));
            }
        }
        lineRepository.saveAll(lines);
        audit(actor.getId(), "LEAVE_YEAR_END_RUN_PREVIEWED", run, null, Map.of("fromYear", fromYear, "lines", lines.size()));
        return detail(run, alreadyClosed, leavers);
    }

    private LeaveYearEndRunLine line(UUID runId, UUID employeeId, LeaveType type, int fromYear, BigDecimal closing,
                                     Optional<LeavePlanCatalog.TypeRule> rule) {
        Split s = split(closing, rule);
        LeavePlanCatalog.YearEnd y = rule.map(LeavePlanCatalog.TypeRule::yearEnd).orElse(LeavePlanCatalog.YearEnd.NONE);
        LeaveYearEndRunLine line = LeaveYearEndRunLine.builder().runId(runId).employeeUserId(employeeId).leaveTypeId(type.getId())
                .closingDays(closing.max(ZERO)).suggestedCarry(s.carry()).suggestedEncash(s.encash())
                .carryDays(s.carry()).encashDays(s.encash()).lapseDays(s.lapse())
                .expiresOn(LeaveYearEndService.expiry(rule, fromYear))
                .encashBasis(y.encashBasis()).encashDivisor(y.encashDivisor()).build();
        if (y.encashEnabled()) {
            line.setMonthlyBase(encashmentService.monthlyBase(employeeId, LeaveBalanceService.yearEnd(fromYear), y.encashBasis()));
        }
        price(line);
        return line;
    }

    /** The encashment amount from the line's days and base; a note when it can't be paid. */
    private static void price(LeaveYearEndRunLine line) {
        line.setEncashAmount(null);
        line.setNote(null);
        if (line.getEncashDays().signum() <= 0) return;
        if (line.getMonthlyBase() == null) {
            line.setNote("No salary structure with a Basic component on 31 Dec: the days are recorded as encashed but nothing is paid.");
            return;
        }
        int divisor = line.getEncashDivisor() == null ? 30 : line.getEncashDivisor();
        line.setEncashAmount(LeaveEncashmentService.amount(line.getEncashDays(), line.getMonthlyBase(), divisor));
    }

    // ── Adjust ──────────────────────────────────────────────────────────────────────────────────

    /** HR's split for one line of a preview: carry + encash ≤ closing; lapse is the rest. */
    @Transactional
    public YearEndRunLineDto adjust(UUID runId, UUID lineId, AdjustYearEndLineRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveYearEndRun run = requireRun(runId);
        if (!LeaveYearEndRun.PREVIEW.equals(run.getStatus())) {
            throw new IllegalStateException("Only a preview can be adjusted.");
        }
        LeaveYearEndRunLine line = lineRepository.findById(lineId).filter(l -> l.getRunId().equals(runId))
                .orElseThrow(() -> new NoSuchElementException("Year-end line not found: " + lineId));
        BigDecimal carry = req.carryDays(), encash = req.encashDays();
        if (carry.add(encash).compareTo(line.getClosingDays()) > 0) {
            throw new IllegalArgumentException("Carry and encash together can't exceed the " + plain(line.getClosingDays())
                    + " unused day(s).");
        }
        if (encash.signum() > 0 && line.getMonthlyBase() == null && line.getEncashBasis() != null) {
            // still allowed (days recorded), but a base may now be needed for a type whose plan didn't encash
            line.setMonthlyBase(encashmentService.monthlyBase(line.getEmployeeUserId(),
                    LeaveBalanceService.yearEnd(run.getFromYear()), line.getEncashBasis()));
        }
        Map<String, Object> before = Map.of("carryDays", line.getCarryDays(), "encashDays", line.getEncashDays(),
                "lapseDays", line.getLapseDays());
        line.setCarryDays(carry);
        line.setEncashDays(encash);
        line.setLapseDays(line.getClosingDays().subtract(carry).subtract(encash));
        line.setAdjusted(carry.compareTo(line.getSuggestedCarry()) != 0 || encash.compareTo(line.getSuggestedEncash()) != 0);
        line.setAdjustedBy(actor.getId());
        line.setAdjustedAt(LocalDateTime.now());
        price(line);
        lineRepository.save(line);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("employeeUserId", line.getEmployeeUserId().toString());
        after.put("leaveTypeId", line.getLeaveTypeId().toString());
        after.put("fromYear", run.getFromYear());
        after.put("carryDays", carry);
        after.put("encashDays", encash);
        after.put("lapseDays", line.getLapseDays());
        AuditService.afterCommit(() -> auditService.log(actor.getId(), "LEAVE_YEAR_END_LINE_ADJUSTED", line.getId(),
                auditSnapshot.toJson(before), auditSnapshot.toJson(after)));
        return lineDtos(List.of(line), Map.of()).get(0);
    }

    // ── Confirm ─────────────────────────────────────────────────────────────────────────────────

    /** Closes the year as previewed (and adjusted): transfers, next year's balances, encashments, notices. */
    @Transactional
    public YearEndRunDetail confirm(UUID runId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveYearEndRun run = requireRun(runId);
        if (!LeaveYearEndRun.PREVIEW.equals(run.getStatus())) {
            throw new IllegalStateException("This run is " + run.getStatus().toLowerCase().replace('_', ' ') + ", not a preview.");
        }
        int fromYear = run.getFromYear();
        Map<UUID, LeaveType> types = typesById();
        List<LeaveYearEndRunLine> lines = lineRepository.findByRunId(runId);
        List<LeaveEncashment> encashments = new ArrayList<>();
        int skipped = 0;
        for (LeaveYearEndRunLine line : lines) {
            LeaveType type = types.get(line.getLeaveTypeId());
            if (type == null || transferRepository.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(
                    line.getEmployeeUserId(), line.getLeaveTypeId(), fromYear).isPresent()) {
                skipped++;
                continue;
            }
            transferRepository.save(LeaveCarryForwardTransfer.builder().employeeUserId(line.getEmployeeUserId()).leaveType(type)
                    .fromYear(fromYear).unusedDays(line.getClosingDays()).carriedDays(line.getCarryDays())
                    .encashedDays(line.getEncashDays()).lapsedDays(line.getLapseDays())
                    .expiresOn(line.getCarryDays().signum() > 0 ? line.getExpiresOn() : null)
                    .runId(runId).adjusted(line.isAdjusted()).build());
            // next year's row for everyone in the run, carrying something or not
            LeaveBalance next = balanceService.ensureBalance(line.getEmployeeUserId(), type, fromYear + 1);
            if (line.getCarryDays().signum() > 0) {
                next.setCarriedForwardDays(next.getCarriedForwardDays().add(line.getCarryDays()));
                next.setCarriedForwardExpiresOn(line.getExpiresOn());
                balanceRepository.save(next);
            }
            if (line.getEncashAmount() != null && line.getEncashAmount().signum() > 0) {
                encashments.add(LeaveEncashment.builder().runId(runId).employeeUserId(line.getEmployeeUserId())
                        .leaveTypeId(line.getLeaveTypeId()).fromYear(fromYear).days(line.getEncashDays())
                        .amount(line.getEncashAmount()).status(LeaveEncashment.PENDING).createdBy(actor.getId()).build());
            }
        }
        encashmentRepository.saveAll(encashments);
        run.setStatus(LeaveYearEndRun.CONFIRMED);
        run.setConfirmedBy(actor.getId());
        run.setConfirmedAt(LocalDateTime.now());
        runRepository.save(run);
        audit(actor.getId(), "LEAVE_YEAR_END_RUN_CONFIRMED", run, Map.of("status", LeaveYearEndRun.PREVIEW),
                Map.of("fromYear", fromYear, "lines", lines.size(), "skipped", skipped, "encashments", encashments.size()));
        Map<UUID, List<String>> notices = notices(lines, types, fromYear);
        // In-app notices commit with the year-end itself; emails go out only once it has committed.
        String title = "Your " + fromYear + " leave year-end";
        notices.forEach((userId, text) -> notificationService.send(userId, "LEAVE_YEAR_END_OUTCOME", title, String.join(" ", text), "/leave"));
        AuditService.afterCommit(() -> email(notices, fromYear));
        return detail(run, 0, 0);
    }

    /** Each employee's outcome, one sentence per leave type. */
    static Map<UUID, List<String>> notices(List<LeaveYearEndRunLine> lines, Map<UUID, LeaveType> types, int fromYear) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        for (LeaveYearEndRunLine l : lines) {
            LeaveType t = types.get(l.getLeaveTypeId());
            String name = t == null ? "Leave" : t.getName();
            List<String> parts = new ArrayList<>();
            if (l.getCarryDays().signum() > 0) {
                parts.add(plain(l.getCarryDays()) + " day(s) carried forward to " + (fromYear + 1)
                        + (l.getExpiresOn() != null ? " (use by " + DAY.format(l.getExpiresOn()) + ")" : ""));
            }
            if (l.getEncashDays().signum() > 0) {
                parts.add(plain(l.getEncashDays()) + " day(s) encashed"
                        + (l.getEncashAmount() != null ? " (" + rupees(l.getEncashAmount()) + ", paid with your next salary)" : ""));
            }
            if (l.getLapseDays().signum() > 0) parts.add(plain(l.getLapseDays()) + " day(s) lapsed");
            String sentence = name + " " + fromYear + ": " + (parts.isEmpty() ? "no unused days to carry forward." : String.join("; ", parts) + ".");
            out.computeIfAbsent(l.getEmployeeUserId(), k -> new ArrayList<>()).add(sentence);
        }
        return out;
    }

    private void email(Map<UUID, List<String>> notices, int fromYear) {
        if (!emailEnabled || notices.isEmpty()) return;
        Map<UUID, String> names = new HashMap<>();
        if (!notices.isEmpty()) employeeRepository.findNamesByUserIds(notices.keySet()).forEach(r -> names.put((UUID) r[0], (String) r[1]));
        Map<UUID, User> users = new HashMap<>();
        userRepository.findAllById(notices.keySet()).forEach(u -> users.put(u.getId(), u));
        String title = "Your " + fromYear + " leave year-end";
        for (Map.Entry<UUID, List<String>> e : notices.entrySet()) {
            try {
                User u = users.get(e.getKey());
                if (u != null && u.getEmail() != null) {
                    emailService.sendLeaveNoticeEmail(u.getEmail(), names.getOrDefault(e.getKey(), ""), title, title, e.getValue(), "/leave");
                }
            } catch (Exception ex) {
                log.warn("Could not email the {} year-end outcome to {}: {}", fromYear, e.getKey(), ex.getMessage());
            }
        }
    }

    // ── Rollback ────────────────────────────────────────────────────────────────────────────────

    /**
     * Reverses a confirmed run within the rollback window: removes its transfers and the carried days from next year's
     * balances, and cancels its encashments (removing payments from draft payroll runs). Refused when a carried day was
     * used or expired, or an encashment is in a payroll run past draft.
     */
    @Transactional
    public YearEndRunDetail rollback(UUID runId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeaveYearEndRun run = requireRun(runId);
        if (!LeaveYearEndRun.CONFIRMED.equals(run.getStatus())) {
            throw new IllegalStateException("Only a confirmed run can be rolled back.");
        }
        if (LocalDateTime.now().isAfter(rollbackUntil(run))) {
            throw new IllegalStateException("The rollback window (" + rollbackDays + " days after confirming) has passed.");
        }
        List<LeaveCarryForwardTransfer> transfers = transferRepository.findByRunId(runId);
        List<String> problems = new ArrayList<>();
        Map<UUID, LeaveBalance> nextBalances = new HashMap<>();
        for (LeaveCarryForwardTransfer t : transfers) {
            if (t.getCarriedDays().signum() <= 0) continue;
            balanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(t.getEmployeeUserId(), t.getLeaveType().getId(), run.getFromYear() + 1)
                    .ifPresent(b -> {
                        nextBalances.put(t.getId(), b);
                        if (b.getCarriedForwardUsedDays().signum() > 0 || b.getCarriedForwardExpiredDays().signum() > 0) {
                            problems.add(employeeName(t.getEmployeeUserId()) + " has already used or lost carried-forward "
                                    + t.getLeaveType().getName());
                        }
                    });
        }
        List<LeaveEncashment> encashments = encashmentRepository.findByRunId(runId);
        for (LeaveEncashment e : encashments) {
            String reason = encashmentService.blockingReason(e);
            if (reason != null) problems.add(employeeName(e.getEmployeeUserId()) + ": " + reason);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("This year-end can't be rolled back: " + String.join("; ",
                    problems.subList(0, Math.min(3, problems.size()))) + (problems.size() > 3 ? " (and " + (problems.size() - 3) + " more)" : "") + ".");
        }
        for (LeaveCarryForwardTransfer t : transfers) {
            LeaveBalance next = nextBalances.get(t.getId());
            if (next != null) {
                BigDecimal left = next.getCarriedForwardDays().subtract(t.getCarriedDays()).max(ZERO);
                next.setCarriedForwardDays(left);
                if (left.signum() == 0) next.setCarriedForwardExpiresOn(null);
                balanceRepository.save(next);
            }
        }
        transferRepository.deleteAll(transfers);
        for (LeaveEncashment e : encashments) {
            if (!LeaveEncashment.CANCELLED.equals(e.getStatus())) encashmentService.cancel(e);
        }
        run.setStatus(LeaveYearEndRun.ROLLED_BACK);
        run.setRolledBackBy(actor.getId());
        run.setRolledBackAt(LocalDateTime.now());
        runRepository.save(run);
        audit(actor.getId(), "LEAVE_YEAR_END_RUN_ROLLED_BACK", run, Map.of("status", LeaveYearEndRun.CONFIRMED),
                Map.of("fromYear", run.getFromYear(), "transfers", transfers.size(), "encashments", encashments.size()));
        return detail(run, 0, 0);
    }

    LocalDateTime rollbackUntil(LeaveYearEndRun run) {
        return run.getConfirmedAt() == null ? null : run.getConfirmedAt().plusDays(rollbackDays);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private YearEndRunDetail detail(LeaveYearEndRun run, int alreadyClosed, int leavers) {
        List<LeaveYearEndRunLine> lines = lineRepository.findByRunId(run.getId());
        Map<String, String> encashmentStatus = new HashMap<>();
        if (!LeaveYearEndRun.PREVIEW.equals(run.getStatus())) {
            encashmentRepository.findByRunId(run.getId()).forEach(e ->
                    encashmentStatus.put(e.getEmployeeUserId() + ":" + e.getLeaveTypeId(), e.getStatus()));
        }
        return new YearEndRunDetail(summary(run, lines), lineDtos(lines, encashmentStatus), alreadyClosed, leavers);
    }

    private YearEndRunSummary summary(LeaveYearEndRun run, List<LeaveYearEndRunLine> lines) {
        BigDecimal closing = ZERO, carry = ZERO, encash = ZERO, lapse = ZERO, amount = ZERO;
        int adjusted = 0;
        for (LeaveYearEndRunLine l : lines) {
            closing = closing.add(l.getClosingDays());
            carry = carry.add(l.getCarryDays());
            encash = encash.add(l.getEncashDays());
            lapse = lapse.add(l.getLapseDays());
            if (l.getEncashAmount() != null) amount = amount.add(l.getEncashAmount());
            if (l.isAdjusted()) adjusted++;
        }
        return new YearEndRunSummary(run.getId(), run.getFromYear(), run.getStatus(), userName(run.getCreatedBy()), run.getCreatedAt(),
                userName(run.getConfirmedBy()), run.getConfirmedAt(),
                LeaveYearEndRun.CONFIRMED.equals(run.getStatus()) ? rollbackUntil(run) : null,
                userName(run.getRolledBackBy()), run.getRolledBackAt(), lines.size(), adjusted, closing, carry, encash, lapse, amount);
    }

    private List<YearEndRunLineDto> lineDtos(List<LeaveYearEndRunLine> lines, Map<String, String> encashmentStatus) {
        Set<UUID> ids = new HashSet<>();
        lines.forEach(l -> ids.add(l.getEmployeeUserId()));
        Map<UUID, String> names = new HashMap<>(), codes = new HashMap<>();
        if (!ids.isEmpty()) {
            employeeRepository.findNamesByUserIds(ids).forEach(r -> names.put((UUID) r[0], (String) r[1]));
            employeeRepository.findCodesByUserIds(ids).forEach(r -> codes.put((UUID) r[0], (String) r[1]));
        }
        Map<UUID, LeaveType> types = typesById();
        return lines.stream()
                .map(l -> new YearEndRunLineDto(l.getId(), l.getEmployeeUserId(), names.getOrDefault(l.getEmployeeUserId(), "Unknown"),
                        codes.get(l.getEmployeeUserId()), l.getLeaveTypeId(),
                        types.containsKey(l.getLeaveTypeId()) ? types.get(l.getLeaveTypeId()).getName() : "Leave",
                        l.getClosingDays(), l.getSuggestedCarry(), l.getSuggestedEncash(), l.getCarryDays(), l.getEncashDays(),
                        l.getLapseDays(), l.getExpiresOn(), l.getEncashBasis(), l.getEncashDivisor(), l.getMonthlyBase(),
                        l.getEncashAmount(), l.getNote(), l.isAdjusted(),
                        encashmentStatus.get(l.getEmployeeUserId() + ":" + l.getLeaveTypeId())))
                .sorted(Comparator.comparing(YearEndRunLineDto::employeeName).thenComparing(YearEndRunLineDto::leaveTypeName))
                .toList();
    }

    private Map<UUID, LeaveType> typesById() {
        Map<UUID, LeaveType> out = new HashMap<>();
        typeRepository.findAll().forEach(t -> out.put(t.getId(), t));
        return out;
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findNamesByUserIds(Set.of(userId)).stream().findFirst().map(r -> (String) r[1]).orElse("An employee");
    }

    private String userName(UUID userId) {
        if (userId == null) return null;
        return employeeRepository.findNamesByUserIds(Set.of(userId)).stream().findFirst().map(r -> (String) r[1])
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse(null));
    }

    private LeaveYearEndRun requireRun(UUID runId) {
        return runRepository.findById(runId).orElseThrow(() -> new NoSuchElementException("Year-end run not found: " + runId));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private void audit(UUID actorId, String action, LeaveYearEndRun run, Map<String, Object> before, Map<String, Object> after) {
        AuditService.afterCommit(() -> auditService.log(actorId, action, run.getId(),
                before == null ? null : auditSnapshot.toJson(before), auditSnapshot.toJson(after)));
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private static String rupees(BigDecimal amount) {
        return "₹" + String.format(Locale.ENGLISH, "%,.2f", amount);
    }
}
