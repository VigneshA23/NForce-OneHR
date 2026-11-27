package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * YE-05: year-end leave notices, in-app and by email.
 * <ul>
 *   <li><b>Reminders</b> (default 1 and 15 December): every employee with unused paid leave gets
 *       "You have N days of Paid Leave left in 2026" and what the current carry-forward setting does
 *       with them after 31 December (lapse, or carry up to X days). The wording follows each leave
 *       type's configured carry-forward (the YE-01 decision, set in Organization Masters → Leave).</li>
 *   <li><b>Manager digest</b> (default 1 December): one message per manager listing direct reports
 *       with more than N unused days (default 5), so leave is planned rather than rushed.</li>
 *   <li><b>New year</b> (default 1 January, after the nightly crediting and carry-forward jobs): each
 *       employee's new year's quota, what was credited today, and any days carried forward.</li>
 * </ul>
 * Runs once a day ({@code app.leave.notices.cron}) and sends only on the configured dates. Each kind
 * is sent at most once per date: the send is recorded in the audit log and a later run that day
 * (a restart, a second instance) skips it. There are no per-user notification preferences in the
 * app yet; switch the whole feature off with {@code app.leave.notices.enabled=false}, or email only
 * with {@code app.leave.notices.email=false}.
 */
@Slf4j
@Service
public class LeaveYearEndNoticeService {

    static final String SENT_ACTION = "LEAVE_YEAR_END_NOTICES_SENT";
    static final String REMINDER = "REMINDER";
    static final String MANAGER_DIGEST = "MANAGER_DIGEST";
    static final String NEW_YEAR = "NEW_YEAR";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NAMES_IN_DIGEST = 25;

    private final LeaveBalanceService leaveBalanceService;
    private final LeavePlanRuleService planRuleService;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AuditLogRepository auditLogRepository;

    private final boolean enabled;
    private final boolean emailEnabled;
    private final Set<MonthDay> reminderDates;
    private final Set<MonthDay> managerDigestDates;
    private final Set<MonthDay> newYearDates;
    private final BigDecimal managerThresholdDays;

    public LeaveYearEndNoticeService(LeaveBalanceService leaveBalanceService, LeavePlanRuleService planRuleService,
                                     EmployeeRepository employeeRepository, EmployeeManagerHistoryRepository historyRepository,
                                     NotificationService notificationService, EmailService emailService,
                                     AuditService auditService, AuditSnapshotSerializer auditSnapshot,
                                     AuditLogRepository auditLogRepository,
                                     @Value("${app.leave.notices.enabled:true}") boolean enabled,
                                     @Value("${app.leave.notices.email:true}") boolean emailEnabled,
                                     @Value("${app.leave.notices.reminder-dates:12-01,12-15}") String reminderDates,
                                     @Value("${app.leave.notices.manager-digest-dates:12-01}") String managerDigestDates,
                                     @Value("${app.leave.notices.new-year-dates:01-01}") String newYearDates,
                                     @Value("${app.leave.notices.manager-threshold-days:5}") BigDecimal managerThresholdDays) {
        this.leaveBalanceService = leaveBalanceService;
        this.planRuleService = planRuleService;
        this.employeeRepository = employeeRepository;
        this.historyRepository = historyRepository;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.auditService = auditService;
        this.auditSnapshot = auditSnapshot;
        this.auditLogRepository = auditLogRepository;
        this.enabled = enabled;
        this.emailEnabled = emailEnabled;
        this.reminderDates = parseDates(reminderDates);
        this.managerDigestDates = parseDates(managerDigestDates);
        this.newYearDates = parseDates(newYearDates);
        this.managerThresholdDays = managerThresholdDays;
    }

