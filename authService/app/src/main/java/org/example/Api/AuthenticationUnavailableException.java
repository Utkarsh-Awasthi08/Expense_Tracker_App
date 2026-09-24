package org.example.Api;

/**
 * A request could not be decided because something behind it failed (the database is down, a transaction could not
 * be started, ...), as opposed to the credentials/code/token being wrong. Answered with 503; the cause is logged
 * where it is caught, never sent to the client. Used by OTP request/verify and by refresh/logout alike.
 */
public class AuthenticationUnavailableException extends RuntimeException {
    public AuthenticationUnavailableException(Throwable cause) {
        super("Service temporarily unavailable", cause);
    }
}
