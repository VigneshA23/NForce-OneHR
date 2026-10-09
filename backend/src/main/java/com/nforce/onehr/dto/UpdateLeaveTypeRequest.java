package com.nforce.onehr.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateLeaveTypeRequest {

    @Size(max = 50)
    private String name;

    @Pattern(regexp = "PAID|UNPAID", message = "classification must be PAID or UNPAID")
    private String classification;
}
