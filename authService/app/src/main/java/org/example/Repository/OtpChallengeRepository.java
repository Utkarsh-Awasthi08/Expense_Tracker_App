package org.example.Repository;

import org.example.Entities.OtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long>
{
    /** Most recent challenge for the phone, whatever its state; used for the resend cooldown. */
    Optional<OtpChallenge> findFirstByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    /** The active challenge (if any) that a verify call would be checked against. */
    Optional<OtpChallenge> findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(String phoneNumber);

    /** Rows created for the phone in the trailing window; used for the hourly rate limit. */
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, Instant since);

    @Query("select min(o.createdAt) from OtpChallenge o where o.phoneNumber = :phoneNumber and o.createdAt > :since")
    Optional<Instant> findOldestCreatedAtSince(@Param("phoneNumber") String phoneNumber, @Param("since") Instant since);

    /** Invalidates any still-active challenge for the phone so that only the newest code is ever valid. */
    @Modifying
    @Query("update OtpChallenge o set o.consumedAt = :now where o.phoneNumber = :phoneNumber and o.consumedAt is "
            + "null and o.expiresAt > :now")
    int consumeActiveForPhone(@Param("phoneNumber") String phoneNumber, @Param("now") Instant now);

    /**
     * Atomic per-guess decrement: returns 1 only if THIS call is the one that consumed an attempt (rows-affected,
     * never read-then-write), so two concurrent wrong guesses cannot both spend the last attempt.
     */
    @Modifying
    @Query("update OtpChallenge o set o.attemptsRemaining = o.attemptsRemaining - 1 where o.id = :id and "
            + "o.attemptsRemaining > 0 and o.consumedAt is null")
    int decrementAttempts(@Param("id") Long id);

    /** Atomic consume guard: returns 1 only if THIS call is the one that consumed the challenge. */
    @Modifying
    @Query("update OtpChallenge o set o.consumedAt = :now where o.id = :id and o.consumedAt is null")
    int consumeById(@Param("id") Long id, @Param("now") Instant now);
}
