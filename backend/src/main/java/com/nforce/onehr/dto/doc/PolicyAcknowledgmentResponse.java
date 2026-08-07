package com.nforce.onehr.dto.doc;

import com.nforce.onehr.entity.PolicyAcknowledgment;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class PolicyAcknowledgmentResponse {
    Long id;
    Long policyId;
    String policyTitle;
    String policyVersion;
    UUID employeeUserId;
    String employeeName;
    Instant acknowledgedAt;
    boolean pending;

    public static PolicyAcknowledgmentResponse from(PolicyAcknowledgment a) {
        return from(a, null);
    }

    public static PolicyAcknowledgmentResponse from(PolicyAcknowledgment a, String employeeName) {
        return new PolicyAcknowledgmentResponse(
                a.getId(), a.getPolicy().getId(), a.getPolicy().getTitle(), a.getPolicy().getVersion(),
                a.getEmployeeUserId(), employeeName, a.getAcknowledgedAt(), a.getAcknowledgedAt() == null);
    }
}
