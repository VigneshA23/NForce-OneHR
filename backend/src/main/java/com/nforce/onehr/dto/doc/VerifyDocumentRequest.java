package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VerifyDocumentRequest {

    @NotNull
    private String action; // "VERIFY" or "REJECT"

    private String rejectionReason;
}
