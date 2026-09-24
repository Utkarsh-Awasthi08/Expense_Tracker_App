package org.example.Api;

/** The phone already had OTP_RATE_LIMIT_PER_HOUR challenges created for it in the trailing hour. Answered with 429. */
public class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Too many OTP requests for this phone number; try again later");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
