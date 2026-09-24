package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.RawHttp;
import com.expense.gateway.support.TestInfra;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every request here is sent byte-for-byte through a raw socket. None of them is one of the exact public literals, so
 * without a token the answer must be 401 and no backend may ever see the request.
 */
class PathTrickIntegrationTest extends GatewayIntegrationTest {

	/**
	 * Not public because they are not byte-for-byte a public literal, yet acceptable to the firewall: the security chain
	 * fails safe with 401 (and the router would have accepted most of them).
	 */
	static Stream<String> failSafeWith401() {
		return Stream.of(
				// trailing slash
				"/auth/v1/otp/request/", "/auth/v1/otp/verify/", "/auth/v1/refreshToken/", "/auth/v1/logout/",
				"/auth/v1/signup/", "/auth/v1/login/",
				// other suffixes
				"/auth/v1/otp/request.", "/auth/v1/otp/request.json", "/auth/v1/otp/request%20", "/auth/v1/otp/requestx",
				"/auth/v1/otp/verifyx", "/auth/v1/signupx", "/auth/v1/loginx", "/auth/v1/signup.json",
				// percent-encoding of ordinary letters
				"/auth/v1/otp/%72equest", "/auth/v1/otp/%52equest", "/auth/v1/otp/reques%74", "/%61uth/v1/otp/request",
				"/auth/%76%31/otp/request", "/auth/v1/%6ftp/request", "/auth/v1/otp/verif%79", "/auth/v1/%73ignup",
				"/auth/v1/log%69n",
				// case
				"/AUTH/V1/OTP/REQUEST", "/Auth/v1/Otp/Request", "/auth/v1/OTP/request", "/auth/v1/otp/REQUEST",
				"/auth/v1/otp/Verify", "/auth/v1/refreshtoken", "/auth/v1/SIGNUP", "/auth/v1/Signup", "/auth/v1/LOGIN",
				"/auth/v1/Login");
	}

	/**
	 * Request targets a backend could read differently from the gateway. Spring Security's strict firewall rejects them
	 * before routing, whether or not a token is present: 400 in the pinned JSON shape.
	 */
	static Stream<String> rejectedByTheFirewall() {
		return Stream.of(
				// duplicate slashes
				"//auth//v1//otp//request", "//auth/v1/otp/request", "/auth//v1/otp/request", "/auth/v1//otp/request",
				"/auth/v1/otp//request", "/auth/v1/otp/request//", "//auth/v1/signup", "/auth/v1//login",
				// matrix / path parameters
				"/auth/v1/otp/request;x=1", "/auth/v1/otp/request;", "/auth/v1;x=1/otp/request",
				"/auth/v1/otp/request%3bx=1", "/auth/v1/signup;x=1", "/auth/v1/login;x=1",
				// encoded dot, slash, NUL
				"/auth/v1/otp/request%2e", "/auth/v1/otp/request%00", "/auth/v1/otp/request%2f",
				"/auth/v1/otp%2frequest", "/auth/v1%2fotp%2frequest", "/auth%2fv1%2fotp%2frequest",
				// dot segments
				"/auth/./v1/otp/verify", "/auth/v1/./otp/request", "/auth/v1/otp/./request",
				"/auth/v1/../v1/otp/request", "/auth/v1/%2e/otp/request", "/auth/v1/otp/%2e%2e/request",
				"/auth/v1/otp/request/.", "/auth/v1/otp/request/..", "/user/v1/../auth/v1/otp/request",
				"/user/v1/%2e%2e/auth/v1/otp/request", "/user/v1/..%2fauth/v1/otp/request",
				"/user/v1/..%2f..%2fauth%2fv1%2fotp%2frequest", "/user/v1/../../auth/v1/otp/verify",
				"/expense/v1/../../auth/v1/refreshToken", "/auth/./v1/signup", "/user/v1/../auth/v1/login",
				// encoded backslash
				"/auth/v1%5cotp%5crequest", "/auth%5cv1%5cotp%5crequest");
	}

