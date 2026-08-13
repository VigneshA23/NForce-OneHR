package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Comparison of one approval attempt against the immediately preceding attempt for the same content, if any. */
@Data
@Builder
public class ApprovalDiffDto {
    private ApprovalAttemptDto previous; // null if this is the first attempt
    private ApprovalAttemptDto current;
    private boolean modified; // true if any field or attachment changed (or previous == null)
    private List<FieldChangeDto> fieldChanges;
    private List<AttachmentChangeDto> attachmentChanges;
}
