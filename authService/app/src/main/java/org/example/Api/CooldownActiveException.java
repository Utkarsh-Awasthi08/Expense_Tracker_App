package org.example.Api;

/** A new OTP was requested within OTP_RESEND_COOLDOWN_SECONDS of the phone's last challenge. Answered with 429. */
public class CooldownActiveException extends RuntimeException {
    private final long retryAfterSeconds;

    public CooldownActiveException(long retryAfterSeconds) {
        super("An OTP was requested too recently for this phone number; try again later");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