	@ParameterizedTest(name = "POST {0}")
	@MethodSource("failSafeWith401")
	void nearMissesOfPublicPathsFailSafeWith401(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target, List.of("Content-Type: application/json"),
				"{\"phone_number\":\"+911234567890\"}");

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
		assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
		assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0}")
	@MethodSource("failSafeWith401")
	void nearMissesOfPublicPathsFailSafeWith401ForGetToo(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target);

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "POST {0}")
	@MethodSource("rejectedByTheFirewall")
	void ambiguousPathsAreRejectedWithAJson400(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target, List.of("Content-Type: application/json"),
				"{\"phone_number\":\"+911234567890\"}");

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertThat(response.header("Content-Type")).startsWith("application/json");
		assertThat(response.body()).contains("\"status\":400", "\"code\":\"BAD_REQUEST\"", "\"path\":");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0}")
	@MethodSource("rejectedByTheFirewall")
	void ambiguousPathsAreRejectedForGetToo(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target);

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0}")
	@ValueSource(strings = { "/actuator/health/", "/actuator", "/actuator/", "/actuator/HEALTH", "/actuator/%68ealth",
			"/actuator/env", "/actuator/beans", "/actuator/gateway/routes", "/actuator/mappings", "/actuator/metrics",
			"/actuator/threaddump", "/actuator/heapdump", "/actuator/info", "/actuator/loggers" })
	void nonHealthActuatorPathsAreNotPublic(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target);

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
	}

	@ParameterizedTest(name = "GET {0}")
	@ValueSource(strings = { "/actuator//health", "/actuator/health//", "/actuator/health;x=1", "/actuator/health/..%2fenv",
			"/actuator/health/%2e%2e/env", "/actuator/health/../env", "/actuator/health/./", "/actuator/health%00",
			"/actuator/health%5c..%5cenv" })
	void trickedHealthPathsCannotReachOtherActuatorEndpoints(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target);

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
	}

	@ParameterizedTest(name = "{0} /auth/v1/otp/request without a token is 401")
	@ValueSource(strings = { "GET", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS" })
	void onlyPostIsPublicOnTheOtpRequestPath(String method) {
		RawHttp.Response response = RawHttp.send(this.port, method, "/auth/v1/otp/request");

		assertThat(response.status()).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "override {0}: {1}")
	@MethodSource("methodOverrides")
	void methodOverrideHeadersCannotTurnANonPublicRequestIntoAPublicOne(String header, String value) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/auth/v1/otp/request",
				List.of(header + ": " + value), null);

		assertThat(response.status()).isEqualTo(401);
		assertNothingReachedABackend();
	}

	static Stream<Arguments> methodOverrides() {
		return Stream.of(Arguments.of("X-HTTP-Method-Override", "POST"), Arguments.of("X-HTTP-Method", "POST"),
				Arguments.of("X-Method-Override", "POST"), Arguments.of("x-http-method-override", "POST"));
	}

	@ParameterizedTest(name = "invalid request target [{0}] is never forwarded")
	@ValueSource(strings = { "/auth/v1/otp/request%zz", "/auth/v1/otp/request%", "/auth/v1/otp/request%g0",
			"/auth/v1\\otp\\request", "/auth\\v1\\otp\\request", "/auth/v1/otp/request with space" })
	void syntacticallyInvalidTargetsAreNeverForwarded(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target, List.of("Content-Type: application/json"),
				"{}");

		assertThat(response.status()).isIn(400, 401);
		assertNothingReachedABackend();
	}

	@Test
	void anAbsoluteFormRequestTargetDoesNotBypassTheChecks() {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "http://127.0.0.1:" + this.port + "/user/v1/me");

		assertThat(response.status()).isEqualTo(401);
		assertNothingReachedABackend();
	}

	// ---- with a valid token: the gateway must not be usable to escape the route prefix either

	@ParameterizedTest(name = "authenticated GET {0} is rejected as 400 and forwarded nowhere")
	@ValueSource(strings = { "/user/v1/../../actuator/env", "/user/v1/..%2f..%2factuator%2fenv", "/user/v1/%2e%2e/x",
			"/user/v1/%2E%2E/x", "/user/v1/./me", "/user/v1/me;jsessionid=1", "/expense/v1/..%2fauth/v1/ping",
			"/expense/v1/x%2fy", "/expense/v1/x%5cy", "/expense/v1/x%00", "/auth/v1/../user/v1/me",
			"/sms/v1/../../health", "/sms/v1/%2e%2e/%2e%2e/admin" })
	void authenticatedPathTraversalIsRejectedBeforeRouting(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target,
				List.of("Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"BAD_REQUEST\"");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "authenticated GET {0} (invalid UTF-8 lead byte) is rejected as 400 and forwarded nowhere")
	@ValueSource(strings = { "/user/v1/me%c0%af", "/user/v1/..%c0%afetc", "/user/v1/%c0%ae%c0%ae/x", "/user/v1/%C0%AF",
			"/user/v1/%c1%9c", "/user/v1/%C1", "/expense/v1/expenses%c0", "/expense/v1/x%f5%80%80%80", "/expense/v1/x%F8",
			"/expense/v1/x%fa", "/expense/v1/x%fe", "/expense/v1/x%ff", "/expense/v1/x%FF", "/expense/v1/x%fF",
			"/sms/v1/ingest%c0%af", "/sms/v1/ingest/batch%ff", "/auth/v1/ping%c0%af", "/auth/v1/ping%f5" })
	void invalidUtf8LeadBytesInThePathAreRejectedBeforeRoutingEvenWithAValidToken(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target,
				List.of("Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertThat(response.header("Content-Type")).startsWith("application/json");
		assertThat(response.body()).contains("\"status\":400", "\"code\":\"BAD_REQUEST\"", "\"path\":");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "authenticated POST {0} (invalid UTF-8 lead byte) is rejected as 400 and forwarded nowhere")
	@ValueSource(strings = { "/expense/v1/expenses%c0%af", "/user/v1/me%C1%9C", "/sms/v1/ingest%ff" })
	void invalidUtf8LeadBytesAreRejectedForPostToo(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target,
				List.of("Authorization: " + bearer(validToken()), "Content-Type: application/json"), "{}");

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"BAD_REQUEST\"");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "unauthenticated GET {0} (invalid UTF-8 lead byte) never reaches a backend")
	@ValueSource(strings = { "/user/v1/me%c0%af", "/auth/v1/otp/request%c0%af", "/auth/v1/otp/request%ff",
			"/expense/v1/x%f5" })
	void invalidUtf8LeadBytesWithoutATokenNeverReachABackend(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target);

		assertThat(response.status()).as("status for " + target).isIn(400, 401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0} (valid UTF-8 percent-encoding) is still routed")
	@ValueSource(strings = { "/expense/v1/expenses/%e2%82%b9", "/expense/v1/expenses/caf%c3%a9",
			"/expense/v1/expenses/%f0%9f%98%80", "/expense/v1/expenses/%f4%8f%bf%bf" })
	void validUtf8PercentEncodingWithATokenIsStillRouted(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target,
				List.of("Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).as("status for " + target).isEqualTo(200);
		assertThat(TestInfra.EXPENSE.only().path()).isEqualTo(target);
	}

	@Test
	void nothingCrossesToAnotherServicesBackend() {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/user/v1/me?next=/auth/v1/ping",
				List.of("Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(TestInfra.USER.requests()).hasSize(1);
		assertThat(TestInfra.AUTH.requests()).isEmpty();
	}

	@Test
	void ordinaryTrailingSlashPathsWithATokenAreStillRouted() {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses/",
				List.of("Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(TestInfra.EXPENSE.only().path()).isEqualTo("/expense/v1/expenses/");
	}

	private void assertNothingReachedABackend() {
		assertThat(TestInfra.AUTH.requests()).isEmpty();
		assertThat(TestInfra.USER.requests()).isEmpty();
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
		assertThat(TestInfra.DS.requests()).isEmpty();
	}
}
