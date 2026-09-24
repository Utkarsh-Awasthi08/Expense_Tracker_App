package com.expenseTracker.userService.Validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Free text that is safe to store: not blank in the Unicode sense (NBSP, em space, zero-width space... count as
 * blank) and free of NUL and other control characters. Null passes (optional field); an empty string does not.
 */
@Documented
@Constraint(validatedBy = ValidTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidText {

    String message() default "must not be blank or contain control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
