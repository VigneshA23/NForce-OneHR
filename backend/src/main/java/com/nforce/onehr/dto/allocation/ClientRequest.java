package com.nforce.onehr.dto.allocation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ClientRequest {

    @NotBlank(message = "Client name is required")
    @Size(max = 255, message = "Client name must be at most 255 characters")
    private String name;
}
