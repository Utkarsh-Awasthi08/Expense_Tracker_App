package com.expense.expenseService.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire form of an expense (snake_case JSON). There is intentionally no constructor taking a JSON
 * string: Jackson would auto-detect a single-String constructor as a delegating creator.
 * <p>
 * No {@code @ToString}/{@code @Data}: instances hold amounts and merchants and must not end up in logs by accident.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@EqualsAndHashCode
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpenseDTO {

    private String externalId;

    private String userId;

    private String smsHash;

    private Instant smsReceivedAt;

    /** Serialized as a JSON number, e.g. {@code 1234.50}. */
    private BigDecimal amount;

    private String currency;

    private String merchant;

    private String category;

    private String txnType;

    private String accountLast4;

    private LocalDate txnDate;

    private Instant createdAt;
}
