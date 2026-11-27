package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** LV-02: each plan rule a leave request can break, and the message the employee sees. */
class LeaveRuleValidatorTest {

    private static final UUID EMP = UUID.randomUUID();
    private final LeaveType sick = LeaveType.builder().id(UUID.randomUUID()).code("SICK").name("Sick Leave").build();
    private final LeaveType casual = LeaveType.builder().id(UUID.randomUUID()).code("CASUAL").name("Casual Leave").build();
    /** Monday. */
    private static final LocalDate MON = LocalDate.of(2027, 3, 1);
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 1);

    /** Saturday/Sunday off, 3 Mar 2027 (Wednesday) a holiday. */
    private final LeaveCalendar.Days days = new LeaveCalendar.Days() {
        @Override
        public String holiday(LocalDate date) {
            return date.equals(LocalDate.of(2027, 3, 3)) ? "Founders' Day" : null;
        }

        @Override
        public boolean weeklyOff(LocalDate date) {
            return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        }
    };

    private static LeavePlanCatalog.Limits limits(java.util.function.UnaryOperator<Map<String, Object>> edit) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("allowHalfDay", true);
        m.put("allowHourly", false);
        m.put("clubbing", Set.of());
        m = edit.apply(m);
        @SuppressWarnings("unchecked") Set<UUID> clubbing = (Set<UUID>) m.get("clubbing");
        return new LeavePlanCatalog.Limits("QUOTA", (BigDecimal) m.get("minDays"), (BigDecimal) m.get("maxDays"),
                (Integer) m.get("perMonth"), (Integer) m.get("perYear"), (BigDecimal) m.get("consecutive"),
                (Integer) m.get("notice"), (BigDecimal) m.get("noticeOver"), (Boolean) m.get("allowHalfDay"),
                (Boolean) m.get("allowHourly"), (BigDecimal) m.get("minHours"), (BigDecimal) m.get("attachOver"),
                (String) m.get("types"), (Integer) m.get("maxMb"), clubbing);
    }

    private LeavePlanCatalog.TypeRule rule(LeaveType type, LeavePlanCatalog.Limits l) {
        return new LeavePlanCatalog.TypeRule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type.getId(),
                new BigDecimal("12"), "YEARLY", false, null, null, null, null, l);
    }

    private LeaveRuleValidator.Candidate candidate(LeaveType type, LocalDate start, LocalDate end, String totalDays) {
        return new LeaveRuleValidator.Candidate(EMP, type, start, end, new BigDecimal(totalDays), false, null, false, null);
    }

    private LeaveRequest existing(LeaveType type, LocalDate start, LocalDate end, String totalDays) {
        return LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(EMP).leaveType(type).startDate(start).endDate(end)
                .totalDays(new BigDecimal(totalDays)).status("APPROVED").build();
    }

    private Optional<String> check(LeaveRuleValidator.Candidate c, LeavePlanCatalog.Limits l, List<LeaveRequest> others) {
        return check(c, l, others, false, Map.of());
    }

    private Optional<String> check(LeaveRuleValidator.Candidate c, LeavePlanCatalog.Limits l, List<LeaveRequest> others,
                                   boolean atApproval, Map<UUID, LeavePlanCatalog.Limits> otherRules) {
        return LeaveRuleValidator.check(c, rule(c.type(), l), TODAY, atApproval, others, days,
                (typeId, date) -> Optional.ofNullable(otherRules.get(typeId)).map(o -> rule(typeId.equals(sick.getId()) ? sick : casual, o)),
                typeId -> typeId.equals(sick.getId()) ? sick.getName() : casual.getName());
    }

    @Test
    void noLimits_passes() {
        assertTrue(check(candidate(sick, MON, MON.plusDays(1), "2"), LeavePlanCatalog.Limits.NONE, List.of()).isEmpty());
    }

    @Test
    void halfDay_notAllowed() {
        var c = new LeaveRuleValidator.Candidate(EMP, sick, MON, MON, new BigDecimal("0.5"), true, null, false, null);
        assertEquals("Half-day Sick Leave is not allowed.",
                check(c, limits(m -> { m.put("allowHalfDay", false); return m; }), List.of()).orElseThrow());
    }

    @Test
    void hourly_notAllowed_andMinimumHours() {
        var c = new LeaveRuleValidator.Candidate(EMP, sick, MON, MON, new BigDecimal("0.25"), false, new BigDecimal("2"), false, null);
        assertEquals("Sick Leave can't be taken by the hour.", check(c, LeavePlanCatalog.Limits.NONE, List.of()).orElseThrow());
        assertEquals("Sick Leave by the hour must be at least 3 hour(s).", check(c, limits(m -> {
            m.put("allowHourly", true);
            m.put("minHours", new BigDecimal("3"));
            return m;
        }), List.of()).orElseThrow());
    }

    @Test
    void minAndMaxDaysPerRequest() {
        var l = limits(m -> { m.put("minDays", new BigDecimal("2")); m.put("maxDays", new BigDecimal("3")); return m; });
        assertEquals("Sick Leave requests must be at least 2 days.", check(candidate(sick, MON, MON, "1"), l, List.of()).orElseThrow());
        assertEquals("Sick Leave requests can be at most 3 days (this one is 4 days).",
                check(candidate(sick, MON, MON.plusDays(3), "4"), l, List.of()).orElseThrow());
        assertTrue(check(candidate(sick, MON, MON.plusDays(1), "2"), l, List.of()).isEmpty());
    }

    @Test
    void notice_onlyOverThreshold_andNotAtApproval() {
        var l = limits(m -> { m.put("notice", 30); m.put("noticeOver", new BigDecimal("2")); return m; });
        // Starts 28 days after TODAY: 30 days' notice not given.
        var longOne = candidate(sick, MON, MON.plusDays(3), "4");
        String msg = check(longOne, l, List.of()).orElseThrow();
        assertEquals("Sick Leave over 2 days needs 30 day(s)' notice: leave starting Mon 1 Mar 2027 had to be applied for by "
                + "Sat 30 Jan 2027.", msg);
        assertTrue(check(candidate(sick, MON, MON.plusDays(1), "2"), l, List.of()).isEmpty(), "2 days is not over the threshold");
        assertTrue(check(longOne, l, List.of(), true, Map.of()).isEmpty(), "approval doesn't re-check notice");
    }

    @Test
    void maxRequestsPerMonthAndYear_countPendingAndApprovedOfTheSameType() {
        var perMonth = limits(m -> { m.put("perMonth", 1); return m; });
        List<LeaveRequest> others = List.of(existing(sick, MON.plusDays(14), MON.plusDays(14), "1"),
                existing(casual, MON.plusDays(21), MON.plusDays(21), "1"));
        assertEquals("You can make at most 1 Sick Leave request(s) a month; March 2027 already has 1.",
                check(candidate(sick, MON, MON, "1"), perMonth, others).orElseThrow());
        var perYear = limits(m -> { m.put("perYear", 2); return m; });
        List<LeaveRequest> two = List.of(existing(sick, LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 11), "1"),
                existing(sick, LocalDate.of(2027, 2, 8), LocalDate.of(2027, 2, 8), "1"));
        assertEquals("You can make at most 2 Sick Leave request(s) a year; 2027 already has 2.",
                check(candidate(sick, MON, MON, "1"), perYear, two).orElseThrow());
    }

    @Test
    void maxConsecutive_joinsAdjacentRequestsAcrossTheWeekend() {
        var l = limits(m -> { m.put("consecutive", new BigDecimal("3")); return m; });
        // Thu-Fri before, then Mon: 3 days in a row across Sat/Sun — fine; Mon-Tue would be 4.
        List<LeaveRequest> others = List.of(existing(sick, MON.minusDays(4), MON.minusDays(3), "2"));
        assertTrue(check(candidate(sick, MON, MON, "1"), l, others).isEmpty());
        assertEquals("Sick Leave can be taken for at most 3 days in a row; with your adjacent Sick Leave this would be 4 days.",
                check(candidate(sick, MON, MON.plusDays(1), "2"), l, others).orElseThrow());
        // Across the Wednesday holiday: Tue + Thu are adjacent.
        List<LeaveRequest> tue = List.of(existing(sick, MON.plusDays(1), MON.plusDays(1), "1"));
        assertEquals(new BigDecimal("1"), LeaveRuleValidator.adjacentRun(tue, MON.plusDays(3), MON.plusDays(3), days));
    }

    @Test
    void clubbing_isCheckedFromBothSides() {
        // Casual Leave on the Friday before; Sick Leave on Monday — only the weekend in between.
        List<LeaveRequest> others = List.of(existing(casual, MON.minusDays(3), MON.minusDays(3), "1"));
        var blocksCasual = limits(m -> { m.put("clubbing", Set.of(casual.getId())); return m; });
        assertEquals("Sick Leave can't be taken next to Casual Leave (your Casual Leave on Fri 26 Feb 2027).",
                check(candidate(sick, MON, MON, "1"), blocksCasual, others).orElseThrow());
        // Sick itself has no clubbing rule, but Casual's rule blocks Sick.
        var casualBlocksSick = limits(m -> { m.put("clubbing", Set.of(sick.getId())); return m; });
        assertTrue(check(candidate(sick, MON, MON, "1"), LeavePlanCatalog.Limits.NONE, others, false,
                Map.of(casual.getId(), casualBlocksSick)).isPresent());
        // A working day in between: not adjacent.
        assertTrue(check(candidate(sick, MON.plusDays(1), MON.plusDays(1), "1"), blocksCasual, others).isEmpty());
    }

    @Test
    void attachment_requiredOverDays_withHint() {
        var l = limits(m -> {
            m.put("attachOver", new BigDecimal("2"));
            m.put("types", "pdf, .JPG");
            m.put("maxMb", 5);
            return m;
        });
        assertEquals("Sick Leave over 2 days needs an attachment (PDF, JPG, max 5 MB).",
                check(candidate(sick, MON, MON.plusDays(3), "4"), l, List.of()).orElseThrow());
        assertTrue(check(candidate(sick, MON, MON.plusDays(1), "2"), l, List.of()).isEmpty());
        var withFile = new LeaveRuleValidator.Candidate(EMP, sick, MON, MON.plusDays(3), new BigDecimal("4"), false, null, true, null);
        assertTrue(check(withFile, l, List.of()).isEmpty());
    }

    @Test
    void checkFile_typeAndSize() {
        var l = limits(m -> { m.put("types", "pdf,jpg"); m.put("maxMb", 2); return m; });
        assertEquals("Sick Leave attachments must be PDF, JPG.", LeaveRuleValidator.checkFile(l, "Sick Leave", "note.docx", 10));
        assertEquals("Sick Leave attachments can be at most 2 MB.",
                LeaveRuleValidator.checkFile(l, "Sick Leave", "note.PDF", 3L * 1024 * 1024));
        assertNull(LeaveRuleValidator.checkFile(l, "Sick Leave", "note.pdf", 1024));
        // No rule: any type, 10 MB.
        assertNull(LeaveRuleValidator.checkFile(LeavePlanCatalog.Limits.NONE, "Sick Leave", "x.zip", 9L * 1024 * 1024));
        assertNotNull(LeaveRuleValidator.checkFile(LeavePlanCatalog.Limits.NONE, "Sick Leave", "x.zip", 11L * 1024 * 1024));
    }
}
