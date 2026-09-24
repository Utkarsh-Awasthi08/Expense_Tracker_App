package org.example.Otp;

import lombok.RequiredArgsConstructor;
import org.example.Api.CooldownActiveException;
import org.example.Api.InvalidOtpException;
import org.example.Api.InvalidPhoneNumberException;
import org.example.Api.RateLimitedException;
import org.example.Entities.OtpChallenge;
import org.example.Entities.UserInfo;
import org.example.Entities.UserRole;
import org.example.Repository.OtpChallengeRepository;
import org.example.Repository.RoleRepository;
import org.example.Repository.UserRepository;
import org.example.Request.InputNormalizer;
import org.example.Response.JwtResponseDTO;
import org.example.Service.JwtService;
import org.example.Service.RefreshTokenService;
import org.example.Sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Core OTP logic: rate-limit, generate, send and verify one-time codes.
 *
 * <p>The flow is:
 * <ol>
 *   <li>{@link #requestOtp(String)} – normalise phone, rate-limit, generate a 6-digit code,
 *       store its SHA-256 hash, and hand the plaintext code to the {@link SmsProvider}.</li>
 *   <li>{@link #verifyOtp(String, String)} – normalise phone, look up the active challenge,
 *       decrement attempts atomically, compare hashes, consume the challenge and issue tokens.</li>
 * </ol>
 *
 * <p>A user who does not yet have an account is auto-provisioned on first successful verify
 * (phone-number-only account with {@code ROLE_USER}).
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final OtpChallengeRepository otpChallengeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SmsProvider smsProvider;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final OtpProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Sends a new OTP code to the phone number.
     *
     * @throws InvalidPhoneNumberException if the number cannot be normalised
     * @throws RateLimitedException        if the phone has already hit the hourly cap
     * @throws CooldownActiveException     if a code was sent too recently
     */
    @Transactional
    public void requestOtp(String rawPhone) {
        String phone = normalizeOrThrow(rawPhone);
        Instant now = now();

        // Hourly rate limit
        Instant oneHourAgo = now.minusSeconds(3600);
        long count = otpChallengeRepository.countByPhoneNumberAndCreatedAtAfter(phone, oneHourAgo);
        if (count >= properties.rateLimitPerHour()) {
            // find when the oldest challenge in the window expires so we can give a Retry-After
            Instant oldest = otpChallengeRepository.findOldestCreatedAtSince(phone, oneHourAgo)
                    .orElse(now);
            long retryAfter = oldest.plusSeconds(3600).getEpochSecond() - now.getEpochSecond();
            throw new RateLimitedException(Math.max(1, retryAfter));
        }

        // Resend cooldown
        otpChallengeRepository.findFirstByPhoneNumberOrderByCreatedAtDesc(phone).ifPresent(last -> {
            long elapsed = now.getEpochSecond() - last.getCreatedAt().getEpochSecond();
            if (elapsed < properties.resendCooldownSeconds()) {
                throw new CooldownActiveException(properties.resendCooldownSeconds() - elapsed);
            }
        });

        // Invalidate any previously active challenge for this phone so only the newest code is valid
        otpChallengeRepository.consumeActiveForPhone(phone, now);

        // Generate and store
        String code = generateCode();
        String hash = sha256Hex(code + ":" + phone);
        Instant expiresAt = now.plusSeconds(properties.ttlSeconds()).truncatedTo(ChronoUnit.MICROS);
        OtpChallenge challenge = new OtpChallenge(phone, hash, expiresAt, properties.maxAttempts(),
                now.truncatedTo(ChronoUnit.MICROS));
        otpChallengeRepository.save(challenge);

        smsProvider.sendOtp(phone, code);
        log.info("OTP challenge created for phone ending in ...{}", phone.length() > 4 ? phone.substring(phone.length() - 4) : "****");
    }

    /**
     * Verifies the OTP code. On success, auto-provisions the user if they do not yet exist, then
     * returns a JWT access token and refresh token.
     *
     * @throws InvalidPhoneNumberException if the number cannot be normalised
     * @throws InvalidOtpException         for any failure (wrong code, expired, exhausted, replayed)
     */
    @Transactional
    public JwtResponseDTO verifyOtp(String rawPhone, String code) {
        String phone = normalizeOrThrow(rawPhone);
        Instant now = now();

        OtpChallenge challenge = otpChallengeRepository
                .findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phone)
                .orElseThrow(InvalidOtpException::new);

        // Expired?
        if (!challenge.getExpiresAt().isAfter(now)) {
            throw new InvalidOtpException();
        }

        // Decrement attempts atomically; if 0 rows affected the challenge is already exhausted
        int decremented = otpChallengeRepository.decrementAttempts(challenge.getId());
        if (decremented == 0) {
            throw new InvalidOtpException();
        }

        // Compare hashes
        String expected = sha256Hex(code + ":" + phone);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidOtpException();
        }

        // Consume atomically (guard against replays)
        int consumed = otpChallengeRepository.consumeById(challenge.getId(), now.truncatedTo(ChronoUnit.MICROS));
        if (consumed == 0) {
            throw new InvalidOtpException();
        }

        // Provision user if needed
        UserInfo user = userRepository.findByPhoneNumber(phone)
                .orElseGet(() -> provisionUser(phone, now));

        List<String> roles = user.getRoles().stream()
                .map(UserRole::getRoleName)
                .sorted(Comparator.naturalOrder())
                .toList();

        String accessToken = jwtService.issueAccessToken(user.getUserId(), user.getPhoneNumber(), roles);
        String refreshToken = refreshTokenService.issue(user.getUserId());
        return JwtResponseDTO.bearer(accessToken, refreshToken, jwtService.accessTtlSeconds());
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private UserInfo provisionUser(String phone, Instant now) {
        UserRole role = roleRepository.findByRoleName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException(DEFAULT_ROLE + " is not seeded; run the Flyway migrations"));
        UserInfo user = new UserInfo(UUID.randomUUID().toString(), phone, now.truncatedTo(ChronoUnit.MICROS));
        user.getRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String normalizeOrThrow(String raw) {
        String phone = InputNormalizer.otpPhoneNumber(raw, properties.defaultCountryCode());
        if (phone == null) {
            throw new InvalidPhoneNumberException();
        }
        return phone;
    }

    private String generateCode() {
        // 6-digit code: 000000–999999, zero-padded
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private Instant now() {
        return Instant.now(clock);
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
