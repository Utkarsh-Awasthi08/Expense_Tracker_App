package com.expense.expenseService.Entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Persistent form of an expense. Maps 1:1 to the {@code expense} table created by
 * {@code db/migration/V1__create_expense.sql}; Hibernate only validates that schema.
 * <p>
 * Enum-like values (category, txn_type) are stored as plain strings and validated by the
 * application layer, and UUIDs are stored as strings, so that schema validation stays trivial on MySQL.
 */
@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor
public class Expense {

    public static final int AMOUNT_SCALE = Amounts.SCALE;
    private static final int AMOUNT_PRECISION = Amounts.PRECISION;

    public static final String DEFAULT_CURRENCY = "INR";
    public static final String DEFAULT_CATEGORY = "OTHER";
    public static final String DEFAULT_TXN_TYPE = "DEBIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "external_id", nullable = false, updatable = false, unique = true, length = 36)
    private String externalId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private String userId;

    @Column(name = "sms_hash", length = 64)
    private String smsHash;

    @Column(name = "sms_received_at")
    private Instant smsReceivedAt;

    @Column(name = "amount", nullable = false, precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "merchant", length = 255)
    private String merchant;

    @Column(name = "category", nullable = false, length = 32)
    private String category = DEFAULT_CATEGORY;

    @Column(name = "txn_type", nullable = false, length = 16)
    private String txnType = DEFAULT_TXN_TYPE;

    @Column(name = "account_last4", length = 4)
    private String accountLast4;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Normalises the amount to scale 2 (HALF_UP) and rejects anything that is not strictly positive
     * or does not fit DECIMAL(19,2). The range is checked BEFORE rounding (see {@link Amounts}) so an absurd
     * exponent such as {@code 1e999999999} is refused in constant time instead of being expanded.
     * The message deliberately never contains the offending value.
     */
    public void setAmount(BigDecimal amount) {
        this.amount = Amounts.normalize(amount);
    }

    @PrePersist
    private void onCreate() {
        if (this.externalId == null || this.externalId.isBlank()) {
            this.externalId = UUID.randomUUID().toString();
        }
        Instant now = now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = now();
    }

    // DATETIME(6) keeps microseconds; truncating here means what we hold equals what we read back.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
