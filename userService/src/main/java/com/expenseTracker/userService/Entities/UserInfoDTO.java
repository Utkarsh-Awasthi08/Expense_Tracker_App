package com.expenseTracker.userService.Entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Profile as returned by {@code /user/v1/me} and, for now, the payload of the legacy Kafka consumer
 * (rewritten in Stage 4). Extra fields in an event (username, event_id, created_at, ...) are ignored.
 * phone_number is a String; the legacy numeric JSON form is coerced by Jackson.
 * toString shows only the user id so names, phone and email never reach a log line.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserInfoDTO {

    @ToString.Include
    private String userId;

    private String firstName;

    private String lastName;

    private String phoneNumber;

    private String email;

    private String profilePicture;

    private String defaultCurrency;

    private String timezone;

    private java.math.BigDecimal monthlyBudget;

    public UserInfo transformToUserInfo() {
        UserInfo userInfo = UserInfo.builder()
                .userId(this.userId)
                .firstName(this.firstName)
                .lastName(this.lastName)
                .phoneNumber(this.phoneNumber)
                .email(this.email)
                .profilePicture(this.profilePicture)
                .monthlyBudget(this.monthlyBudget)
                .build();
        // The columns are NOT NULL with defaults: only override the defaults when the event carries a value.
        if (this.defaultCurrency != null) {
            userInfo.setDefaultCurrency(this.defaultCurrency);
        }
        if (this.timezone != null) {
            userInfo.setTimezone(this.timezone);
        }
        return userInfo;
    }

    public static UserInfoDTO from(UserInfo userInfo) {
        return new UserInfoDTO(
                userInfo.getUserId(),
                userInfo.getFirstName(),
                userInfo.getLastName(),
                userInfo.getPhoneNumber(),
                userInfo.getEmail(),
                userInfo.getProfilePicture(),
                userInfo.getDefaultCurrency(),
                userInfo.getTimezone(),
                userInfo.getMonthlyBudget());
    }
}
