package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.RawHttp;
import com.expense.gateway.support.RecordingServer.Received;
import com.expense.gateway.support.TestInfra;
import com.expense.gateway.support.TestKeys;
import com.expense.gateway.support.TestKeys.Signing;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * A mobile client keeps sending its stale access token to signup, login, refresh and logout. On the exact public routes
 * no bearer token is authenticated, so such a token cannot cause a 401, and it is not forwarded either (authService's
 * own resource server would reject it). Every other route, and every near miss of a public path, keeps strict
 * authentication.
 */
class PublicRouteBearerIntegrationTest extends GatewayIntegrationTest {

	private static final List<String> PUBLIC_POST_PATHS = List.of("/auth/v1/signup", "/auth/v1/login",
			"/auth/v1/otp/request", "/auth/v1/otp/verify", "/auth/v1/refreshToken", "/auth/v1/logout");

	/** Every kind of Authorization header value a client could send: stale, garbage, valid, forged, malformed. */
	static Stream<Arguments> authorizationValues() {
		TestKeys keys = TestInfra.KEYS;
		return Stream.of(
				Arguments.of("expired token", (Supplier<String>) () -> bearer(keys.token().expired().build())),
				Arguments.of("garbage token", (Supplier<String>) () -> "Bearer not-a-jwt"),
				Arguments.of("three garbage segments", (Supplier<String>) () -> "Bearer abc.def.ghi"),
				Arguments.of("valid token", (Supplier<String>) () -> bearer(keys.token().subject(USER_ID).build())),
				Arguments.of("wrong issuer", (Supplier<String>) () -> bearer(keys.token().issuer("someone-else").build())),
				Arguments.of("alg none", (Supplier<String>) () -> bearer(keys.token().signing(Signing.NONE).build())),
				Arguments.of("RS512 signed by the same key",
						(Supplier<String>) () -> bearer(keys.token().signing(Signing.RS512).build())),
				Arguments.of("malformed bearer", (Supplier<String>) () -> "Bearer !!!"),
				Arguments.of("empty bearer", (Supplier<String>) () -> "Bearer "),
				Arguments.of("basic scheme", (Supplier<String>) () -> "Basic dXNlcjpwYXNz"),
				Arguments.of("no scheme", (Supplier<String>) () -> "just-a-value"));
	}

	static Stream<Arguments> publicRouteWithEachAuthorization() {
		return PUBLIC_POST_PATHS.stream()
			.flatMap((path) -> authorizationValues()
				.map((values) -> Arguments.of(path, values.get()[0], values.get()[1])));
	}

	// ---- public routes: no authentication attempt, no Authorization header, no identity headers downstream

