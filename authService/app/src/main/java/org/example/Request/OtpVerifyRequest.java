package org.example.Request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /auth/v1/otp/verify} (snake_case on the wire). The phone number is normalised in OtpService. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpVerifyRequest(
        @NotBlank String phoneNumber,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String code) {

    /** Never print the code. */
    @Override
    public String toString() {
        return "OtpVerifyRequest[phoneNumber=" + phoneNumber + "]";
    }
}
