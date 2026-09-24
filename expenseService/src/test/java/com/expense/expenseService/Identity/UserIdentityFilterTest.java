package com.expense.expenseService.Identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests: no Spring context, no Docker. */
class UserIdentityFilterTest {

    private static final String USER = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final UserIdentityFilter filter = new UserIdentityFilter(json);

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "the request must not reach the controller");
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals(401, body.get("status").asInt());
        assertEquals("Unauthorized", body.get("error").asText());
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals(request.getRequestURI(), body.get("path").asText());
        assertTrue(body.get("timestamp").asText().endsWith("Z"), "ISO-8601 UTC: " + body.get("timestamp"));
        assertFalse(body.get("message").asText().isBlank());
        assertNull(body.get("details"), "details only accompany VALIDATION_FAILED");
        assertEquals(6, body.size(), "exactly the pinned properties: " + body);
    }

    @Test
    void missingHeaderIs401WithThePinnedBody() throws Exception {
        assertRejected(request("GET", "/expense/v1/expenses"));
    }

    @Test
    void blankHeaderIs401() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", "   ");
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uuid",
            "1",
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6",       // one char short
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d0",     // one char long
            "9a8b7c6d5e4f4a3b8c2d1e0f9a8b7c6d",          // no dashes
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6g",      // non-hex
            "{9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d}",
            " 9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d ",
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d\n",
            "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d,9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
            "1' or '1'='1",
            "00000000-0000-0000-0000-000000000000/../x"
    })
    void notAStrictUuidIs401(String value) throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", value);
        assertRejected(request);
    }

    @Test
    void duplicatedHeaderIs401EvenWhenBothValuesAreValid() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", USER);
        request.addHeader("X-User-Id", "11111111-2222-4333-8444-555555555555");
        assertRejected(request);
    }

    @Test
    void duplicatedHeaderIs401EvenWhenBothValuesAreTheSame() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", USER);
        request.addHeader("X-User-Id", USER);
        assertRejected(request);
    }

    @Test
    void validHeaderPassesAndTheIdIsExposedToControllers() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", USER);
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest(), "the chain must have been invoked");
        assertEquals(USER, request.getAttribute(UserIdentityFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void upperCaseUuidIsAcceptedAndNormalisedToLowerCase() throws Exception {
        MockHttpServletRequest request = request("POST", "/expense/v1/expenses");
        request.addHeader("X-User-Id", USER.toUpperCase());
        MockFilterChain chain = new MockFilterChain();

        run(request, chain);

        assertNotNull(chain.getRequest());
        assertEquals(USER, request.getAttribute(UserIdentityFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void headerNameIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/getExpense");
        request.addHeader("x-user-id", USER);
        MockFilterChain chain = new MockFilterChain();

        run(request, chain);

        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/liveness"})
    void actuatorIsOpenWithoutAHeader(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request("GET", path), chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), path + " must not need an identity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/error", "/error/", "/error;jsessionid=abc", "/error/x", "/Error"})
    void aDirectRequestForErrorWithoutAnIdentityIsThePinned401(String path) throws Exception {
        assertRejected(request("GET", path));
        assertRejected(request("POST", path));
    }

    @Test
    void aDirectRequestForErrorWithAValidIdentityPassesThrough() throws Exception {
        MockHttpServletRequest request = request("GET", "/error");
        request.addHeader("X-User-Id", USER);
        MockFilterChain chain = new MockFilterChain();

        run(request, chain);

        assertSame(request, chain.getRequest());
        assertEquals(USER, request.getAttribute(UserIdentityFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void theContainersOwnErrorDispatchIsNotFilteredSoTheErrorPageStillRendersWithoutAnIdentity() throws Exception {
        // OncePerRequestFilter skips an error dispatch (recognised by the container's error attributes), which is what lets a Tomcat-level failure reach ApiErrorController.
        MockHttpServletRequest dispatch = request("GET", "/error");
        dispatch.setDispatcherType(DispatcherType.ERROR);
        dispatch.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/expense/v1/%zz");
        dispatch.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(dispatch, chain);

        assertEquals(200, response.getStatus());
        assertSame(dispatch, chain.getRequest(), "the error dispatch must reach the error controller");
        assertNull(dispatch.getAttribute(UserIdentityFilter.USER_ID_ATTRIBUTE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/../expense/v1/expenses",
            "/actuator/%2e%2e/expense/v1/expenses",
            "/error/../expense/v1/expenses",
            "/error",
            "/actuatorx/health",
            "/actuator-evil",
            "/errors",
            "/Actuator/health",
            "/ACTUATOR/health",
            "/expense/actuator/health",
            "/expense/v1/expenses;/actuator/health",
            "//actuator/../expense/v1/expenses",
            "/",
            "/expense/v1/expenses"
    })
    void lookalikeAndTraversalPathsStillNeedAnIdentity(String path) throws Exception {
        assertRejected(request("GET", path));
    }

    @Test
    void semicolonParametersOnAnOpenPathDoNotBreakTheExemption() throws Exception {
        // Tomcat strips ;params before mapping, so /actuator;x=1/health really is the health endpoint.
        MockFilterChain chain = new MockFilterChain();

        run(request("GET", "/actuator;jsessionid=abc/health"), chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void contextPathIsNotPartOfTheJudgedPath() throws Exception {
        MockHttpServletRequest open = request("GET", "/svc/actuator/health");
        open.setContextPath("/svc");
        MockFilterChain chain = new MockFilterChain();
        run(open, chain);
        assertNotNull(chain.getRequest());

        MockHttpServletRequest closed = request("GET", "/svc/expense/v1/expenses");
        closed.setContextPath("/svc");
        assertRejected(closed);
    }

    @Test
    void theRejectionNeverEchoesTheHeaderValue() throws Exception {
        MockHttpServletRequest request = request("GET", "/expense/v1/expenses");
        request.addHeader("X-User-Id", "attacker-controlled-value");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request, chain);

        assertFalse(response.getContentAsString().contains("attacker-controlled-value"));
    }
}
