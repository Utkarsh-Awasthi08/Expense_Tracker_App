package org.example.Service;

import lombok.RequiredArgsConstructor;
import org.example.Api.InvalidRefreshTokenException;
import org.example.Auth.JwtProperties;
import org.example.Entities.RefreshToken;
import org.example.Entities.UserInfo;
import org.example.Repository.RefreshTokenRepository;
import org.example.Repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque refresh tokens: 32 random bytes, base64url. Only the SHA-256 hex digest is stored, a user may hold many
 * tokens, and every refresh rotates (old token revoked, new one issued in the same transaction).
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    /** 32 bytes as unpadded base64url is 43 characters; anything much longer cannot be one of ours. */
    private static final int MAX_TOKEN_LENGTH = 128;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** The user together with the new (raw) refresh token that replaced the presented one. */
    public record Rotation(UserInfo user, String refreshToken) {
    }

    /** Creates a refresh token for the user and returns the raw value, which is not stored anywhere. */
    @Transactional
    public String issue(String userId) {
        return store(userId, now()).raw();
    }

    /**
     * Validates and rotates a refresh token atomically. The token row is locked, so of two concurrent refreshes with
     * the same token exactly one succeeds. The user (and their roles) is re-read from the database.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked or already rotated
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidRefreshTokenException();
        }
        Instant now = now();
        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(rawToken))
                .filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(InvalidRefreshTokenException::new);
        UserInfo user = userRepository.findById(current.getUserId()).orElseThrow(InvalidRefreshTokenException::new);

        Stored next = store(user.getUserId(), now);
        current.setRevokedAt(now);
        current.setReplacedBy(next.entity().getId());
        refreshTokenRepository.save(current);
        return new Rotation(user, next.raw());
    }

    /** Revokes the token if it exists and is still active. Idempotent; never reveals whether the token was valid. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        refreshTokenRepository.revoke(sha256Hex(rawToken), now());
    }

    private record Stored(RefreshToken entity, String raw) {
    }

    private Stored store(String userId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken entity = refreshTokenRepository.saveAndFlush(
                new RefreshToken(sha256Hex(raw), userId, now.plus(properties.refreshTtl()), now));
        return new Stored(entity, raw);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
