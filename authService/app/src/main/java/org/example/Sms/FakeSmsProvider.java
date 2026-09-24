package org.example.Sms;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.example.Otp.OtpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only {@link SmsProvider} implementation today: logs the code instead of sending a real SMS. Never wire this
 * in production; {@link SmsConfig} fails application startup if {@code SMS_PROVIDER} is anything but {@code fake}.
 *
 * <p>This is the ONE place in the service allowed to log the plaintext OTP code.
 */
@RequiredArgsConstructor
public class FakeSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeSmsProvider.class);

    private final OtpProperties properties;

    @PostConstruct
    void logActivation() {
        log.warn("FAKE SMS PROVIDER ACTIVE - no real SMS is sent, do not use in production");
    }

    @Override
    public void sendOtp(String normalizedPhone, String code) {
        log.warn("[FAKE SMS] OTP for {} is {} (expires in {}s)", normalizedPhone, code, properties.ttlSeconds());
    }
}
