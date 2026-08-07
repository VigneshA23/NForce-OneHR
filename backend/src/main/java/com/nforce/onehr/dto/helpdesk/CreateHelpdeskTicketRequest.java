package com.nforce.onehr.dto.helpdesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Backs the "Contact HR Support" modal — Topic (category) + Description only. */
@Data
public class CreateHelpdeskTicketRequest {

    @NotNull(message = "Topic is required")
    private Integer categoryId;

    @NotBlank(message = "Description is required")
    private String description;
}
