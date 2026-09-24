package com.expenseTracker.userService.Identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdFilterTest {

    private static final String UUID_LOWER = "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91";

    private final ObjectMapper mapper = new ObjectMapper();
    private final UserIdFilter filter = new UserIdFilter(mapper);

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        return req;
    }

    private MockFilterChain run(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain;
    }

    private void assertRejected(MockHttpServletRequest req, MockHttpServletResponse res, MockFilterChain chain)
            throws Exception {
        assertThat(chain.getRequest()).as("chain must not be invoked").isNull();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getContentType()).startsWith("application/json");
        JsonNode body = mapper.readTree(res.getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .containsExactly("timestamp", "status", "error", "code", "message", "path");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("error").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("path").asText()).isEqualTo(req.getRequestURI());
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
        assertThat(body.get("timestamp").asText()).endsWith("Z");
    }

    @Test
    void missingHeaderIsRejected() throws Exception {
        MockHttpServletRequest req = request("/user/v1/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-uuid", "3f6c2b7e9d1a4c588e0b5a1d7c4f2e91",
            "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e9", "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91x",
            "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e9g", "{3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91}",
            "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91, 3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91",
            "' OR '1'='1", "../../etc/passwd"})
    void blankOrMalformedHeaderIsRejected(String value) throws Exception {
        MockHttpServletRequest req = request("/user/v1/me");
        req.addHeader("X-User-Id", value);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @Test
    void twoHeaderValuesAreRejectedEvenWhenBothAreUuids() throws Exception {
        MockHttpServletRequest req = request("/user/v1/me");
        req.addHeader("X-User-Id", UUID_LOWER);
        req.addHeader("X-User-Id", "8d0c1f2a-4b3e-4a6d-9c7f-1e2d3c4b5a69");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @Test
    void validHeaderPassesAndIsExposedAsRequestAttribute() throws Exception {
        MockHttpServletRequest req = request("/user/v1/me");
        req.addHeader("X-User-Id", UUID_LOWER);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(UserIdFilter.USER_ID_ATTRIBUTE)).isEqualTo(UUID_LOWER);
    }

    @Test
    void headerNameIsCaseInsensitiveAndUpperCaseUuidIsNormalizedToLowerCase() throws Exception {
        MockHttpServletRequest req = request("/user/v1/me");
        req.addHeader("x-user-id", UUID_LOWER.toUpperCase());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute(UserIdFilter.USER_ID_ATTRIBUTE)).isEqualTo(UUID_LOWER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/liveness"})
    void actuatorIsExemptFromTheHeaderRequirement(String path) throws Exception {
        MockHttpServletRequest req = request(path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void aDirectRequestToErrorWithoutTheHeaderIsThePinned401(String method) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/error");
        req.setServletPath("/error");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/error", "/error/", "/error/anything"})
    void errorPathsNeedAValidHeaderLikeEveryOtherPath(String path) throws Exception {
        MockHttpServletRequest bad = request(path);
        bad.addHeader("X-User-Id", "not-a-uuid");
        MockHttpServletResponse badRes = new MockHttpServletResponse();
        assertRejected(bad, badRes, run(bad, badRes));

        MockHttpServletRequest good = request(path);
        good.addHeader("X-User-Id", UUID_LOWER);
        MockHttpServletResponse goodRes = new MockHttpServletResponse();
        assertThat(run(good, goodRes).getRequest()).isNotNull();
    }

    @Test
    void theContainersOwnErrorDispatchIsStillSkippedByOncePerRequestFilterWithoutAnyPathExemption() throws Exception {
        // The servlet container re-dispatches a failed request to /error internally (e.g. an exempt /actuator/env
        // that 404s); that internal ERROR dispatch has no client-supplied header and must not be turned into a 401
        // that would replace the pinned error body. OncePerRequestFilter recognizes it by the request attribute the
        // container sets (jakarta.servlet.error.request_uri), which a client cannot forge.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/error");
        req.setServletPath("/error");
        req.setDispatcherType(DispatcherType.ERROR);
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/actuator/env");
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void aClientCannotMakeADirectErrorRequestPassByClaimingToBeAnErrorDispatch() throws Exception {
        // Only the container's own dispatch carries the error attributes and the ERROR dispatcher type; both are
        // server-side state. A plain REQUEST-dispatch to /error is judged like any other path.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/error");
        req.setServletPath("/error");
        req.addHeader("X-Original-URL", "/actuator/env");
        req.addParameter("jakarta.servlet.error.request_uri", "/actuator/env");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuatorx", "/actuator-evil/health", "/errors", "/error-page", "/", "/user/v1/me/actuator",
            "/ACTUATOR/health"})
    void lookalikePathsAreNotExempt(String path) throws Exception {
        MockHttpServletRequest req = request(path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertRejected(req, res, chain);
    }

    @Test
    void exemptionIsJudgedOnTheNormalizedPathNotTheRawUri() throws Exception {
        // The container has already normalized /actuator/../user/v1/me to /user/v1/me for routing, but the raw
        // request URI still starts with /actuator. The filter must follow the routing path.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/../user/v1/me");
        req.setServletPath("/user/v1/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = run(req, res);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(401);
    }
}
