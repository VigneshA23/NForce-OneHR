package com.nforce.onehr.dto.assignments;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Filter-dropdown options for the Employee Assignments table (ONEHR-108). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentLookupsResponse {

    private List<PolicyOption> shifts;
    private List<PolicyOption> weeklyOffPolicies;
    private List<PolicyOption> penalisationPolicies;
    private List<String> departments;
    private List<String> locations;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PolicyOption {
        private UUID id;
        private String name;
    }
}
