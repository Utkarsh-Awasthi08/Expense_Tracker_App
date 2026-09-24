package org.example.Entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One OTP code sent to a phone number. Only {@code codeHash} (SHA-256 of {@code code + ":" + normalizedPhone}) is
 * stored, never the plaintext code. A challenge is "active" while {@code consumedAt} is null and {@code expiresAt}
 * is in the future; {@code attemptsRemaining} is only ever changed by an atomic conditional UPDATE (see
 * {@link org.example.Repository.OtpChallengeRepository}), never read-then-written from Java.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "otp_challenges")
public class OtpChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "phone_number", length = 20, nullable = false)
    private String phoneNumber;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts_remaining", nullable = false)
    private int attemptsRemaining;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public OtpChallenge(String phoneNumber, String codeHash, Instant expiresAt, int attemptsRemaining,
                        Instant createdAt) {
        this.phoneNumber = phoneNumber;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attemptsRemaining = attemptsRemaining;
        this.createdAt = createdAt;
    }
}
