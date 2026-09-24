package com.expenseTracker.userService.Validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class ValidCurrencyValidator implements ConstraintValidator<ValidCurrency, String> {

    private static final Pattern THREE_ASCII_LETTERS = Pattern.compile("[A-Za-z]{3}");

    /** ISO-4217 "no currency" (XXX) and testing (XTS) codes: known to java.util.Currency, useless as a default. */
    static final Set<String> PSEUDO_CURRENCIES = Set.of("XXX", "XTS");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // ASCII only: Locale.ROOT upper-casing would otherwise map e.g. a dotless i onto a valid code.
        if (!THREE_ASCII_LETTERS.matcher(value).matches()) {
            return false;
        }
        String code = value.toUpperCase(Locale.ROOT);
        if (PSEUDO_CURRENCIES.contains(code)) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
