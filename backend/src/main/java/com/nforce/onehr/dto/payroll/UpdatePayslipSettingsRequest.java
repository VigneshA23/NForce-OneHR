package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Full replace — one small settings form saved as a whole, same convention as
 * UpdatePayGroupFilingDetailsRequest. */
@Data
public class UpdatePayslipSettingsRequest {

    private boolean passwordProtected;

    @Size(max = 50)
    private String documentHeaderText;

    private String footerText;
}
