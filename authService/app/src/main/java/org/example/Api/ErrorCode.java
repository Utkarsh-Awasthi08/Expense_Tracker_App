package org.example.Api;

/** Error codes pinned in docs/CONTRACTS.md. */
public enum ErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    VALIDATION_FAILED,
    NOT_FOUND,
    CONFLICT,
    BAD_REQUEST,
    BATCH_TOO_LARGE,
    INTERNAL,
    /** OTP request rejected: the phone already hit OTP_RATE_LIMIT_PER_HOUR challenges in the trailing hour. */
    RATE_LIMITED,
    /** OTP request rejected: within OTP_RESEND_COOLDOWN_SECONDS of the phone's last challenge. */
    COOLDOWN_ACTIVE,
    /** OTP verify rejected: no active/expired challenge, wrong code, exhausted attempts, or a replay. */
    INVALID_OTP;

    /** The code for a status that has no more specific mapping. */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            default -> status >= 500 ? INTERNAL : BAD_REQUEST;
        };
    }
}
