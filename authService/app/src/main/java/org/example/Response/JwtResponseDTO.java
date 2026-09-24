package org.example.Response;

/**
 * Login / refresh response. {@code accessToken} and {@code token} (the refresh token) keep their camelCase names;
 * {@code expiresIn} is the access token lifetime in seconds.
 */
public record JwtResponseDTO(String accessToken, String token, String tokenType, long expiresIn) {

    public static JwtResponseDTO bearer(String accessToken, String refreshToken, long expiresInSeconds) {
        return new JwtResponseDTO(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }

    /** Never print the tokens. */
    @Override
    public String toString() {
        return "JwtResponseDTO[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
