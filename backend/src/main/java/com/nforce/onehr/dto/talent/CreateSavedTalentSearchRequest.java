package com.nforce.onehr.dto.talent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSavedTalentSearchRequest {

    @NotBlank
    private String name;

    @NotNull
    @Valid
    private TalentSearchRequest query;
}
