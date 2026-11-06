package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** US-C13 — creates or revises a statutory rule's slab config, version-dated. */
@Getter
@Setter
public class CreateStatutoryRuleRequest {

    @NotBlank
    private String country;

    @NotBlank
    private String ruleType;

    /** Opaque JSON — this story stores and versions it, Phase 3 decides its shape and reads it. */
    @NotBlank
    private String slabConfig;

    @NotNull
    private LocalDate effectiveFrom;
}
