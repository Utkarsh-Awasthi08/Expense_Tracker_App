package org.example.Controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.example.Api.CooldownActiveException;
import org.example.Api.InvalidOtpException;
import org.example.Api.InvalidPhoneNumberException;
import org.example.Api.RateLimitedException;
import org.example.Auth.JsonAccessDeniedHandler;
import org.example.Auth.JsonAuthenticationEntryPoint;
import org.example.Auth.JwtConfig;
import org.example.Auth.JwtKeyProvider;
import org.example.Auth.JwtProperties;
import org.example.Auth.SecurityConfig;
import org.example.Auth.UserConfig;
import org.example.Otp.OtpService;
import org.example.Response.JwtResponseDTO;
import org.example.Service.AuthTokenService;
import org.example.Service.JwtService;
import org.example.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer slice: security rules, JSON error shapes and request validation. No database, no Docker. */
@WebMvcTest(controllers = {AuthController.class, TokenController.class, JwksController.class, OtpController.class})
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, UserConfig.class,
        SecurityWebMvcTest.TestBeans.class, SecurityWebMvcTest.AdminProbe.class})
class SecurityWebMvcTest {

    private static final String USER_ID = "0b0e6f0e-6b8e-4c2f-8d0a-3f7d6f1d2a11";
    private static final String ISO_Z = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair PAIR = TestKeys.generateRsa();
    private static final JwtKeyProvider KEYS = TestKeys.provider(PAIR);

    @TestConfiguration
    static class TestBeans {
        @Bean
        JwtKeyProvider jwtKeyProvider() {
            return KEYS;
        }

        @Bean
        JwtProperties jwtProperties() {
            return TestKeys.properties(Duration.ofMinutes(15));
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return JwtConfig.buildDecoder((RSAPublicKey) PAIR.getPublic(), TestKeys.ISSUER);
        }

        @Bean
        JwtService jwtService(JwtProperties properties) {
            return new JwtService(KEYS, properties, Clock.systemUTC());
        }
    }

    /** A protected endpoint that needs ROLE_ADMIN, to exercise the 403 path. */
    @RestController
    static class AdminProbe {
        @GetMapping("/admin-probe")
        @PreAuthorize("hasAuthority('ROLE_ADMIN')")
        String probe() {
            return "admin";
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwtService;

    @MockitoBean
    OtpService otpService;
    @MockitoBean
    AuthTokenService authTokenService;
    @MockitoBean
    UserDetailsService userDetailsService;

    private String bearer(String... roles) {
        return "Bearer " + jwtService.issueAccessToken(USER_ID, "+919876543210", List.of(roles));
    }

    private void assertErrorBody(MvcResult result, int status, String code, String path) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(body).contains("\"status\":" + status).contains("\"code\":\"" + code + "\"")
                .contains("\"path\":\"" + path + "\"").contains("\"timestamp\":\"").contains("\"message\":\"")
                .contains("\"error\":\"");
    }

    // ---- 401 -------------------------------------------------------------------------------------------------

    @Test
    void missingBearerTokenGivesJson401WithWwwAuthenticate() throws Exception {
        MvcResult result = mvc.perform(get("/auth/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/auth/v1/ping"))
                .andExpect(jsonPath("$.timestamp").value(org.hamcrest.Matchers.matchesPattern(ISO_Z)))
                .andReturn();
        assertErrorBody(result, 401, "UNAUTHORIZED", "/auth/v1/ping");
    }

