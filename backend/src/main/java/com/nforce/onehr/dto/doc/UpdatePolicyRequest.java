package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePolicyRequest {

    @Size(max = 200)
    private String title;

    private String description;

    @Size(max = 200)
    private String audience;
}
