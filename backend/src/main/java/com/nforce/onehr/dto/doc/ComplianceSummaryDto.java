package com.nforce.onehr.dto.doc;

import lombok.Value;

@Value
public class ComplianceSummaryDto {
    int totalRequired;
    int uploaded;
    int verified;
    int pendingVerification;
    int rejected;
    int missing;
    int expiringSoon;
    int pendingPolicies;
}
