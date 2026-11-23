package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PayslipSettingsResponse {
    private UUID payGroupId;
    private boolean passwordProtected;
    private String documentHeaderText;
    private String footerText;
    private boolean hasLogo;
    private String logoAlignment;
    private int logoSize;
    private String logoShape;
    private boolean excludeNaFields;
    private String earningsHeading;
    private String deductionsHeading;
    private boolean includeLeaveSummary;
    private boolean includeArrearBreakup;
    private boolean includeYtdColumn;
    private boolean includeEmployerContributions;
    private List<String> payslipFields;
}
