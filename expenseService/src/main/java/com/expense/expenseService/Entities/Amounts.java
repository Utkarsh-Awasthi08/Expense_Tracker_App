package com.expense.expenseService.Entities;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place that decides whether an amount may be stored, and in which form.
 * <p>
 * The order of the checks is the point. {@link BigDecimal#setScale} on a literal such as {@code 1e999999999}
 * or {@code 1e-999999999} has to materialise a number with a billion digits, which burns CPU and memory. So every
 * check that only needs {@code signum()}, {@code precision()} and {@code scale()} (all O(1) for a parsed value)
 * runs first, and rounding only ever sees a value whose scale is small and whose integer part fits the column.
 * <p>
 * Messages deliberately never contain the offending value.
 */
public final class Amounts {

    /** Fractional digits kept (rounded HALF_UP). */
    public static final int SCALE = 2;
    /** DECIMAL(19,2). */
    public static final int PRECISION = 19;
    /** At most 17 integer digits fit DECIMAL(19,2). */
    public static final int MAX_INTEGER_DIGITS = PRECISION - SCALE;
    /** Inputs may carry more decimals than are kept, but not an absurd number of them. */
    public static final int MAX_INPUT_SCALE = 64;
    /** No legitimate amount literal is longer than this; longer text is refused before it is even parsed. */
    public static final int MAX_LITERAL_LENGTH = 100;

    public static final String REQUIRED = "Expense amount is required";
    public static final String NOT_POSITIVE = "Expense amount must be greater than zero";
    public static final String OUT_OF_RANGE = "Expense amount exceeds the supported range";
    public static final String TOO_PRECISE = "Expense amount has too many decimal places";
    public static final String NOT_A_NUMBER = "Expense amount must be a decimal number";

    private Amounts() {
    }

    /**
     * Returns the amount normalised to scale 2 (HALF_UP).
     *
     * @throws IllegalArgumentException if the amount is null, not strictly positive (also after rounding),
     *                                  has more than 17 integer digits or an absurd number of decimals
     */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException(REQUIRED);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(NOT_POSITIVE);
        }
        // O(1) checks first: nothing below may run on an unbounded exponent.
        long integerDigits = (long) amount.precision() - amount.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        if (amount.scale() > MAX_INPUT_SCALE) {
            throw new IllegalArgumentException(TOO_PRECISE);
        }
        BigDecimal scaled = amount.setScale(SCALE, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException(NOT_POSITIVE);
        }
        if (scaled.precision() > PRECISION) {
            // rounding carried into a new integer digit, e.g. 99999999999999999.995
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        return scaled;
    }

    /**
     * Parses an amount literal without ever giving the parser an unreasonable input, then normalises it.
     *
     * @throws IllegalArgumentException with one of the safe messages above
     */
    public static BigDecimal parse(String literal) {
        if (literal == null || literal.isBlank()) {
            throw new IllegalArgumentException(REQUIRED);
        }
        String text = literal.trim();
        if (text.length() > MAX_LITERAL_LENGTH) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(NOT_A_NUMBER);
        }
        return normalize(parsed);
    }
}
