package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * "Who knows Java?" — Java AND Selenium, or Java OR Python, per the spec's own two named
 * examples. A single {@code matchMode} applied uniformly across every id in {@code skillIds}
 * covers exactly those two cases; a fully general nested AND/OR boolean expression tree isn't
 * something the spec actually asks for (both examples are flat), so this deliberately doesn't
 * build one.
 */
@Data
public class TalentSearchRequest {

    private List<UUID> skillIds;

    // ALL (AND — every id in skillIds) | ANY (OR — at least one)
    @Pattern(regexp = "^(ALL|ANY)$")
    private String matchMode = "ANY";

    // Optional: only employees whose best matching skill is at least this proficiency (by
    // sort_order) — e.g. searching with Advanced also returns Expert.
    private UUID minimumProficiencyLevelId;

    // true = must have at least one certificate, false = must have none, null = don't care
    private Boolean certified;

    private String department;
    private String location;
    private String managerName;

    // Planned | In Progress | Completed
    private String learningStatus;

    private int page = 0;
    private int size = 20;

    // BEST_MATCH (default) | NAME
    @Pattern(regexp = "^(BEST_MATCH|NAME)$")
    private String sortBy = "BEST_MATCH";
}
