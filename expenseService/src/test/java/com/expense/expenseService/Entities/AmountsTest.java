package com.expense.expenseService.Entities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AmountsTest {

    @ParameterizedTest
    @CsvSource({
            "1234.5,        1234.50",
            "7,             7.00",
            "10.005,        10.01",
            "10.004,        10.00",
            "0.005,         0.01",
            "1e2,           100.00",
            "1E+2,          100.00",
            "12.3456789,    12.35",
            "99999999999999999.99, 99999999999999999.99",
            "99999999999999999.994, 99999999999999999.99"
    })
    void normalisesToScaleTwoHalfUp(String in, String expected) {
        assertEquals(new BigDecimal(expected), Amounts.parse(in));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "0.004", "0.0049999", "0E+1000"})
    void refusesZeroNegativeAndValuesThatRoundToZero(String in) {
        assertEquals(Amounts.NOT_POSITIVE, assertThrows(IllegalArgumentException.class, () -> Amounts.parse(in)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "100000000000000000",            // 18 integer digits
            "100000000000000000.00",
            "99999999999999999.995",         // rounds up into an 18th integer digit
            "1e17",
            "1e999999999",
            "1E+50000000",
            "9.9e2147483647"
    })
    void refusesMoreThanSeventeenIntegerDigits(String in) {
        assertEquals(Amounts.OUT_OF_RANGE, assertThrows(IllegalArgumentException.class, () -> Amounts.parse(in)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e-999999999", "1E-50000000", "0.00000000000000000000000000000000000000000000000000000000000000001"})
    void refusesAbsurdScales(String in) {
        assertEquals(Amounts.TOO_PRECISE, assertThrows(IllegalArgumentException.class, () -> Amounts.parse(in)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "NaN", "Infinity", "1,000.00", "1e", "--1", "0x10", "1e99999999999"})
    void refusesNonNumbers(String in) {
        assertEquals(Amounts.NOT_A_NUMBER, assertThrows(IllegalArgumentException.class, () -> Amounts.parse(in)).getMessage());
    }

    @Test
    void refusesBlankAndOverlongLiterals() {
        assertEquals(Amounts.REQUIRED, assertThrows(IllegalArgumentException.class, () -> Amounts.parse("  ")).getMessage());
        assertEquals(Amounts.REQUIRED, assertThrows(IllegalArgumentException.class, () -> Amounts.parse(null)).getMessage());
        assertEquals(Amounts.OUT_OF_RANGE,
                assertThrows(IllegalArgumentException.class, () -> Amounts.parse("1".repeat(Amounts.MAX_LITERAL_LENGTH + 1))).getMessage());
    }

    @Test
    void normalizeRefusesNull() {
        assertEquals(Amounts.REQUIRED, assertThrows(IllegalArgumentException.class, () -> Amounts.normalize(null)).getMessage());
    }

    @Test
    void normalizeIsIdempotent() {
        BigDecimal once = Amounts.normalize(new BigDecimal("12.345"));
        assertEquals(once, Amounts.normalize(once));
    }

    @Test
    void absurdExponentsAreRefusedInConstantTimeNotExpanded() {
        // Rounding 1e999999999 would materialise a ~1-gigadigit number. The guard must fire before setScale.
        for (String literal : new String[]{"1e999999999", "1E+50000000", "1e-999999999", "1E-50000000", "-1e999999999"}) {
            BigDecimal absurd = new BigDecimal(literal);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    assertThrows(IllegalArgumentException.class, () -> Amounts.normalize(absurd)), literal);
        }
    }

    @Test
    void messagesNeverEchoTheValue() {
        for (String literal : new String[]{"-123.45", "1e999999999", "1e-999999999", "0.001", "not-a-number-777"}) {
            String message = assertThrows(IllegalArgumentException.class, () -> Amounts.parse(literal)).getMessage();
            assertFalse(message.contains("123") || message.contains("999") || message.contains("777"), message);
        }
    }
}
