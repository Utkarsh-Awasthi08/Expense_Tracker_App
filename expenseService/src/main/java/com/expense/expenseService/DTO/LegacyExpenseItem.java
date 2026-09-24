package com.expense.expenseService.DTO;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Row shape of the legacy alias {@code GET /expense/v1/getExpense}, kept so the untouched mobile list keeps working:
 * it calls {@code amount.toFixed(2)} (so amount must be a JSON number) and reads {@code created_at}, which is
 * {@code coalesce(sms_received_at, created_at)}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LegacyExpenseItem(BigDecimal amount, String merchant, String currency, Instant createdAt) {

    @Override
    public String toString() {
        return "LegacyExpenseItem[redacted]";
    }
}
