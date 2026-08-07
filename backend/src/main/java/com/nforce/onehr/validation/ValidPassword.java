package com.nforce.onehr.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordConstraintValidator.class)
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default "Password must include at least 3 of: uppercase letter, lowercase letter, number, special character";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
