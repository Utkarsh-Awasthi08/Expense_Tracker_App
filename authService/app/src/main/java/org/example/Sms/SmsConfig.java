package org.example.Sms;

import org.example.Otp.OtpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link SmsProvider} implementation.
 *
 * <p>Only {@code fake} is supported today. Setting {@code SMS_PROVIDER} to anything else will cause
 * application startup to fail fast with a clear error (rather than silently using the fake provider
 * in a production environment).
 */
@Configuration
public class SmsConfig {

    @Bean
    public SmsProvider smsProvider(OtpProperties properties) {
        String provider = System.getenv("SMS_PROVIDER");
        if (provider == null || provider.isBlank()) {
            provider = "fake";
        }
        return switch (provider.toLowerCase()) {
            case "fake" -> new FakeSmsProvider(properties);
            default -> throw new IllegalStateException(
                    "Unknown SMS_PROVIDER=\"" + provider + "\"; only \"fake\" is supported. "
                            + "Refusing to start to prevent a misconfigured production deployment.");
        };
    }
}
