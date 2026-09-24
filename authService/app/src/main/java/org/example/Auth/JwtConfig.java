package org.example.Auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Fails application startup if the key is missing, unreadable or not RSA. */
    @Bean
    public JwtKeyProvider jwtKeyProvider(JwtProperties properties) {
        return JwtKeyProvider.load(properties.privateKeyPath());
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtKeyProvider keys, JwtProperties properties) {
        return buildDecoder(keys.publicKey(), properties.issuer());
    }

    /**
     * RS256 only: a token with any other algorithm (none, HS256 keyed with the public key, ...) is rejected.
     * Validates exp/nbf and the issuer.
     */
    public static NimbusJwtDecoder buildDecoder(RSAPublicKey publicKey, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
