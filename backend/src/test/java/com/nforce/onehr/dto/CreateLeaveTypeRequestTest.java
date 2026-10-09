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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure jakarta.validation.Validator tests — no Spring context needed, same approach as
 * CreateExpenseCategoryRequestTest/CreateHolidayRequestTest. Covers the mandatory Paid/Unpaid
 * classification (Organization Masters > Leave).
 */
class CreateLeaveTypeRequestTest {

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

    private CreateLeaveTypeRequest request(String code, String name, String classification) {
        CreateLeaveTypeRequest req = new CreateLeaveTypeRequest();
        req.setCode(code);
        req.setName(name);
        req.setClassification(classification);
        return req;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "UNPAID"})
    void allowsValidClassifications(String classification) {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("LOP", "Loss of Pay", classification));
        assertTrue(violations.isEmpty(), "unexpected violations for: " + classification + " -> " + violations);
    }

    @Test
    void rejectsMissingClassification() {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("LOP", "Loss of Pay", null));
        assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsBlankClassification() {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("LOP", "Loss of Pay", ""));
        assertFalse(violations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "unpaid", "Paid", "PAYED", "NONE", "PAID_LEAVE", "LOSS_OF_PAY"})
    void rejectsInvalidClassificationValues(String invalid) {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("LOP", "Loss of Pay", invalid));
        assertFalse(violations.isEmpty(), "expected a violation for: " + invalid);
    }

    @Test
    void rejectsBlankName() {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("LOP", "", "PAID"));
        assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsBlankCode() {
        Set<ConstraintViolation<CreateLeaveTypeRequest>> violations =
                validator.validate(request("", "Loss of Pay", "PAID"));
        assertFalse(violations.isEmpty());
    }
}
