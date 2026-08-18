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
    // At least one letter or digit (rejects emoji-only / symbol-only input like
    // "🎉🎊🪔" or "!!!@@@###"), and only letters (Unicode-aware — accented
    // characters like "Deepāvali" are \p{L}), digits, spaces, apostrophes, and
    // hyphens otherwise ("New Year's Day", "Eid al-Fitr").
    @Pattern(
            regexp = "^(?=.*[\\p{L}\\p{N}])[\\p{L}\\p{N} '-]+$",
            message = "Holiday name must contain at least one letter or number, and only letters, numbers, spaces, apostrophes, or hyphens")
    private String holidayName;

    @NotNull
    private LocalDate holidayDate;

    @NotNull
    private UUID locationId;
}
