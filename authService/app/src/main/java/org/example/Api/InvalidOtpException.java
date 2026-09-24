package org.example.Api;

/**
 * No active/expired challenge for the phone, a wrong code, exhausted attempts, or a replay of an already-consumed
 * code: deliberately identical in every case (same status, code and message) so the response is never an oracle for
 * whether the phone is registered, how many attempts remain, or which of these actually happened.
 */
public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException() {
        super("Invalid or expired code");
    }
}
