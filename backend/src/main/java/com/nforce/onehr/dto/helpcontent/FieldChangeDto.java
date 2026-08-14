package com.nforce.onehr.dto.helpcontent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Field-level comparison of one text field between two approval attempts (or none, if this is the first). */
@Data
@Builder
public class FieldChangeDto {
    private String fieldName; // title | description | body | category
    private boolean changed;
    private String oldValue;
    private String newValue;
    private List<DiffSegmentDto> segments; // word-level diff, oldValue -> newValue
}
