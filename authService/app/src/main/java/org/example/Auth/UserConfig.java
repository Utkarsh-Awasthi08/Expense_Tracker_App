package org.example.Auth;

import org.example.Otp.OtpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link OtpProperties} configuration. PasswordEncoder has been removed: authentication is
 * now phone + OTP only, so there are no passwords to hash. The Clock bean lives in
 * {@link JwtConfig}.
 */
@Configuration
@EnableConfigurationProperties(OtpProperties.class)
public class UserConfig {
}
