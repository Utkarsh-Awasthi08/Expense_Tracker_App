package org.example.Integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.example.Auth.JwtKeyProvider;
import org.example.Auth.JwtProperties;
import org.example.Entities.RefreshToken;
import org.example.Repository.OtpChallengeRepository;
import org.example.Repository.RefreshTokenRepository;
import org.example.Repository.RoleRepository;
import org.example.Repository.UserRepository;
import org.example.Service.JwtService;
import org.example.Sms.SmsProvider;
import org.example.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The complete OTP identity flow against a real MySQL 8.4 (Flyway migrations + Hibernate schema validation).
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @TempDir
    static Path keyDir;

    static final Map<String, String> SENT_OTPS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class SmsTestConfig {
        @Bean
        @Primary
        SmsProvider testSmsProvider() {
            return SENT_OTPS::put;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path pem = TestKeys.writePem(keyDir, "jwt-test-key.pem", TestKeys.generateRsa());
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.private-key-path", pem::toString);
        registry.add("otp.resend-cooldown-seconds", () -> "0"); // disable cooldown for integration test runs
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    RefreshTokenRepository refreshTokenRepository;
    @Autowired
    OtpChallengeRepository otpChallengeRepository;
    @Autowired
    JwtKeyProvider jwtKeyProvider;
    @Autowired
    JwtProperties jwtProperties;
    @LocalServerPort
    int port;

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String uniquePhone() {
        return "+91" + (1000000000L + (long) (Math.random() * 8999999999L));
    }

    private MvcResult requestOtp(String phone) throws Exception {
        return mvc.perform(post("/auth/v1/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"" + phone + "\"}"))
                .andReturn();
    }

    private MvcResult verifyOtp(String phone, String code) throws Exception {
        return mvc.perform(post("/auth/v1/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andReturn();
    }

    private JsonNode requestAndVerify(String phone) throws Exception {
        MvcResult req = requestOtp(phone);
        assertThat(status(req)).isEqualTo(204);
        String code = SENT_OTPS.get(phone);
        assertThat(code).isNotNull();

        MvcResult verify = verifyOtp(phone, code);
        assertThat(status(verify)).isEqualTo(200);
        return body(verify);
    }

    private MvcResult refresh(String token) throws Exception {
        return mvc.perform(post("/auth/v1/refreshToken").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andReturn();
    }

    private MvcResult logout(String token) throws Exception {
        return mvc.perform(post("/auth/v1/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andReturn();
    }

    private MvcResult ping(String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/auth/v1/ping");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return mvc.perform(request).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hash(String token) {
        try {
            return sha256Hex(token);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SafeVarargs
    private static <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.Arrays.stream(tasks).map(task -> pool.submit(() -> {
                start.await();
                return task.call();
            })).toList();
            start.countDown();
            return futures.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
        } finally {
            pool.shutdown();
        }
    }

    // ---- the OTP flow ------------------------------------------------------------------------------------------

    @Test
    void otpRequestVerifyTokenPingRefreshLogoutFlow() throws Exception {
        String phone = uniquePhone();

        JsonNode tokens = requestAndVerify(phone);
        assertThat(tokens.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accessToken", "token", "tokenType", "expiresIn");
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);

        String access = tokens.get("accessToken").asText();
        Jwt jwt = jwtDecoder.decode(access);
        String userId = jdbc.queryForObject("select user_id from users where phone_number = ?", String.class, phone);
        assertThat(jwt.getSubject()).isEqualTo(userId);
        assertThat(java.util.UUID.fromString(jwt.getSubject())).isNotNull();
        assertThat(jwt.getClaimAsString("username")).isEqualTo(phone);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("expense-tracker-auth");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));

        // ping returns the user id as plain text
        MvcResult ping = ping(access);
        assertThat(status(ping)).isEqualTo(200);
        assertThat(ping.getResponse().getContentType()).startsWith("text/plain");
        assertThat(ping.getResponse().getContentAsString()).isEqualTo(userId);

        // refresh rotates: new token works, old is revoked
        String refresh1 = tokens.get("token").asText();
        MvcResult refreshed = refresh(refresh1);
        assertThat(status(refreshed)).isEqualTo(200);
        JsonNode rotated = body(refreshed);
        assertThat(rotated.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(rotated.get("expiresIn").asLong()).isEqualTo(900);
        String refresh2 = rotated.get("token").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);
        assertThat(ping(rotated.get("accessToken").asText()).getResponse().getContentAsString()).isEqualTo(userId);

        // replaying rotated token gives 401
        MvcResult replay = refresh(refresh1);
        assertThat(status(replay)).isEqualTo(401);
        assertThat(body(replay).get("code").asText()).isEqualTo("UNAUTHORIZED");

        // newest token still works
        MvcResult third = refresh(refresh2);
        assertThat(status(third)).isEqualTo(200);
        String refresh3 = body(third).get("token").asText();

        // logout revokes
        MvcResult logout = logout(refresh3);
        assertThat(status(logout)).isEqualTo(204);
        assertThat(logout.getResponse().getContentAsString()).isEmpty();
        assertThat(status(refresh(refresh3))).isEqualTo(401);
        assertThat(status(logout(refresh3))).isEqualTo(204);
        assertThat(status(logout("never-issued"))).isEqualTo(204);
    }

    @Test
    void rotationLinksTheOldRowToItsSuccessorAndKeepsExactlyOneActiveToken() throws Exception {
        String phone = uniquePhone();
        String refresh1 = requestAndVerify(phone).get("token").asText();
        String refresh2 = body(refresh(refresh1)).get("token").asText();

        List<RefreshToken> rows = refreshTokenRepository.findAll().stream()
                .filter(t -> t.getTokenHash().equals(hash(refresh1)) || t.getTokenHash().equals(hash(refresh2)))
                .toList();
        RefreshToken old = rows.stream().filter(t -> t.getTokenHash().equals(hash(refresh1))).findFirst().orElseThrow();
        RefreshToken current = rows.stream().filter(t -> t.getTokenHash().equals(hash(refresh2))).findFirst().orElseThrow();

        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedBy()).isEqualTo(current.getId());
        assertThat(current.getRevokedAt()).isNull();
        assertThat(current.getReplacedBy()).isNull();
        assertThat(current.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void refreshTokensAreStoredOnlyAsSha256HexHashes() throws Exception {
        String phone = uniquePhone();
        String raw = requestAndVerify(phone).get("token").asText();

        assertThat(raw).matches("^[A-Za-z0-9_-]{43}$");
        List<String> hashes = jdbc.queryForList("select token_hash from refresh_tokens", String.class);
        assertThat(hashes).isNotEmpty().allMatch(h -> h.matches("^[0-9a-f]{64}$"));
        assertThat(hashes).contains(sha256Hex(raw)).doesNotContain(raw);
    }

    @Test
    void aUserCanHoldManyRefreshTokensAtOnce() throws Exception {
        String phone = uniquePhone();
        String first = requestAndVerify(phone).get("token").asText();
        String second = requestAndVerify(phone).get("token").asText();

        assertThat(first).isNotEqualTo(second);
        assertThat(status(refresh(first))).isEqualTo(200);
        assertThat(status(refresh(second))).isEqualTo(200);
    }

    @Test
    void expiredRefreshTokenGets401() throws Exception {
        String phone = uniquePhone();
        String raw = requestAndVerify(phone).get("token").asText();
        RefreshToken row = refreshTokenRepository.findAll().stream()
                .filter(t -> t.getTokenHash().equals(hash(raw))).findFirst().orElseThrow();
        row.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        refreshTokenRepository.saveAndFlush(row);

        MvcResult result = refresh(raw);

        assertThat(status(result)).isEqualTo(401);
        assertThat(body(result).get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void unknownBlankAndOversizedRefreshTokensGet401() throws Exception {
        assertThat(status(refresh("no-such-token"))).isEqualTo(401);
        assertThat(status(refresh(""))).isEqualTo(401);
        assertThat(status(refresh("x".repeat(5000)))).isEqualTo(401);
        MvcResult noField = mvc.perform(post("/auth/v1/refreshToken").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
        assertThat(status(noField)).isEqualTo(401);
    }

    @Test
    void refreshReReadsTheRolesFromTheDatabase() throws Exception {
        String phone = uniquePhone();
        JsonNode tokens = requestAndVerify(phone);
        assertThat(jwtDecoder.decode(tokens.get("accessToken").asText()).getClaimAsStringList("roles"))
                .containsExactly("ROLE_USER");

        jdbc.update("insert into user_roles (user_id, role_id) select u.user_id, r.role_id from users u, roles r "
                + "where u.phone_number = ? and r.role_name = 'ROLE_ADMIN'", phone);

        String refreshedAccess = body(refresh(tokens.get("token").asText())).get("accessToken").asText();

        assertThat(jwtDecoder.decode(refreshedAccess).getClaimAsStringList("roles"))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void concurrentRefreshOfTheSameTokenSucceedsExactlyOnce() throws Exception {
        String raw = requestAndVerify(uniquePhone()).get("token").asText();

        List<Integer> statuses = runConcurrently(() -> status(refresh(raw)), () -> status(refresh(raw)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void wrongOtpReturns401() throws Exception {
        String phone = uniquePhone();
        requestOtp(phone);

        MvcResult result = verifyOtp(phone, "000000");

        assertThat(status(result)).isEqualTo(401);
        assertThat(body(result).get("code").asText()).isEqualTo("INVALID_OTP");
    }

    @Test
    void replayedOtpReturns401() throws Exception {
        String phone = uniquePhone();
        requestOtp(phone);
        String code = SENT_OTPS.get(phone);

        // first verify succeeds
        assertThat(status(verifyOtp(phone, code))).isEqualTo(200);

        // second verify with same code fails
        MvcResult replayed = verifyOtp(phone, code);
        assertThat(status(replayed)).isEqualTo(401);
        assertThat(body(replayed).get("code").asText()).isEqualTo("INVALID_OTP");
    }

    @Test
    void pingNeedsATokenAndJwksDoesNot() throws Exception {
        MvcResult noToken = ping(null);
        assertThat(status(noToken)).isEqualTo(401);
        assertThat(noToken.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body(noToken).get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(status(ping("garbage"))).isEqualTo(401);

        MvcResult health = mvc.perform(get("/health")).andReturn();
        assertThat(status(health)).isEqualTo(200);
        assertThat(health.getResponse().getContentAsString()).isEqualTo("true");
        assertThat(status(mvc.perform(get("/actuator/health")).andReturn())).isEqualTo(200);
        assertThat(status(mvc.perform(get("/actuator/env")).andReturn())).isEqualTo(401);
    }

    @Test
    void jwksIsPublicAndTheTokensVerifyWithTheKeyItPublishes() throws Exception {
        String access = requestAndVerify(uniquePhone()).get("accessToken").asText();

        MvcResult jwks = mvc.perform(get("/auth/v1/.well-known/jwks.json")).andReturn();

        assertThat(status(jwks)).isEqualTo(200);
        assertThat(jwks.getResponse().getHeader("Cache-Control")).isEqualTo("public, max-age=300");
        assertThat(jwks.getResponse().getContentType()).startsWith("application/json");
        String json = jwks.getResponse().getContentAsString();
        JsonNode key = JSON.readTree(json).get("keys").get(0);
        assertThat(key.fieldNames()).toIterable().containsExactlyInAnyOrder("kty", "n", "e", "kid", "alg", "use");
        assertThat(json).doesNotContain("\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\"");

        RSAKey published = (RSAKey) JWKSet.parse(json).getKeys().get(0);
        NimbusJwtDecoder gatewayStyle = NimbusJwtDecoder.withPublicKey((RSAPublicKey) published.toPublicKey()).build();
        Jwt jwt = gatewayStyle.decode(access);
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(published.getKeyID());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
    }

    @Test
    void anExpiredAccessTokenInTheAuthorizationHeaderDoesNotBlockRefresh() throws Exception {
        JsonNode tokens = requestAndVerify(uniquePhone());

        MvcResult result = mvc.perform(post("/auth/v1/refreshToken").header("Authorization", "Bearer expired.garbage.token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + tokens.get("token").asText() + "\"}")).andReturn();

        assertThat(status(result)).isEqualTo(200);
    }
}
