package org.example.Repository;

import jakarta.persistence.LockModeType;
import org.example.Entities.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long>
{
    /** Row-locks the token so that two concurrent refreshes of the same token cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Idempotent revoke; returns 0 when the token is unknown or already revoked. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.tokenHash = :tokenHash and t.revokedAt is null")
    int revoke(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
