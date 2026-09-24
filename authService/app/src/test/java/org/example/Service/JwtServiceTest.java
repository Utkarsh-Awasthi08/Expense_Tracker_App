package org.example.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.Auth.JwtConfig;
import org.example.Auth.JwtKeyProvider;
import org.example.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String USER_ID = "0b0e6f0e-6b8e-4c2f-8d0a-3f7d6f1d2a11";

    private KeyPair pair;
    private JwtKeyProvider keys;
    private JwtService service;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        pair = TestKeys.generateRsa();
        keys = TestKeys.provider(pair);
        service = new JwtService(keys, TestKeys.properties(Duration.ofMinutes(15)), Clock.systemUTC());
        decoder = JwtConfig.buildDecoder((RSAPublicKey) pair.getPublic(), TestKeys.ISSUER);
    }

    @Test
    void issuedTokenDecodesWithThePublicKeyAndCarriesAllClaims() {
        Instant before = Instant.now().minusSeconds(2);
        String token = service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER", "ROLE_ADMIN"));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestKeys.ISSUER);
        assertThat(jwt.getSubject()).isEqualTo(USER_ID);
        assertThat(jwt.getClaimAsString("username")).isEqualTo("bob");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER", "ROLE_ADMIN");
        assertThat(UUID.fromString(jwt.getId())).isNotNull();
        assertThat(jwt.getIssuedAt()).isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(service.accessTtlSeconds()).isEqualTo(900);
    }

    @Test
    void headerIsRs256WithTheThumbprintKid() throws Exception {
        SignedJWT parsed = SignedJWT.parse(service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER")));

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(keys.kid());
    }

    @Test
    void rolesAreAJsonStringArrayWithVerbatimNames() throws Exception {
        SignedJWT parsed = SignedJWT.parse(service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER")));

        Object roles = parsed.getJWTClaimsSet().getClaim("roles");

        assertThat(roles).isInstanceOf(List.class);
        assertThat(roles).isEqualTo(List.of("ROLE_USER"));
    }

    @Test
    void everyTokenGetsAFreshJti() {
        String first = decoder.decode(service.issueAccessToken(USER_ID, "bob", List.of())).getId();
        String second = decoder.decode(service.issueAccessToken(USER_ID, "bob", List.of())).getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtDecoder otherIssuer = JwtConfig.buildDecoder((RSAPublicKey) pair.getPublic(), "someone-else");
        String token = service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER"));

        assertThatThrownBy(() -> otherIssuer.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        // Issued 2 hours ago with a 15 minute lifetime: well past the decoder's 60 s clock skew.
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        JwtService oldService = new JwtService(keys, TestKeys.properties(Duration.ofMinutes(15)), past);
        String token = oldService.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER"));

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedByADifferentKeyIsRejected() {
        JwtKeyProvider otherKeys = TestKeys.provider(TestKeys.generateRsa());
        JwtService otherService = new JwtService(otherKeys, TestKeys.properties(Duration.ofMinutes(15)), Clock.systemUTC());
        String token = otherService.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER"));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String token = service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER"));
        String[] parts = token.split("\\.");
        Map<?, ?> claims = new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> forged = (Map<String, Object>) claims;
        forged.put("roles", List.of("ROLE_ADMIN"));
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new ObjectMapper().writeValueAsBytes(forged));

        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.issueAccessToken(USER_ID, "bob", List.of("ROLE_USER"));
        // Change a character well inside the signature: the last base64url character carries padding bits only,
        // so altering it may not change the decoded signature bytes at all.
        int index = token.lastIndexOf('.') + 10;
        char flipped = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + flipped + token.substring(index + 1);

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void algNoneTokenIsRejected() {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"" + TestKeys.ISSUER + "\",\"sub\":\"" + USER_ID + "\",\"username\":\"bob\","
                + "\"roles\":[\"ROLE_ADMIN\"],\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond() + "}");

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + "."))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(header + "." + payload))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void hs256TokenSignedWithThePublicKeyAsHmacSecretIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TestKeys.ISSUER)
                .subject(USER_ID)
                .claim("username", "bob")
                .claim("roles", List.of("ROLE_ADMIN"))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        // Classic algorithm confusion: the attacker only knows the public key and uses it as the HMAC secret.
        byte[] publicDer = pair.getPublic().getEncoded();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(publicDer)
                + "\n-----END PUBLIC KEY-----\n";

        for (byte[] secret : List.of(publicDer, publicPem.getBytes(StandardCharsets.US_ASCII))) {
            SignedJWT forged = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keys.kid()).build(), claims);
            forged.sign(new MACSigner(secret));

            assertThatThrownBy(() -> decoder.decode(forged.serialize())).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void jwksExposesOnlyPublicMembers() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new JWKSet(keys.publicJwk()).toJSONObject(true));

        Map<?, ?> parsed = new ObjectMapper().readValue(json, Map.class);
        List<?> jwks = (List<?>) parsed.get("keys");
        assertThat(jwks).hasSize(1);
        Map<?, ?> key = (Map<?, ?>) jwks.get(0);

        assertThat(key.keySet()).isEqualTo(Set.of("kty", "n", "e", "kid", "alg", "use"));
        assertThat(key.get("kty")).isEqualTo("RSA");
        assertThat(key.get("alg")).isEqualTo("RS256");
        assertThat(key.get("use")).isEqualTo("sig");
        assertThat(key.get("kid")).isEqualTo(keys.kid());
        assertThat(Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k")).noneMatch(key::containsKey);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
