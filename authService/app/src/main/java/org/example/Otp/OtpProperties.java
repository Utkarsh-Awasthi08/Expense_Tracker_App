package org.example.Otp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for OTP generation and rate limiting.
 *
 * <ul>
 *   <li>{@code OTP_TTL_SECONDS}             – how long a code is valid (default 300 = 5 min)</li>
 *   <li>{@code OTP_RESEND_COOLDOWN_SECONDS} – minimum gap between two OTP requests for the same phone (default 60)</li>
 *   <li>{@code OTP_RATE_LIMIT_PER_HOUR}     – max OTP requests per phone per trailing hour (default 5)</li>
 *   <li>{@code OTP_MAX_ATTEMPTS}            – wrong-code attempts before the challenge is exhausted (default 5)</li>
 *   <li>{@code OTP_DEFAULT_COUNTRY_CODE}    – prepended when number has no leading '+' (default 91 = India)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "otp")
public record OtpProperties(
        long ttlSeconds,
        long resendCooldownSeconds,
        int rateLimitPerHour,
        int maxAttempts,
        String defaultCountryCode) {

    /** Fills in sensible defaults when properties are not set. */
    public OtpProperties {
        if (ttlSeconds <= 0) ttlSeconds = 300;
        if (resendCooldownSeconds <= 0) resendCooldownSeconds = 60;
        if (rateLimitPerHour <= 0) rateLimitPerHour = 5;
        if (maxAttempts <= 0) maxAttempts = 5;
        if (defaultCountryCode == null || defaultCountryCode.isBlank()) defaultCountryCode = "91";
    }
}
