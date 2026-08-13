package com.nforce.onehr.dto.helpcontent;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** {@code attachmentIds} in the desired display order — must be exactly the content's current attachment ids. */
@Data
public class ReorderAttachmentsRequest {

    @NotEmpty(message = "Attachment order is required")
    private List<UUID> attachmentIds;
}
