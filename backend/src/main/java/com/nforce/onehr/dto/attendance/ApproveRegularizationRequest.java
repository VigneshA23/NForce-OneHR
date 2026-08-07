package com.nforce.onehr.dto.attendance;

import lombok.*;

// Comment is optional on approve (unlike reject, where it's mandatory) — an approver
// isn't required to explain a decision they're agreeing with.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApproveRegularizationRequest {

    private String comment;
}
