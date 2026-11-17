package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Full replace — one small settings form saved as a whole, same convention as
 * UpdatePayGroupFilingDetailsRequest. */
@Data
public class UpdatePayslipSettingsRequest {

    private boolean passwordProtected;

    @Size(max = 50)
    private String documentHeaderText;

    private String footerText;

    private String logoAlignment;

    private boolean excludeNaFields;

    @Size(max = 50)
    private String earningsHeading;

    @Size(max = 50)
    private String deductionsHeading;

    private boolean includeLeaveSummary;

    private boolean includeArrearBreakup;

    private boolean includeYtdColumn;

    private boolean includeEmployerContributions;

    /** Null or empty means "use the default set, in the catalog's own order" — see
     * PayslipFields.VALID_KEYS. */
    private List<String> payslipFields;
}
