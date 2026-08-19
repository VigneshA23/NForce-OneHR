package com.nforce.onehr.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure jakarta.validation.Validator tests — no Spring context needed to
 * exercise Bean Validation annotations, consistent with this project's
 * general preference for avoiding @SpringBootTest/H2 (see LeaveServiceTest).
 */
class CreateHolidayRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private CreateHolidayRequest request(String name) {
        CreateHolidayRequest req = new CreateHolidayRequest();
        req.setHolidayName(name);
        req.setHolidayDate(LocalDate.now());
        req.setLocationId(UUID.randomUUID());
        return req;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "🎉🎊🪔", "!!!@@@###$$$%%%", "!!!@@@###$$$%%%😁", "😁😂🎉", "123456", "-----", "'''", "   "
    })
    void rejectsNamesWithNoLetter(String invalidName) {
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request(invalidName));
        assertFalse(violations.isEmpty(), "expected a violation for: " + invalidName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "New Year's Day", "Eid-ul-Fitr", "Eid al-Fitr", "Deepāvali", "Independence Day 2026", "Diwali", "4th of July"
    })
    void allowsLegitimateHolidayNames(String validName) {
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request(validName));
        assertTrue(violations.isEmpty(), "unexpected violations for: " + validName + " -> " + violations);
    }

    @Test
    void rejectsBlankName() {
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request(""));
        assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsNameOverMaxLength() {
        String tooLong = "a".repeat(101);
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request(tooLong));
        assertFalse(violations.isEmpty());
    }

    @Test
    void allowsNameAtMaxLength() {
        String exactly100 = "a".repeat(100);
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request(exactly100));
        assertTrue(violations.isEmpty());
    }

    @Test
    void rejectsMixedEmojiAndSymbols() {
        Set<ConstraintViolation<CreateHolidayRequest>> violations = validator.validate(request("🎉!!!@@@"));
        assertFalse(violations.isEmpty());
    }
}
