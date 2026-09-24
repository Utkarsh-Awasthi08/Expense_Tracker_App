package org.example.Service;

import lombok.RequiredArgsConstructor;
import org.example.Entities.UserInfo;
import org.example.Entities.UserRole;
import org.example.Response.JwtResponseDTO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Refresh and logout: glues refresh-token rotation and JWT issuing together.
 *
 * <p>The password-based {@code login()} method has been removed; authentication is now done via
 * OTP (see {@link org.example.Otp.OtpService#verifyOtp}). This class only provides token rotation
 * (refresh) and revocation (logout).
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    public JwtResponseDTO refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);
        UserInfo user = rotation.user();
        List<String> roles = user.getRoles().stream()
                .map(UserRole::getRoleName)
                .sorted(Comparator.naturalOrder())
                .toList();
        String accessToken = jwtService.issueAccessToken(user.getUserId(), user.getPhoneNumber(), roles);
        return JwtResponseDTO.bearer(accessToken, rotation.refreshToken(), jwtService.accessTtlSeconds());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }
}
