package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** PATCH semantics: a null field is left unchanged — same convention as UpdateDocumentTypeRequest. */
@Data
public class UpdateSkillMasterRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String category;
}
