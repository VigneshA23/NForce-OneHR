package com.nforce.onehr.ai.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic date-range parser behind every My Team AI question that names a period.
 * Anchored to a fixed "today" throughout, exactly like the business date every other date
 * computation in this system uses - never left for the model to compute.
 */
class MyTeamDateRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25); // a Friday

    @Test
    void today() {
        var r = MyTeamDateRange.resolve("who is in today", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY);
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void yesterday() {
        var r = MyTeamDateRange.resolve("who was late yesterday", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void thisWeek_isMondayOfTheCurrentWeekThroughToday() {
        var r = MyTeamDateRange.resolve("show my team's attendance for this week", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 9, 21)); // the Monday
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void last7Days_isARollingWindowEndingToday_distinctFromThisWeek() {
        var r = MyTeamDateRange.resolve("who worked the most hours in the last 7 days", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void last30Days() {
        var r = MyTeamDateRange.resolve("show penalties over the last 30 days", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void pastWeek_meansTheSameRollingWindowAsLast7Days() {
        var r = MyTeamDateRange.resolve("who was late in the past week", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void thisMonth() {
        var r = MyTeamDateRange.resolve("team effort this month", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void lastMonth_isTheWholePreviousCalendarMonth() {
        var r = MyTeamDateRange.resolve("punctuality report for last month", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void thisQuarter() {
        var r = MyTeamDateRange.resolve("negligence this quarter", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 7, 1)); // Q3 starts July
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void lastQuarter_isTheWholePreviousCalendarQuarter() {
        var r = MyTeamDateRange.resolve("show last quarter", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 4, 1)); // Q2
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void explicitIsoRange_betweenXAndY() {
        var r = MyTeamDateRange.resolve("between 2026-09-01 and 2026-09-10", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void explicitIsoRange_fromXToY() {
        var r = MyTeamDateRange.resolve("attendance from 2026-08-01 to 2026-08-15", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void explicitRange_isCappedAtBusinessToday_neverExtendsIntoTheFuture() {
        var r = MyTeamDateRange.resolve("from 2026-09-01 to 2026-12-31", TODAY, 7);
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void explicitRange_backwardsIsIgnored_fallsBackToTheDefault() {
        var r = MyTeamDateRange.resolve("from 2026-09-10 to 2026-09-01", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void noRecognisableExpression_fallsBackToTheCallersDefaultWindow() {
        var r = MyTeamDateRange.resolve("who was on time the most", TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(r.to()).isEqualTo(TODAY);

        var r30 = MyTeamDateRange.resolve("which team members have attendance penalties", TODAY, 30);
        assertThat(r30.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(r30.to()).isEqualTo(TODAY);
    }

    @Test
    void nullQuestion_fallsBackToTheDefaultWindow_neverThrows() {
        var r = MyTeamDateRange.resolve(null, TODAY, 7);
        assertThat(r.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(r.to()).isEqualTo(TODAY);
    }

    @Test
    void everyRangeIsAnchoredToTheGivenBusinessToday_neverTheJvmClock() {
        // Regardless of what today the JVM itself thinks it is, "today" always resolves to the
        // business date this method was handed - the same guarantee every other date computation
        // in this system relies on (see PromptBuilder's CURRENT DATE & TIME section).
        LocalDate otherDay = LocalDate.of(2020, 1, 1);
        var r = MyTeamDateRange.resolve("today", otherDay, 7);
        assertThat(r.from()).isEqualTo(otherDay);
        assertThat(r.to()).isEqualTo(otherDay);
    }
}
