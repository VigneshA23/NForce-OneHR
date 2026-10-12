package com.nforce.onehr.dto.bench;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** US-A01: end a bench episode. {@code endDate} defaults to today when omitted. */
@Getter @Setter
public class EndBenchRequest {
    private LocalDate endDate;
}
