package com.expense.expenseService.Entities;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The pinned transaction types (docs/CONTRACTS.md). The column stays a plain string. */
public enum TxnType {
    DEBIT, CREDIT, OTHER;

    public static boolean isValid(String value) {
        return value != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(value));
    }

    public static String allowedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
