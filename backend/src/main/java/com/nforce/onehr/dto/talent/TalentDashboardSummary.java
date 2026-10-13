package com.nforce.onehr.dto.talent;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Not included here, by deliberate scope decision (flagged to the user, not silently omitted):
 * <ul>
 *   <li><b>Skill gap view</b> (required skills vs. headcount who has them) — there is no
 *   "required skill" concept anywhere in this data model (per role/department/designation); it
 *   would need a new master concept this phase's spec didn't define the shape of.</li>
 *   <li><b>Learning completion trend over time</b> — needs a time-bucketing granularity (weekly?
 *   monthly?) with no UI contract yet; deferred to when the chart is actually being built.</li>
 *   <li><b>"Overdue" learning status</b> — {@code employee_learning_entries} has no due/target
 *   date field at all, only {@code startDate}/{@code completedDate}, so "overdue" isn't
 *   derivable; {@link #learningStatusCounts} uses the 3 real stored values only (Planned is
 *   surfaced as "Not Started").</li>
 * </ul>
 */
@Value
@Builder
public class TalentDashboardSummary {
    long totalEmployees;
    long withSkills;
    long withCertificates;
    long withActiveLearningTrack;
    long certificatesExpiringIn30Days;

    List<NameCount> topSkills;
    List<NameCount> proficiencyDistribution;
    Map<String, List<NameCount>> skillsByDepartment;
    List<NameCount> certificatesByCategory;
    CertificateExpiryBreakdown certificateExpiryBreakdown;
    List<NameCount> learningStatusCounts;
}
