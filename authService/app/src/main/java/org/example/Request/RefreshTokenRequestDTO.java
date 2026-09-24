package org.example.Request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of refreshToken and logout. Not validated with bean validation: an unusable token is a 401 / a no-op. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshTokenRequestDTO(String token) {

    /** Never print the token. */
    @Override
    public String toString() {
        return "RefreshTokenRequestDTO[token=<redacted>]";
    }
}
