package com.expenseTracker.userService.Entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Row of {@code user_info} (Flyway V1). Column names are explicit because Hibernate runs in
 * {@code ddl-auto=validate}; timestamps are UTC instants stored as DATETIME(6).
 * {@code @DynamicUpdate} makes an UPDATE write only the columns that actually changed, so two partial edits of
 * different fields never rewrite each other's columns with stale values (see also the row lock taken by
 * {@code UserRepository.findByUserIdForUpdate}).
 */
@Entity
@DynamicUpdate
@Table(name = "user_info")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserInfo {

    public static final String DEFAULT_CURRENCY = "INR";
    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The auth service's user UUID, as text. Identity is never taken from anything but the X-User-Id header. */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false, length = 36)
    private String userId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "profile_picture", length = 512)
    private String profilePicture;

    @Builder.Default
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = DEFAULT_CURRENCY;

    @Builder.Default
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "monthly_budget", precision = 19, scale = 2)
    private java.math.BigDecimal monthlyBudget;

    @Builder.Default
    @Column(name = "current_streak", nullable = false)
    private Integer currentStreak = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = now();
    }

    // DATETIME(6) keeps microseconds; truncating here avoids the server rounding nanoseconds.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
