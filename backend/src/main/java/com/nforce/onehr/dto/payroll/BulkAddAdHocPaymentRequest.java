package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Same component/amount/reason applied to every employee with a payslip in this run — see
 *  AdHocPaymentService#bulkAdd. No employeeId: that's the whole point of this request over
 *  {@link AddAdHocPaymentRequest}. */
@Getter
@Setter
public class BulkAddAdHocPaymentRequest {

    @NotBlank
    private String componentName;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank
    private String reason;
}
