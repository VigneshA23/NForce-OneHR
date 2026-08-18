package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateHolidayRequest {
    @NotBlank(message = "Holiday name is required")
    @Size(max = 100, message = "Holiday name must be 100 characters or fewer")
    // Must contain at least one actual letter — rejects emoji-only, symbol-only,
    // and digit-only input ("!!!@@@###$$$%%%😁", "🎉🎊🪔", "123456"). Otherwise
    // limited to letters (Unicode-aware, so accented names like "Deepāvali" are
    // \p{L}), digits (so "Independence Day 2026" is fine), spaces, apostrophes,
    // and hyphens ("New Year's Day", "Eid-ul-Fitr").
    @Pattern(
            regexp = "^(?=.*[\\p{L}])[\\p{L}\\p{N} '-]+$",
            message = "Holiday name must contain at least one letter, and only letters, numbers, spaces, apostrophes, or hyphens")
    private String holidayName;

    @NotNull
    private LocalDate holidayDate;

    @NotNull
    private UUID locationId;
}
