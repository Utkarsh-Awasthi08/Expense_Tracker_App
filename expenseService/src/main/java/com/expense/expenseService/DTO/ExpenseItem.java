package com.expense.expenseService.DTO;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One expense as the API returns it. There is intentionally no user_id (the caller is the owner, always) and no
 * sms_hash (an internal dedup key). {@code amount} is a JSON number.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExpenseItem(
        String externalId,
        BigDecimal amount,
        String currency,
        String merchant,
        String category,
        String txnType,
        String accountLast4,
        LocalDate txnDate,
        Instant createdAt) {

    @Override
    public String toString() {
        return "ExpenseItem[externalId=" + externalId + "]";
    }
}
