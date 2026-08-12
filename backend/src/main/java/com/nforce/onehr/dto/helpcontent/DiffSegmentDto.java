package com.nforce.onehr.dto.helpcontent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** One word-level span of a text-field diff. {@code type}: EQUAL | ADDED | REMOVED. */
@Data
@Builder
@AllArgsConstructor
public class DiffSegmentDto {
    private String type;
    private String text;
}
