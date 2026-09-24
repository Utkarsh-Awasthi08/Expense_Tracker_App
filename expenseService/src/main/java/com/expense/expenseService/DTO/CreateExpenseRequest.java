package com.expense.expenseService.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of {@code POST /expense/v1/expenses}. Deliberately has NO user_id, external_id, created_at or sms_hash:
 * anything of that kind in the JSON is ignored (mass-assignment), and the owner always comes from X-User-Id.
 * <p>
 * Only text and dates are held as given; the service validates and defaults them.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateExpenseRequest(
        @JsonDeserialize(using = AmountDeserializer.class) BigDecimal amount,
        String currency,
        String merchant,
        String category,
        String txnType,
        LocalDate txnDate) {

    // Never print the amount or merchant by accident.
    @Override
    public String toString() {
        return "CreateExpenseRequest[redacted]";
    }
}
