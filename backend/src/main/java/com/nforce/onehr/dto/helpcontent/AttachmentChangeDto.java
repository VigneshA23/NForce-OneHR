package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

/** One attachment-level change between two approval attempts. */
@Data
@Builder
public class AttachmentChangeDto {
    private String changeType; // ADDED | REMOVED | REPLACED | REORDERED | UNCHANGED
    private String fileName;
    private String previousFileName;
    private Integer displayOrder;
    private Integer previousDisplayOrder;
}
