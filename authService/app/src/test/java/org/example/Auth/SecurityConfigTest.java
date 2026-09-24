package org.example.Auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void rolesClaimBecomesAuthoritiesVerbatimWithoutAPrefix() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("u1")
                .claim("roles", List.of("ROLE_USER", "ROLE_ADMIN"))
                .claim("scope", "should-not-be-used")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(authentication.getName()).isEqualTo("u1");
    }

    @Test
    void tokenWithoutRolesHasNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(Map.of("sub", "u1")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }
}
