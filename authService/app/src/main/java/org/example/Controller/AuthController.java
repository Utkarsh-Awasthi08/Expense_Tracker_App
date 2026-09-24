package org.example.Controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth utility endpoints.
 *
 * <p>Signup is no longer a separate step: the first successful OTP verify auto-provisions the
 * user's account (phone-number identity only). See {@link OtpController#verifyOtp}.
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    /** Plain-text user id of the caller (the subject of the access token). */
    @GetMapping("/auth/v1/ping")
    public ResponseEntity<String> ping(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(jwt.getSubject());
    }

    @GetMapping("/health")
    public ResponseEntity<Boolean> checkHealth() {
        return ResponseEntity.ok(true);
    }
}
