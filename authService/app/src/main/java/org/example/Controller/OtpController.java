package org.example.Controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.Otp.OtpService;
import org.example.Request.OtpRequestRequest;
import org.example.Request.OtpVerifyRequest;
import org.example.Response.JwtResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTP authentication endpoints.
 *
 * <ul>
 *   <li>{@code POST /auth/v1/otp/request} – sends a 6-digit OTP to the given phone number</li>
 *   <li>{@code POST /auth/v1/otp/verify}  – verifies the code and returns access + refresh tokens</li>
 * </ul>
 *
 * Both endpoints are public (no bearer token required); they are listed in
 * {@link org.example.Auth.SecurityConfig#PUBLIC_ENDPOINTS}.
 */
@RestController
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;

    /**
     * Sends an OTP to the phone number. Returns 204 on success so the response body never reveals
     * whether the phone is already registered.
     */
    @PostMapping("/auth/v1/otp/request")
    public ResponseEntity<Void> requestOtp(@Valid @RequestBody OtpRequestRequest request) {
        otpService.requestOtp(request.phoneNumber());
        return ResponseEntity.noContent().build();
    }

    /**
     * Verifies the OTP code. On success auto-provisions the user if they don't exist and returns
     * an access token + refresh token pair.
     */
    @PostMapping("/auth/v1/otp/verify")
    public JwtResponseDTO verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return otpService.verifyOtp(request.phoneNumber(), request.code());
    }
}
