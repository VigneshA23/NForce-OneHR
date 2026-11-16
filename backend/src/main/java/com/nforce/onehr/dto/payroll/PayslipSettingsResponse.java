package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PayslipSettingsResponse {
    private UUID payGroupId;
    private boolean passwordProtected;
    private String documentHeaderText;
    private String footerText;
}