	@ParameterizedTest(name = "POST {0} with {1} reaches authService without Authorization")
	@MethodSource("publicRouteWithEachAuthorization")
	void publicPostRoutesIgnoreAnyBearerAndDoNotForwardIt(String path, String name, Supplier<String> authorization) {
		this.client.post()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, authorization.get())
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"token\":\"x\"}")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.AUTH.only();
		assertThat(received.method()).isEqualTo("POST");
		assertThat(received.path()).isEqualTo(path);
		assertThat(received.hasHeader("Authorization")).as("Authorization header forwarded").isFalse();
		assertThat(received.hasHeader("X-User-Id")).as("X-User-Id").isFalse();
		assertThat(received.hasHeader("X-User-Roles")).as("X-User-Roles").isFalse();
		assertThat(received.body()).isEqualTo("{\"token\":\"x\"}");
		assertThat(TestInfra.USER.requests()).isEmpty();
	}

	@ParameterizedTest(name = "POST {0} with a stale token is not answered by the security chain")
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void theDownstreamAnswerIsReturnedUnchangedNotAGatewayError(String path) {
		TestInfra.AUTH.reply(com.expense.gateway.support.RecordingServer.Reply.json(200, "{\"accessToken\":\"new\"}"));

		this.client.post()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, bearer(TestInfra.KEYS.token().expired().build()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"token\":\"refresh-token\"}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.json("{\"accessToken\":\"new\"}");
	}

	@ParameterizedTest(name = "POST {0} with two Authorization headers forwards none")
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void repeatedAuthorizationHeadersOnAPublicRouteAreAllRemoved(String path) {
		List<String> headers = List.of("Content-Type: application/json",
				"Authorization: " + bearer(TestInfra.KEYS.token().expired().build()),
				"authorization: Bearer garbage",
				"AUTHORIZATION: " + bearer(validToken()),
				"X-User-Id: 11111111-1111-1111-1111-111111111111");

		RawHttp.Response response = RawHttp.send(this.port, "POST", path, headers, "{}");

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.AUTH.only();
		assertThat(received.hasHeader("Authorization")).isFalse();
		assertThat(received.hasHeader("X-User-Id")).isFalse();
	}

	@ParameterizedTest(name = "GET {0} with {1}")
	@MethodSource("healthPathWithEachAuthorization")
	void publicHealthIgnoresAnyBearer(String path, String name, Supplier<String> authorization) {
		int withoutToken = this.client.get().uri(path).exchange().returnResult(String.class).getStatus().value();
		assertThat(withoutToken).as("health path must be public").isNotEqualTo(401);

		this.client.get()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, authorization.get())
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).as(path + " with " + name).isEqualTo(withoutToken));
		assertThat(TestInfra.AUTH.requests()).isEmpty();
		assertThat(TestInfra.USER.requests()).isEmpty();
	}

	static Stream<Arguments> healthPathWithEachAuthorization() {
		return Stream.of("/actuator/health", "/actuator/health/liveness")
			.flatMap((path) -> authorizationValues()
				.map((values) -> Arguments.of(path, values.get()[0], values.get()[1])));
	}

	// ---- everything else keeps strict authentication

	static Stream<Arguments> strictlyAuthenticatedRoutes() {
		return Stream.of(
				Arguments.of(HttpMethod.GET, "/user/v1/me"),
				Arguments.of(HttpMethod.PUT, "/user/v1/me"),
				Arguments.of(HttpMethod.GET, "/auth/v1/ping"),
				Arguments.of(HttpMethod.GET, TestInfra.JWKS_PATH),
				Arguments.of(HttpMethod.GET, "/auth/v1/health"),
				Arguments.of(HttpMethod.GET, "/expense/v1/expenses"),
				Arguments.of(HttpMethod.POST, "/sms/v1/ingest"),
				// public path, wrong method
				Arguments.of(HttpMethod.GET, "/auth/v1/signup"),
				Arguments.of(HttpMethod.PUT, "/auth/v1/login"),
				Arguments.of(HttpMethod.GET, "/auth/v1/otp/request"),
				Arguments.of(HttpMethod.GET, "/auth/v1/otp/verify"),
				Arguments.of(HttpMethod.PUT, "/auth/v1/refreshToken"),
				Arguments.of(HttpMethod.DELETE, "/auth/v1/logout"),
				Arguments.of(HttpMethod.POST, "/actuator/health"),
				// not health
				Arguments.of(HttpMethod.GET, "/actuator/env"));
	}

	@ParameterizedTest(name = "{0} {1} with a stale token is 401")
	@MethodSource("strictlyAuthenticatedRoutes")
	void aStaleTokenOnEveryOtherRouteIsStill401(HttpMethod method, String uri) {
		this.client.method(method)
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, bearer(TestInfra.KEYS.token().expired().build()))
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.expectBody()
			.jsonPath("$.code")
			.isEqualTo("UNAUTHORIZED");

		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "{0} {1} with a garbage token is 401")
	@MethodSource("strictlyAuthenticatedRoutes")
	void aGarbageTokenOnEveryOtherRouteIsStill401(HttpMethod method, String uri) {
		this.client.method(method)
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
			.exchange()
			.expectStatus()
			.isUnauthorized();

		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "{0} {1} without a token is still 401")
	@MethodSource("strictlyAuthenticatedRoutes")
	void noTokenOnEveryOtherRouteIsStill401(HttpMethod method, String uri) {
		this.client.method(method).uri(uri).exchange().expectStatus().isUnauthorized();

		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0} with a valid token is still routed with identity")
	@ValueSource(strings = { "/user/v1/me" })
	void aValidTokenOffThePublicRoutesStillGetsIdentityAndItsAuthorization(String uri) {
		String header = bearer(validToken());

		this.client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, header).exchange().expectStatus().isOk();

		Received received = TestInfra.USER.only();
		assertThat(received.header("Authorization")).containsExactly(header);
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
	}

	// ---- path tricks never skip authentication (stale token: 401 or 400, never routed)

	@ParameterizedTest(name = "POST {0} with a stale token is 401")
	@MethodSource("com.expense.gateway.security.PathTrickIntegrationTest#failSafeWith401")
	void nearMissesOfPublicPathsStillRejectAStaleTokenWith401(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target,
				List.of("Content-Type: application/json", "Authorization: " + bearer(TestInfra.KEYS.token().expired().build())),
				"{}");

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
		assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "POST {0} with a garbage token is 401")
	@MethodSource("com.expense.gateway.security.PathTrickIntegrationTest#failSafeWith401")
	void nearMissesOfPublicPathsStillRejectAGarbageTokenWith401(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target,
				List.of("Content-Type: application/json", "Authorization: Bearer garbage"), "{}");

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0} with a stale token is 401")
	@MethodSource("com.expense.gateway.security.PathTrickIntegrationTest#failSafeWith401")
	void nearMissesOfPublicPathsStillRejectAStaleTokenWith401ForGetToo(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target,
				List.of("Authorization: " + bearer(TestInfra.KEYS.token().expired().build())), null);

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "POST {0} with a stale token never reaches a backend")
	@MethodSource("com.expense.gateway.security.PathTrickIntegrationTest#rejectedByTheFirewall")
	void ambiguousPathsWithAStaleTokenAreRejectedNotRouted(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "POST", target,
				List.of("Content-Type: application/json", "Authorization: " + bearer(TestInfra.KEYS.token().expired().build())),
				"{}");

		assertThat(response.status()).as("status for " + target).isEqualTo(400);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "{0} /auth/v1/otp/request with a stale token is 401")
	@ValueSource(strings = { "GET", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS" })
	void onlyPostIsPublicOnTheOtpRequestPathEvenWithAStaleToken(String method) {
		RawHttp.Response response = RawHttp.send(this.port, method, "/auth/v1/otp/request",
				List.of("Authorization: " + bearer(TestInfra.KEYS.token().expired().build())), null);

		assertThat(response.status()).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "override {0}: {1}")
	@ValueSource(strings = { "X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override", "x-http-method-override" })
	void methodOverrideCannotMakeAStaleTokenRequestPublic(String header) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/auth/v1/otp/request",
				List.of(header + ": POST", "Authorization: " + bearer(TestInfra.KEYS.token().expired().build())), null);

		assertThat(response.status()).isEqualTo(401);
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "GET {0} with a stale token is not public")
	@ValueSource(strings = { "/actuator/health/", "/actuator/HEALTH", "/actuator/%68ealth", "/actuator/healthz",
			"/actuator/env", "/actuator/", "/actuator" })
	void nonHealthActuatorPathsRejectAStaleToken(String target) {
		RawHttp.Response response = RawHttp.send(this.port, "GET", target,
				List.of("Authorization: " + bearer(TestInfra.KEYS.token().expired().build())), null);

		assertThat(response.status()).as("status for " + target).isEqualTo(401);
	}

	private void assertNothingReachedABackend() {
		assertThat(TestInfra.AUTH.requests()).isEmpty();
		assertThat(TestInfra.USER.requests()).isEmpty();
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
		assertThat(TestInfra.DS.requests()).isEmpty();
	}
}
