package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DistributePayslipsResponse {
    private int releasedCount;
    private int emailedCount;
    private int emailFailedCount;
}