    /** "12-01,12-15" -> {Dec 1, Dec 15}; blank = never. */
    static Set<MonthDay> parseDates(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> MonthDay.parse("--" + s)).collect(Collectors.toUnmodifiableSet());
    }

    /** Daily; after the 00:15 crediting and 00:30 year-end jobs, so 1 January sees the new balances. */
    @Scheduled(cron = "${app.leave.notices.cron:0 0 9 * * *}", zone = "${app.attendance.zone:Asia/Kolkata}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            runFor(leaveBalanceService.today());
        } catch (Exception e) {
            log.error("Leave year-end notices failed", e);
        }
    }

    /** Sends whatever is due on {@code date}; returns kind -> recipients for the kinds sent now. */
    public Map<String, Integer> runFor(LocalDate date) {
        MonthDay day = MonthDay.from(date);
        Map<String, Integer> sent = new LinkedHashMap<>();
        boolean reminder = reminderDates.contains(day) && !alreadySent(REMINDER, date);
        boolean digest = managerDigestDates.contains(day) && !alreadySent(MANAGER_DIGEST, date);
        if (reminder || digest) {
            int year = LeaveBalanceService.leaveYearOf(date);
            Map<UUID, Recipient> people = recipients();
            Map<UUID, List<Unused>> unused = unusedLeave(people.keySet(), year);
            if (reminder) sent.put(REMINDER, record(REMINDER, date, sendReminders(people, unused, year)));
            if (digest) sent.put(MANAGER_DIGEST, record(MANAGER_DIGEST, date, sendManagerDigests(people, unused, year)));
        }
        if (newYearDates.contains(day) && !alreadySent(NEW_YEAR, date)) {
            sent.put(NEW_YEAR, record(NEW_YEAR, date, sendNewYearBalances(recipients(), LeaveBalanceService.leaveYearOf(date))));
        }
        return sent;
    }

    // ── Data ────────────────────────────────────────────────────────────────────────────────────

    record Recipient(UUID userId, String name, String email) {}

    /** Unused credited days of one regular paid type at the time of the reminder. */
    record Unused(String typeName, UUID typeId, BigDecimal days) {}

    private Map<UUID, Recipient> recipients() {
        Map<UUID, Recipient> out = new LinkedHashMap<>();
        for (Object[] row : employeeRepository.findActiveNoticeRecipients()) {
            out.put((UUID) row[0], new Recipient((UUID) row[0], (String) row[1], (String) row[2]));
        }
        return out;
    }

    private Map<UUID, List<Unused>> unusedLeave(Set<UUID> employeeIds, int year) {
        Map<UUID, List<Unused>> out = new LinkedHashMap<>();
        for (UUID id : employeeIds) {
            try {
                List<Unused> rows = new ArrayList<>();
                for (LeaveBalanceResponse b : leaveBalanceService.balancesFor(id, year)) {
                    if (!LeaveType.BASIS_ANNUAL.equals(b.getEntitlementBasis()) || b.isUnlimited() || b.getRemainingDays() == null) continue;
                    if (b.getRemainingDays().signum() > 0) rows.add(new Unused(b.getLeaveTypeName(), b.getLeaveTypeId(), b.getRemainingDays()));
                }
                if (!rows.isEmpty()) out.put(id, rows);
            } catch (Exception e) {
                log.warn("Could not read leave balances of {} for year-end notices: {}", id, e.getMessage());
            }
        }
        return out;
    }

    // ── Reminders ───────────────────────────────────────────────────────────────────────────────

    private int sendReminders(Map<UUID, Recipient> people, Map<UUID, List<Unused>> unused, int year) {
        LeavePlanCatalog catalog = planRuleService.catalog(unused.keySet());
        int count = 0;
        for (Map.Entry<UUID, List<Unused>> e : unused.entrySet()) {
            Recipient r = people.get(e.getKey());
            if (r == null) continue;
            List<String> lines = new ArrayList<>();
            for (Unused u : e.getValue()) {
                // LV-01: the carry-forward rules of this employee's plan on the last day of the year.
                lines.add(reminderLine(u, catalog.ruleFor(e.getKey(), u.typeId(), LeaveBalanceService.yearEnd(year)).orElse(null), year));
            }
            String message = String.join(" ", lines);
            notificationService.send(r.userId(), "LEAVE_YEAR_END_REMINDER", "Plan your remaining " + year + " leave", message, "/leave");
            if (emailEnabled && r.email() != null) {
                List<String> body = new ArrayList<>(lines);
                body.add("Apply from the Leave page. Leave over New Year is charged to each year's own balance.");
                emailService.sendLeaveNoticeEmail(r.email(), r.name(), "You have unused leave in " + year,
                        "Plan your remaining " + year + " leave", body, "/leave");
            }
            count++;
        }
        return count;
    }

    /** "You have 6 days of Paid Leave left in 2026. Under the current policy, ... applies after 31 Dec 2026." */
    static String reminderLine(Unused u, LeavePlanCatalog.TypeRule rule, int year) {
        return "You have " + days(u.days()) + " of " + u.typeName() + " left in " + year + ". After 31 Dec " + year
                + ", under the current policy, " + policyText(rule, year) + ".";
    }

    /** What the plan's carry-forward does with unused days of the type at the end of {@code year} (null = not in the plan). */
    static String policyText(LeavePlanCatalog.TypeRule rule, int year) {
        if (rule == null || !rule.carryForwardEnabled()) return "unused days lapse";
        BigDecimal max = rule.carryForwardMaxDays();
        String expiry = rule.carryForwardExpiryMonths() != null
                ? " (to be used by " + DAY.format(LeaveBalanceService.yearStart(year + 1)
                        .plusMonths(rule.carryForwardExpiryMonths()).minusDays(1)) + ")"
                : "";
        return max == null
                ? "all unused days carry forward to " + (year + 1) + expiry
                : "up to " + days(max) + " carry forward to " + (year + 1) + expiry + " and the rest lapse";
    }

    // ── Manager digest ──────────────────────────────────────────────────────────────────────────

    private int sendManagerDigests(Map<UUID, Recipient> people, Map<UUID, List<Unused>> unused, int year) {
        Map<UUID, List<String>> byManager = new LinkedHashMap<>();
        List<Map.Entry<UUID, BigDecimal>> totals = unused.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().stream().map(Unused::days).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .filter(e -> e.getValue().compareTo(managerThresholdDays) > 0)
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                .toList();
        for (Map.Entry<UUID, BigDecimal> e : totals) {
            Optional<UUID> manager = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(e.getKey())
                    .map(EmployeeManagerHistory::getManagerUserId);
            Recipient report = people.get(e.getKey());
            if (manager.isEmpty() || report == null) continue;
            byManager.computeIfAbsent(manager.get(), k -> new ArrayList<>()).add(report.name() + " (" + days(e.getValue()) + ")");
        }
        int count = 0;
        for (Map.Entry<UUID, List<String>> e : byManager.entrySet()) {
            Recipient manager = people.get(e.getKey());
            if (manager == null) continue; // an inactive manager gets nothing
            List<String> names = e.getValue();
            String shown = String.join(", ", names.subList(0, Math.min(names.size(), MAX_NAMES_IN_DIGEST)))
                    + (names.size() > MAX_NAMES_IN_DIGEST ? " and " + (names.size() - MAX_NAMES_IN_DIGEST) + " more" : "");
            String message = names.size() + " of your team " + (names.size() == 1 ? "has" : "have") + " more than "
                    + days(managerThresholdDays) + " of leave left in " + year + ": " + shown
                    + ". Encourage them to plan it now rather than in the last weeks of December.";
            notificationService.send(manager.userId(), "LEAVE_TEAM_UNUSED_DIGEST", "Team leave to plan before 31 Dec", message, "/my-team");
            if (emailEnabled && manager.email() != null) {
                emailService.sendLeaveNoticeEmail(manager.email(), manager.name(), "Your team's unused leave in " + year,
                        "Team leave to plan before 31 Dec", List.of(message), "/my-team");
            }
            count++;
        }
        return count;
    }

    // ── New year ────────────────────────────────────────────────────────────────────────────────

    private int sendNewYearBalances(Map<UUID, Recipient> people, int year) {
        int count = 0;
        for (Recipient r : people.values()) {
            try {
                List<String> lines = new ArrayList<>();
                for (LeaveBalanceResponse b : leaveBalanceService.balancesFor(r.userId(), year)) {
                    String line = newYearLine(b, year);
                    if (line != null) lines.add(line);
                }
                if (lines.isEmpty()) continue;
                notificationService.send(r.userId(), "LEAVE_NEW_YEAR_BALANCE", "Your " + year + " leave balance", String.join(" ", lines), "/leave");
                if (emailEnabled && r.email() != null) {
                    emailService.sendLeaveNoticeEmail(r.email(), r.name(), "Your " + year + " leave balance",
                            "Your " + year + " leave balance", lines, "/leave");
                }
                count++;
            } catch (Exception e) {
                log.warn("Could not send the {} leave balance notice to {}: {}", year, r.userId(), e.getMessage());
            }
        }
        return count;
    }

    /** One regular paid type's opening position, or null when there is nothing to tell. */
    static String newYearLine(LeaveBalanceResponse b, int year) {
        if (!LeaveType.BASIS_ANNUAL.equals(b.getEntitlementBasis()) || b.isUnlimited()) return null;
        BigDecimal quota = b.getAnnualQuotaDays() != null ? b.getAnnualQuotaDays() : b.getTotalDays();
        BigDecimal carried = b.getCarriedForwardDays() != null ? b.getCarriedForwardDays() : BigDecimal.ZERO;
        if ((quota == null || quota.signum() == 0) && carried.signum() == 0) return null;
        StringBuilder s = new StringBuilder(b.getLeaveTypeName()).append(": ").append(days(quota == null ? BigDecimal.ZERO : quota))
                .append(" for ").append(year);
        if ("MONTHLY".equals(b.getCreditFrequency())) {
            s.append(", credited monthly (").append(days(b.getTotalDays() == null ? BigDecimal.ZERO : b.getTotalDays()))
                    .append(" credited so far)");
        }
        if (carried.signum() > 0) {
            s.append(", plus ").append(days(carried)).append(" carried forward from ").append(year - 1);
            if (b.getCarriedForwardExpiresOn() != null) s.append(" to use by ").append(DAY.format(b.getCarriedForwardExpiresOn()));
        }
        return s.append(".").toString();
    }

    // ── Once per kind and date ──────────────────────────────────────────────────────────────────

    private boolean alreadySent(String kind, LocalDate date) {
        for (AuditLog a : auditLogRepository.findByActionInOrderByOccurredAtDesc(List.of(SENT_ACTION), PageRequest.of(0, 20))) {
            try {
                Map<?, ?> d = JSON.readValue(a.getAfterState(), Map.class);
                if (kind.equals(d.get("kind")) && date.toString().equals(d.get("date"))) return true;
            } catch (Exception ignored) {
                // an unreadable entry never blocks a send
            }
        }
        return false;
    }

    private int record(String kind, LocalDate date, int recipients) {
        auditService.log(null, SENT_ACTION, null, null, auditSnapshot.toJson(Map.of(
                "kind", kind, "date", date.toString(), "recipients", recipients)));
        log.info("Leave year-end notices: {} sent to {} recipient(s) for {}", kind, recipients, date);
        return recipients;
    }

    static String days(BigDecimal d) {
        String n = LeaveBalanceService.formatDays(d);
        return n + (d.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
    }
}
