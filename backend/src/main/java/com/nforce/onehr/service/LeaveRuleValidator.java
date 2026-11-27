package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * LV-02 (ONEHR-887): enforces a leave type's rules from the employee's leave plan on submit, edit and
 * approval. The first rule a request breaks is reported as a 400 with a message naming the type and the
 * limit. Approval re-checks everything except notice (notice is about when the request was made).
 */
@Service
@RequiredArgsConstructor
public class LeaveRuleValidator {

    static final Set<String> ACTIVE = Set.of("PENDING", "APPROVED");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final LeaveRequestRepository requestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeavePlanRuleService planRuleService;
    private final LeaveCalendar calendar;

    /** The request being checked. {@code hours} only for hourly leave; {@code excludeRequestId} = itself when editing/approving. */
    public record Candidate(UUID employeeUserId, LeaveType type, LocalDate start, LocalDate end, BigDecimal totalDays,
                            boolean halfDay, BigDecimal hours, boolean hasAttachment, UUID excludeRequestId) {}

    /** Throws IllegalArgumentException with the first broken rule; nothing when the type has no rule (not in the plan). */
    @Transactional(readOnly = true)
    public void validate(Candidate c, LocalDate today, boolean atApproval) {
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(c.employeeUserId()));
        Optional<LeavePlanCatalog.TypeRule> rule = catalog.ruleFor(c.employeeUserId(), c.type().getId(), c.start());
        if (rule.isEmpty()) return;
        // Requests near this one: counts need the whole month/year; adjacency looks a few weeks either side.
        LocalDate from = min(LocalDate.of(c.start().getYear(), 1, 1), c.start().minusDays(62));
        LocalDate to = max(LocalDate.of(c.start().getYear(), 12, 31), c.end().plusDays(62));
        List<LeaveRequest> others = requestRepository.findActiveInRange(c.employeeUserId(), ACTIVE, from, to).stream()
                .filter(r -> !r.getId().equals(c.excludeRequestId())).toList();
        LeaveCalendar.Days days = calendar.forEmployee(c.employeeUserId());
        check(c, rule.get(), today, atApproval, others, days,
                (typeId, date) -> catalog.ruleFor(c.employeeUserId(), typeId, date),
                typeId -> leaveTypeRepository.findById(typeId).map(LeaveType::getName).orElse("another leave type"))
                .ifPresent(message -> { throw new IllegalArgumentException(message); });
    }

    /** The first rule {@code c} breaks, as the message shown to the employee. */
    static Optional<String> check(Candidate c, LeavePlanCatalog.TypeRule rule, LocalDate today, boolean atApproval,
                                  List<LeaveRequest> others, LeaveCalendar.Days days,
                                  BiFunction<UUID, LocalDate, Optional<LeavePlanCatalog.TypeRule>> ruleOfType,
                                  Function<UUID, String> typeName) {
        LeavePlanCatalog.Limits l = rule.limits();
        String name = c.type().getName();
        BigDecimal requested = c.totalDays();

        if (c.halfDay() && !l.allowHalfDay()) return Optional.of("Half-day " + name + " is not allowed.");
        if (c.hours() != null) {
            if (!l.allowHourly()) return Optional.of(name + " can't be taken by the hour.");
            if (l.minHours() != null && c.hours().compareTo(l.minHours()) < 0) {
                return Optional.of(name + " by the hour must be at least " + fmt(l.minHours()) + " hour(s).");
            }
        }
        if (l.minDaysPerRequest() != null && requested.compareTo(l.minDaysPerRequest()) < 0) {
            return Optional.of(name + " requests must be at least " + daysText(l.minDaysPerRequest()) + ".");
        }
        if (l.maxDaysPerRequest() != null && requested.compareTo(l.maxDaysPerRequest()) > 0) {
            return Optional.of(name + " requests can be at most " + daysText(l.maxDaysPerRequest()) + " (this one is "
                    + daysText(requested) + ").");
        }
        if (!atApproval && l.noticeDays() != null && l.noticeDays() > 0
                && (l.noticeAppliesOverDays() == null || requested.compareTo(l.noticeAppliesOverDays()) > 0)) {
            LocalDate applyBy = c.start().minusDays(l.noticeDays());
            if (today.isAfter(applyBy)) {
                return Optional.of(name + (l.noticeAppliesOverDays() != null ? " over " + daysText(l.noticeAppliesOverDays()) : "")
                        + " needs " + l.noticeDays() + " day(s)' notice: leave starting " + c.start().format(DAY)
                        + " had to be applied for by " + applyBy.format(DAY) + ".");
            }
        }
        List<LeaveRequest> sameType = others.stream().filter(r -> r.getLeaveType().getId().equals(c.type().getId())).toList();
        if (l.maxRequestsPerMonth() != null) {
            YearMonth month = YearMonth.from(c.start());
            long count = sameType.stream().filter(r -> YearMonth.from(r.getStartDate()).equals(month)).count();
            if (count + 1 > l.maxRequestsPerMonth()) {
                return Optional.of("You can make at most " + l.maxRequestsPerMonth() + " " + name + " request(s) a month; "
                        + month.format(MONTH) + " already has " + count + ".");
            }
        }
        if (l.maxRequestsPerYear() != null) {
            int year = c.start().getYear();
            long count = sameType.stream().filter(r -> r.getStartDate().getYear() == year).count();
            if (count + 1 > l.maxRequestsPerYear()) {
                return Optional.of("You can make at most " + l.maxRequestsPerYear() + " " + name + " request(s) a year; "
                        + year + " already has " + count + ".");
            }
        }
        if (l.maxConsecutiveDays() != null) {
            BigDecimal run = requested.add(adjacentRun(sameType, c.start(), c.end(), days));
            if (run.compareTo(l.maxConsecutiveDays()) > 0) {
                return Optional.of(name + " can be taken for at most " + daysText(l.maxConsecutiveDays())
                        + " in a row; with your adjacent " + name + " this would be " + daysText(run) + ".");
            }
        }
        // Clubbing: either side may forbid the other being taken next to it.
        LocalDate before = days.previousWorkingDay(c.start());
        LocalDate after = days.nextWorkingDay(c.end());
        for (LeaveRequest r : others) {
            if (r.getLeaveType().getId().equals(c.type().getId())) continue;
            boolean adjacent = (before != null && covers(r, before)) || (after != null && covers(r, after));
            if (!adjacent) continue;
            UUID otherType = r.getLeaveType().getId();
            boolean blocked = l.clubbingBlocked().contains(otherType)
                    || ruleOfType.apply(otherType, r.getStartDate())
                            .map(o -> o.limits().clubbingBlocked().contains(c.type().getId())).orElse(false);
            if (blocked) {
                return Optional.of(name + " can't be taken next to " + typeName.apply(otherType) + " (your "
                        + typeName.apply(otherType) + " on " + r.getStartDate().format(DAY)
                        + (r.getEndDate().equals(r.getStartDate()) ? "" : " – " + r.getEndDate().format(DAY)) + ").");
            }
        }
        if (l.attachmentRequiredOverDays() != null && requested.compareTo(l.attachmentRequiredOverDays()) > 0 && !c.hasAttachment()) {
            return Optional.of(name + (l.attachmentRequiredOverDays().signum() > 0 ? " over " + daysText(l.attachmentRequiredOverDays()) : "")
                    + " needs an attachment" + attachmentHint(l) + ".");
        }
        return Optional.empty();
    }

    /** Days of same-type leave directly before and after [start, end], with only non-working days in between, chained. */
    static BigDecimal adjacentRun(List<LeaveRequest> sameType, LocalDate start, LocalDate end, LeaveCalendar.Days days) {
        BigDecimal total = BigDecimal.ZERO;
        List<LeaveRequest> sorted = new ArrayList<>(sameType);
        sorted.sort(Comparator.comparing(LeaveRequest::getStartDate));
        LocalDate edge = start;
        for (int guard = 0; guard < sorted.size(); guard++) {
            LocalDate prev = days.previousWorkingDay(edge);
            if (prev == null) break;
            Optional<LeaveRequest> r = sorted.stream().filter(x -> covers(x, prev)).findFirst();
            if (r.isEmpty()) break;
            total = total.add(r.get().getTotalDays());
            edge = r.get().getStartDate();
        }
        edge = end;
        for (int guard = 0; guard < sorted.size(); guard++) {
            LocalDate next = days.nextWorkingDay(edge);
            if (next == null) break;
            Optional<LeaveRequest> r = sorted.stream().filter(x -> covers(x, next)).findFirst();
            if (r.isEmpty()) break;
            total = total.add(r.get().getTotalDays());
            edge = r.get().getEndDate();
        }
        return total;
    }

    /** " (PDF, JPG, max 5 MB)" from the rule's allowed types and size, or "". */
    static String attachmentHint(LeavePlanCatalog.Limits l) {
        List<String> parts = new ArrayList<>();
        if (l.attachmentAllowedTypes() != null && !l.attachmentAllowedTypes().isBlank()) {
            parts.add(String.join(", ", allowedExtensions(l).stream().map(e -> e.toUpperCase(Locale.ROOT)).toList()));
        }
        if (l.attachmentMaxMb() != null) parts.add("max " + l.attachmentMaxMb() + " MB");
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    static List<String> allowedExtensions(LeavePlanCatalog.Limits l) {
        if (l.attachmentAllowedTypes() == null) return List.of();
        return java.util.Arrays.stream(l.attachmentAllowedTypes().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")).filter(s -> !s.isEmpty()).toList();
    }

    /** Checks an uploaded file against the rule; null = fine. */
    static String checkFile(LeavePlanCatalog.Limits l, String typeName, String fileName, long sizeBytes) {
        List<String> allowed = allowedExtensions(l);
        String ext = fileName == null || !fileName.contains(".") ? "" : fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!allowed.isEmpty() && !allowed.contains(ext)) {
            return typeName + " attachments must be " + String.join(", ", allowed.stream().map(e -> e.toUpperCase(Locale.ROOT)).toList()) + ".";
        }
        long max = (l.attachmentMaxMb() != null ? l.attachmentMaxMb() : 10) * 1024L * 1024L;
        if (sizeBytes > max) {
            return typeName + " attachments can be at most " + (max / (1024 * 1024)) + " MB.";
        }
        return null;
    }

    private static boolean covers(LeaveRequest r, LocalDate d) {
        return !d.isBefore(r.getStartDate()) && !d.isAfter(r.getEndDate());
    }

    static String daysText(BigDecimal d) {
        return fmt(d) + (d.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
    }

    private static String fmt(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
