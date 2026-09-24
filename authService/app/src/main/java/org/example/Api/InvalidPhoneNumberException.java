package org.example.Api;

/** {@code phone_number} did not normalise cleanly (see {@code InputNormalizer.otpPhoneNumber}). Answered with 400. */
public class InvalidPhoneNumberException extends RuntimeException {
    public InvalidPhoneNumberException() {
        super("phone_number must be 7-15 digits, optionally starting with '+', after removing spaces, hyphens, "
                + "dots and parentheses");
    }
}
