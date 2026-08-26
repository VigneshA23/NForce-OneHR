package com.nforce.onehr.dto.helpdesk;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTicketStatusRequest {

    @NotBlank(message = "Status is required")
    private String status;

    /** Optional HR comment recorded as a system-authored reply alongside the transition. */
    private String comment;
}
