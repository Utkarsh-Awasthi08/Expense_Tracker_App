package org.example.Request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/v1/otp/request} (snake_case on the wire). Only presence is validated here; the actual
 * normalisation (separators stripped, default country code applied) happens in {@code OtpService}, because the
 * default country code is environment-configurable and so cannot be baked into a compact record constructor.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpRequestRequest(@NotBlank String phoneNumber) {
}
