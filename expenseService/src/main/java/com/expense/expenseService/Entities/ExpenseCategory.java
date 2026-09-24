package com.expense.expenseService.Entities;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The pinned category vocabulary (docs/CONTRACTS.md). The column stays a plain string; this enum is what the
 * application validates against, exactly as written (upper case).
 */
public enum ExpenseCategory {
    FOOD, GROCERIES, TRANSPORT, SHOPPING, BILLS_UTILITIES, ENTERTAINMENT, HEALTH, TRAVEL,
    EDUCATION, RENT, EMI_LOANS, INVESTMENT, TRANSFER, CASH_WITHDRAWAL, OTHER;

    public static boolean isValid(String value) {
        return value != null && Arrays.stream(values()).anyMatch(c -> c.name().equals(value));
    }

    public static String allowedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
