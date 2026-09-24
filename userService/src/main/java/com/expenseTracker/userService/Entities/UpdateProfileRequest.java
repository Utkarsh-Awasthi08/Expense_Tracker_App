package com.expenseTracker.userService.Entities;

import com.expenseTracker.userService.Validation.ValidCurrency;
import com.expenseTracker.userService.Validation.ValidText;
import com.expenseTracker.userService.Validation.ValidTimeZone;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PUT /user/v1/me}. Every field is optional: an absent (or null) field leaves the stored value
 * unchanged. Anything else in the body (user_id, id, created_at, ...) is ignored, because identity comes only
 * from the X-User-Id header. There is deliberately no toString so PII cannot be logged by accident.
 * The four free-text fields go through {@link ValidText}: blank means blank in the Unicode sense (NBSP, em space,
 * zero-width space...) and NUL or any other control character is rejected. phone_number, default_currency and
 * timezone are already restricted to a strict alphabet by their own constraints.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateProfileRequest {

    @Size(max = 100, message = "must be at most 100 characters")
    @ValidText
    private String firstName;

    @Size(max = 100, message = "must be at most 100 characters")
    @ValidText
    private String lastName;

    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be 7 to 15 digits with an optional leading +")
    private String phoneNumber;

    @Email(message = "must be a well-formed email address")
    @Size(max = 254, message = "must be at most 254 characters")
    @ValidText
    private String email;

    @Size(max = 512, message = "must be at most 512 characters")
    @ValidText
    private String profilePicture;

    @ValidCurrency
    private String defaultCurrency;

    @Size(max = 64, message = "must be at most 64 characters")
    @ValidTimeZone
    private String timezone;

    private java.math.BigDecimal monthlyBudget;
}
