package org.example.Api;

/** The refresh token is unknown, expired, revoked or already rotated. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
