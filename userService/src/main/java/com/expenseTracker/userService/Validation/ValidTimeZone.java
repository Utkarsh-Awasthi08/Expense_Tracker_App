package com.expenseTracker.userService.Validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A zone id accepted by {@link java.time.ZoneId#of(String)}; null passes (optional field). */
@Documented
@Constraint(validatedBy = ValidTimeZoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimeZone {

    String message() default "must be a valid time zone id such as Asia/Kolkata";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
