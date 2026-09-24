package org.example.Service;

import org.example.Auth.JwtKeyProvider;
import org.example.Auth.JwtProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Issues RS256 access tokens. Claims: iss, sub (user id), username, roles (verbatim role names), iat, exp, jti.
 * The header carries alg=RS256 and the kid (RFC 7638 thumbprint) that the JWKS endpoint publishes.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final String kid;
    private final String issuer;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtService(JwtKeyProvider keys, JwtProperties properties, Clock clock) {
        this.encoder = keys.newEncoder();
        this.kid = keys.kid();
        this.issuer = properties.issuer();
        this.accessTtl = properties.accessTtl();
        this.clock = clock;
    }

    public String issueAccessToken(String userId, String username, Collection<String> roles) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId)
                .claim("username", username)
                .claim("roles", List.copyOf(roles))
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
