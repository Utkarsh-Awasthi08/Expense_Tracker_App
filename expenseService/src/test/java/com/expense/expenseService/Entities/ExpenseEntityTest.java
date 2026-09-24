package com.expense.expenseService.Entities;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseEntityTest {

    @Test
    void setAmount_normalisesToScaleTwo() {
        Expense expense = new Expense();

        expense.setAmount(new BigDecimal("1234.5"));
        assertEquals(new BigDecimal("1234.50"), expense.getAmount());

        expense.setAmount(new BigDecimal("10.005"));
        assertEquals(new BigDecimal("10.01"), expense.getAmount(), "HALF_UP rounding");

        expense.setAmount(new BigDecimal("7"));
        assertEquals(new BigDecimal("7.00"), expense.getAmount());
    }

    @Test
    void setAmount_rejectsNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new Expense().setAmount(null));
        assertEquals("Expense amount is required", ex.getMessage());
    }

    @Test
    void setAmount_rejectsZeroNegativeAndValuesThatRoundToZero() {
        Expense expense = new Expense();
        for (String bad : new String[]{"0", "0.00", "-1", "-0.01", "0.004"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> expense.setAmount(new BigDecimal(bad)), bad);
            assertEquals("Expense amount must be greater than zero", ex.getMessage());
            assertFalse(ex.getMessage().contains(bad), "message must not echo the amount");
        }
        assertNull(expense.getAmount(), "a rejected amount must not be stored");
    }

    @Test
    void setAmount_rejectsValuesBeyondDecimal19_2() {
        // 17 integer digits fit DECIMAL(19,2), 18 do not
        new Expense().setAmount(new BigDecimal("99999999999999999.99"));
        assertThrows(IllegalArgumentException.class,
                () -> new Expense().setAmount(new BigDecimal("100000000000000000.00")));
    }

    @Test
    void setAmount_rejectsAbsurdExponentsQuicklyInsteadOfExpandingThem() {
        // Regression for the Stage 1 review finding: rounding ran before the range check, so a literal like
        // 1e999999999 made setScale build a number with a billion digits. It must now fail in constant time.
        for (String literal : new String[]{"1e999999999", "1E+50000000", "1e-999999999", "1E-50000000"}) {
            Expense expense = new Expense();
            BigDecimal absurd = new BigDecimal(literal);
            IllegalArgumentException ex = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(IllegalArgumentException.class, () -> expense.setAmount(absurd)), literal);
            assertFalse(ex.getMessage().contains("999999999"), "message must not echo the amount");
            assertNull(expense.getAmount());
        }
    }

    @Test
    void setAmount_rejectsRoundingThatCarriesIntoAnEighteenthIntegerDigit() {
        Expense expense = new Expense();
        assertThrows(IllegalArgumentException.class,
                () -> expense.setAmount(new BigDecimal("99999999999999999.995")));
        assertNull(expense.getAmount());
    }

    @Test
    void newEntityCarriesTheDatabaseDefaults() {
        Expense expense = new Expense();

        assertEquals("INR", expense.getCurrency());
        assertEquals("OTHER", expense.getCategory());
        assertEquals("DEBIT", expense.getTxnType());
    }
}
