package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PayGroupResponse {
    private UUID id;
    private String name;
    private String legalEntity;
    private String frequency;
    private String currency;
    private int cycleStartDay;
    private int cycleEndDay;
    private int processingDay;
    private boolean active;
}
