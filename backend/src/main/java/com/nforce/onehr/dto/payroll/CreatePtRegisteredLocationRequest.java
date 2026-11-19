package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreatePtRegisteredLocationRequest {

    @NotBlank
    @Size(max = 100)
    private String stateName;

    @NotBlank
    @Size(max = 100)
    private String locationName;

    @Size(max = 50)
    private String establishmentId;

    private LocalDate registrationDate;

    @Size(max = 150)
    private String signatory;

    @Size(max = 150)
    private String signatoryDesignation;

    @Size(max = 150)
    private String signatoryFatherName;
}
