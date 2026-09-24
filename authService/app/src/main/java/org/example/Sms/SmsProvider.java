package org.example.Sms;

/** Sends an OTP code to a phone. The only implementation today is {@link FakeSmsProvider}; see {@link SmsConfig}. */
public interface SmsProvider {

    /**
     * @param normalizedPhone the canonical E.164-ish form (see {@code InputNormalizer.otpPhoneNumber})
     * @param code            the plaintext 6-digit code; never persist or log this outside the provider itself
     */
    void sendOtp(String normalizedPhone, String code);
}
