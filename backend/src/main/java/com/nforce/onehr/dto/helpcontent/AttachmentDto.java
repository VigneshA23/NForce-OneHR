package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AttachmentDto {
    private UUID id;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private int displayOrder;
}
