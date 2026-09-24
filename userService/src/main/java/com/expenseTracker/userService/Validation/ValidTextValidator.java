package com.expenseTracker.userService.Validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidTextValidator implements ConstraintValidator<ValidText, String> {

    static final String CONTROL_MESSAGE = "must not contain control characters";
    static final String BLANK_MESSAGE = "must not be blank";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // Control characters first: a tab or NUL is both blank-looking and a control character, and the more
        // specific message is the useful one. One violation per field either way.
        if (UnicodeText.hasControlCharacters(value)) {
            return fail(context, CONTROL_MESSAGE);
        }
        if (UnicodeText.isBlank(value)) {
            return fail(context, BLANK_MESSAGE);
        }
        return true;
    }

    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