    @Test
    void garbageBearerTokenGivesJson401() throws Exception {
        mvc.perform(get("/auth/v1/ping").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/auth/v1/ping").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredBearerTokenGivesJson401() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        JwtService old = new JwtService(KEYS, TestKeys.properties(Duration.ofMinutes(15)), past);
        String expired = old.issueAccessToken(USER_ID, "+919876543210", List.of("ROLE_USER"));

        mvc.perform(get("/auth/v1/ping").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tokenSignedByAnotherKeyGivesJson401() throws Exception {
        JwtKeyProvider other = TestKeys.provider(TestKeys.generateRsa());
        JwtService forger = new JwtService(other, TestKeys.properties(Duration.ofMinutes(15)), Clock.systemUTC());

        mvc.perform(get("/auth/v1/ping").header("Authorization",
                        "Bearer " + forger.issueAccessToken(USER_ID, "+919876543210", List.of("ROLE_ADMIN"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void hs256TokenKeyedWithThePublicKeyGivesJson401() throws Exception {
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(TestKeys.ISSUER).subject(USER_ID).claim("roles", List.of("ROLE_ADMIN"))
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build());
        forged.sign(new MACSigner(PAIR.getPublic().getEncoded()));

        mvc.perform(get("/admin-probe").header("Authorization", "Bearer " + forged.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- 403 -------------------------------------------------------------------------------------------------

    @Test
    void authenticatedUserWithoutRoleGetsJson403() throws Exception {
        MvcResult result = mvc.perform(get("/admin-probe").header("Authorization", bearer("ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/admin-probe"))
                .andReturn();
        assertErrorBody(result, 403, "FORBIDDEN", "/admin-probe");
    }

    @Test
    void roleFromTheTokenIsHonouredVerbatim() throws Exception {
        mvc.perform(get("/admin-probe").header("Authorization", bearer("ROLE_USER", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    // ---- public endpoints -------------------------------------------------------------------------------------

    @Test
    void healthIsPublicAndReturnsTrue() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("true"));
    }

    @Test
    void publicEndpointsAreExactAndMethodPinned() throws Exception {
        mvc.perform(post("/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/v1/otp/request")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/v1/.well-known/jwks.json")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/v1/otp/request/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/v1/ping/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void staleOrGarbageAuthorizationHeaderDoesNotBreakPublicEndpoints() throws Exception {
        when(authTokenService.refresh(anyString())).thenReturn(JwtResponseDTO.bearer("a", "r", 900));

        mvc.perform(post("/auth/v1/refreshToken").header("Authorization", "Bearer expired.or.garbage")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"));
    }

    private String expiredToken() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        return new JwtService(KEYS, TestKeys.properties(Duration.ofMinutes(15)), past)
                .issueAccessToken(USER_ID, "+919876543210", List.of("ROLE_USER"));
    }

    private static String algNoneToken() {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + b64.encodeToString(
                ("{\"iss\":\"" + TestKeys.ISSUER + "\",\"sub\":\"x\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}")
                        .getBytes()) + ".";
    }

    private String forgedToken() {
        JwtService forger = new JwtService(TestKeys.provider(TestKeys.generateRsa()),
                TestKeys.properties(Duration.ofMinutes(15)), Clock.systemUTC());
        return forger.issueAccessToken(USER_ID, "+919876543210", List.of("ROLE_ADMIN"));
    }

    private List<String> authorizationHeaders() {
        return List.of(
                "Bearer " + expiredToken(),
                "Bearer expired.or.garbage",
                "Bearer garbage",
                "Bearer",
                "Bearer ",
                bearer("ROLE_USER"),
                "Bearer " + algNoneToken(),
                "Bearer " + forgedToken(),
                "Basic Ym9iOnBhc3N3b3Jk");
    }

    @Test
    void refreshAndLogoutBehaveTheSameWithAnyAuthorizationHeader() throws Exception {
        when(authTokenService.refresh(anyString())).thenReturn(JwtResponseDTO.bearer("a", "r", 900));
        List<String> headers = authorizationHeaders();

        for (String authorization : headers) {
            mvc.perform(post("/auth/v1/refreshToken").header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("a"))
                    .andExpect(jsonPath("$.token").value("r"));
            mvc.perform(post("/auth/v1/logout").header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }
    }

    @Test
    void otpEndpointsIgnoreAuthorizationHeaderToo() throws Exception {
        doNothing().when(otpService).requestOtp(anyString());
        when(otpService.verifyOtp(anyString(), anyString())).thenReturn(JwtResponseDTO.bearer("acc", "ref", 900));

        for (String authorization : authorizationHeaders()) {
            mvc.perform(post("/auth/v1/otp/request").header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phone_number\":\"+919876543210\"}"))
                    .andExpect(status().isNoContent());

            mvc.perform(post("/auth/v1/otp/verify").header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phone_number\":\"+919876543210\",\"code\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("acc"));

            mvc.perform(get("/auth/v1/.well-known/jwks.json").header("Authorization", authorization))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keys[0].kid").value(KEYS.kid()));

            mvc.perform(get("/health").header("Authorization", authorization))
                    .andExpect(status().isOk())
                    .andExpect(content().string("true"));
        }
    }

    @Test
    void theBearerResolverDropsTheTokenOnlyForPublicEndpoints() {
        org.springframework.security.oauth2.server.resource.web.BearerTokenResolver resolver =
                SecurityConfig.publicEndpointsIgnoreBearerTokens(
                        new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver());
        String[][] publicCalls = {
                {"POST", "/auth/v1/otp/request"}, {"POST", "/auth/v1/otp/verify"},
                {"POST", "/auth/v1/refreshToken"}, {"POST", "/auth/v1/logout"},
                {"GET", "/auth/v1/.well-known/jwks.json"}, {"GET", "/health"}, {"GET", "/actuator/health"},
                {"GET", "/actuator/health/liveness"}};
        String[][] protectedCalls = {
                {"GET", "/auth/v1/ping"}, {"POST", "/health"},
                {"POST", "/auth/v1/ping"}, {"GET", "/actuator/env"}, {"POST", "/actuator/health"},
                {"GET", "/error"}, {"POST", "/error"}};

        for (String[] call : publicCalls) {
            var request = new org.springframework.mock.web.MockHttpServletRequest(call[0], call[1]);
            request.addHeader("Authorization", "Bearer some.token.value");
            assertThat(resolver.resolve(request)).as(call[0] + " " + call[1]).isNull();
        }
        for (String[] call : protectedCalls) {
            var request = new org.springframework.mock.web.MockHttpServletRequest(call[0], call[1]);
            request.addHeader("Authorization", "Bearer some.token.value");
            assertThat(resolver.resolve(request)).as(call[0] + " " + call[1]).isEqualTo("some.token.value");
        }
    }

    // ---- OTP request & verify web layer tests -----------------------------------------------------------------

    @Test
    void otpRequestSuccessGives204() throws Exception {
        doNothing().when(otpService).requestOtp("+919876543210");

        mvc.perform(post("/auth/v1/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(otpService).requestOtp("+919876543210");
    }

    @Test
    void otpRequestBlankPhoneGivesValidationFailed() throws Exception {
        mvc.perform(post("/auth/v1/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/auth/v1/otp/request"));
    }

    @Test
    void otpRequestCooldownReturns429WithRetryAfter() throws Exception {
        doThrow(new CooldownActiveException(45)).when(otpService).requestOtp(anyString());

        mvc.perform(post("/auth/v1/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.code").value("COOLDOWN_ACTIVE"));
    }

    @Test
    void otpRequestRateLimitedReturns429WithRetryAfter() throws Exception {
        doThrow(new RateLimitedException(1800)).when(otpService).requestOtp(anyString());

        mvc.perform(post("/auth/v1/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void otpVerifySuccessReturnsJwtTokens() throws Exception {
        when(otpService.verifyOtp("+919876543210", "123456"))
                .thenReturn(JwtResponseDTO.bearer("jwt-access-token", "refresh-token-xyz", 900));

        mvc.perform(post("/auth/v1/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-access-token"))
                .andExpect(jsonPath("$.token").value("refresh-token-xyz"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void otpVerifyInvalidCodeGives401() throws Exception {
        doThrow(new InvalidOtpException()).when(otpService).verifyOtp(anyString(), anyString());

        mvc.perform(post("/auth/v1/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void otpVerifyMalformedCodeGivesValidationFailed() throws Exception {
        mvc.perform(post("/auth/v1/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone_number\":\"+919876543210\",\"code\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[?(@.field=='code')]").exists());
    }

    // ---- error and jwks --------------------------------------------------------------------------------------

    @Test
    void jwksIsPublicCachedAndCarriesNoPrivateMembers() throws Exception {
        MvcResult result = mvc.perform(get("/auth/v1/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=300"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid").value(KEYS.kid()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"d\"", "\"qi\"", "\"dp\"");
    }

    @Test
    void aDirectCallToErrorIsAnOrdinaryUnauthenticatedRequestAndGetsThePinned401() throws Exception {
        for (var request : List.of(get("/error"), post("/error"), put("/error"), delete("/error"))) {
            MvcResult result = mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.path").value("/error"))
                    .andReturn();
            assertErrorBody(result, 401, "UNAUTHORIZED", "/error");
        }
    }
}
