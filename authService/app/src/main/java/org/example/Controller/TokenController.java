package org.example.Controller;

import lombok.RequiredArgsConstructor;
import org.example.Request.RefreshTokenRequestDTO;
import org.example.Response.JwtResponseDTO;
import org.example.Service.AuthTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token lifecycle endpoints (refresh and logout). Login is handled by
 * {@link OtpController#verifyOtp} which issues tokens after OTP verification.
 */
@RestController
@RequiredArgsConstructor
public class TokenController {

    private final AuthTokenService authTokenService;

    /** Rotates the refresh token: the presented token is revoked and a new one is returned. Anything invalid is 401. */
    @PostMapping("/auth/v1/refreshToken")
    public JwtResponseDTO refreshToken(@RequestBody RefreshTokenRequestDTO request) {
        return authTokenService.refresh(request.token());
    }

    /** Always 204 (idempotent): the response never reveals whether the token was valid. */
    @PostMapping("/auth/v1/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshTokenRequestDTO request) {
        authTokenService.logout(request.token());
        return ResponseEntity.noContent().build();
    }
}
