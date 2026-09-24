package org.example.Auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT settings bound from {@code jwt.*}. {@code privateKeyPath} may be blank here; the key loader fails
 * fast with a clear message in that case.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank String issuer,
        String privateKeyPath,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl) {
}
