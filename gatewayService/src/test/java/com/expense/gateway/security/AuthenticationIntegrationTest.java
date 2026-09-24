package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.TestInfra;
import com.expense.gateway.support.TestKeys;
import com.expense.gateway.support.TestKeys.Signing;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class AuthenticationIntegrationTest extends GatewayIntegrationTest {

	private static final Set<String> ERROR_BODY_FIELDS = Set.of("timestamp", "status", "error", "code", "message",
			"path");

	private static final TestKeys ATTACKER = TestKeys.generate();

	static Stream<Arguments> protectedEndpoints() {
		return Stream.of(
				Arguments.of(HttpMethod.GET, "/user/v1/me"),
				Arguments.of(HttpMethod.PUT, "/user/v1/me"),
				Arguments.of(HttpMethod.GET, "/expense/v1/expenses?page=0"),
				Arguments.of(HttpMethod.POST, "/expense/v1/expenses"),
				Arguments.of(HttpMethod.GET, "/expense/v1/getExpense"),
				Arguments.of(HttpMethod.POST, "/sms/v1/ingest"),
				Arguments.of(HttpMethod.POST, "/sms/v1/ingest/batch"),
				Arguments.of(HttpMethod.GET, "/auth/v1/ping"),
				Arguments.of(HttpMethod.GET, "/auth/v1/.well-known/jwks.json"),
				Arguments.of(HttpMethod.GET, "/auth/v1/health"),
				// the public auth paths are public for POST only
				Arguments.of(HttpMethod.GET, "/auth/v1/signup"),
				Arguments.of(HttpMethod.PUT, "/auth/v1/login"),
				Arguments.of(HttpMethod.GET, "/auth/v1/otp/request"),
				Arguments.of(HttpMethod.PUT, "/auth/v1/otp/verify"),
				Arguments.of(HttpMethod.DELETE, "/auth/v1/logout"),
				Arguments.of(HttpMethod.PATCH, "/auth/v1/refreshToken"),
				Arguments.of(HttpMethod.GET, "/no/such/route"),
				Arguments.of(HttpMethod.GET, "/"));
	}

	@ParameterizedTest(name = "{0} {1} without a token is 401")
	@MethodSource("protectedEndpoints")
	void protectedEndpointsRequireAToken(HttpMethod method, String uri) {
		WebTestClient.ResponseSpec response = this.client.method(method).uri(uri).exchange();

		assertUnauthorizedJson(response, uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri);
		assertNothingReachedABackend();
	}

	static Stream<Arguments> rejectedTokens() {
		TestKeys keys = TestInfra.KEYS;
		return Stream.of(
				Arguments.of("expired", (Supplier<String>) () -> keys.token().expired().build()),
				Arguments.of("wrong issuer", (Supplier<String>) () -> keys.token().issuer("someone-else").build()),
				Arguments.of("no issuer", (Supplier<String>) () -> keys.token().issuer(null).build()),
				Arguments.of("bad signature, right kid",
						(Supplier<String>) () -> keys.token().signedWith(ATTACKER).build()),
				Arguments.of("unknown kid, attacker key",
						(Supplier<String>) () -> ATTACKER.token().build()),
				Arguments.of("alg none", (Supplier<String>) () -> keys.token().signing(Signing.NONE).build()),
				Arguments.of("HS256 algorithm confusion",
						(Supplier<String>) () -> keys.token().signing(Signing.HS256).build()),
				// Same key as the valid tokens, another RSA algorithm: RS256 is pinned, "any RSA" is not accepted.
				Arguments.of("RS384 signed by the same key",
						(Supplier<String>) () -> keys.token().signing(Signing.RS384).build()),
				Arguments.of("RS512 signed by the same key",
						(Supplier<String>) () -> keys.token().signing(Signing.RS512).build()),
				Arguments.of("PS256 signed by the same key",
						(Supplier<String>) () -> keys.token().signing(Signing.PS256).build()),
				Arguments.of("no subject", (Supplier<String>) () -> keys.token().subject(null).build()),
				Arguments.of("subject is not a uuid", (Supplier<String>) () -> keys.token().subject("admin").build()),
				Arguments.of("subject with header injection",
						(Supplier<String>) () -> keys.token().subject("3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c\r\nX-A: b").build()),
				Arguments.of("no expiry", (Supplier<String>) () -> keys.token().expiresAt(null).build()),
				Arguments.of("garbage", (Supplier<String>) () -> "not-a-jwt"),
				Arguments.of("three garbage segments", (Supplier<String>) () -> "abc.def.ghi"),
				Arguments.of("signature stripped", (Supplier<String>) () -> {
					String token = keys.token().build();
					return token.substring(0, token.lastIndexOf('.') + 1);
				}),
				Arguments.of("payload tampered", (Supplier<String>) () -> {
					String[] parts = keys.token().build().split("\\.");
					String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
							("{\"iss\":\"" + TestKeys.ISSUER + "\",\"sub\":\"" + USER_ID + "\",\"roles\":[\"ROLE_ADMIN\"],\"exp\":"
									+ Instant.now().plusSeconds(600).getEpochSecond() + "}").getBytes());
					return parts[0] + "." + forged + "." + parts[2];
				}));
	}

	@ParameterizedTest(name = "{0} token is 401")
	@MethodSource("rejectedTokens")
	void invalidTokensAreRejectedWithJson401(String name, Supplier<String> token) {
		WebTestClient.ResponseSpec response = this.client.get()
			.uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, bearer(token.get()))
			.exchange();

		assertUnauthorizedJson(response, "/user/v1/me");
		assertNothingReachedABackend();
	}

	@ParameterizedTest(name = "Authorization: [{0}] is 401")
	@org.junit.jupiter.params.provider.ValueSource(strings = { "Bearer ", "Bearer", "Bearer a b", "Basic dXNlcjpwYXNz",
			"Token abc", "Bearer !!!" })
	void malformedAuthorizationHeadersAreRejected(String header) {
		WebTestClient.ResponseSpec response = this.client.get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, header).exchange();

		assertUnauthorizedJson(response, "/user/v1/me");
		assertNothingReachedABackend();
	}

	@Test
	void aValidTokenInTheQueryStringIsNotAccepted() {
		WebTestClient.ResponseSpec response = this.client.get().uri("/user/v1/me?access_token=" + validToken()).exchange();

		assertUnauthorizedJson(response, "/user/v1/me");
		assertNothingReachedABackend();
	}

	@Test
	void aBadBearerTokenOnAPublicRouteIsNotAuthenticatedAndNotForwarded() {
		// Changed behaviour: a mobile client attaches its stale access token to otp/request, otp/verify, refresh and
		// logout. No bearer token is read on the exact public routes, so this is answered by the route, not with 401
		// (see PublicRouteBearerIntegrationTest for the full matrix), and the header never reaches authService.
		this.client.post().uri("/auth/v1/otp/request")
			.header(HttpHeaders.AUTHORIZATION, bearer(TestInfra.KEYS.token().expired().build()))
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
			.exchange().expectStatus().isOk();

		assertThat(TestInfra.AUTH.only().hasHeader("Authorization")).isFalse();
	}

	@Test
	void aValidTokenIsAccepted() {
		this.client.get().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(validToken())).exchange()
			.expectStatus().isOk();

		assertThat(TestInfra.USER.requests()).hasSize(1);
	}

	@Test
	void anUnknownKidDoesNotBreakTheGatewayAndKnownKeysStillWork() {
		this.client.get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER.token().build()))
			.exchange().expectStatus().isUnauthorized();

		this.client.get().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(validToken())).exchange()
			.expectStatus().isOk();
	}

	@Test
	void theJwksIsCachedAndNotFetchedForEveryRequest() {
		String auth = bearer(validToken());
		this.client.get().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, auth).exchange().expectStatus().isOk();
		int fetchesAfterWarmUp = TestInfra.JWKS.requests().size();

		for (int i = 0; i < 5; i++) {
			this.client.get().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, auth).exchange().expectStatus()
				.isOk();
		}

		assertThat(TestInfra.JWKS.requests()).hasSize(fetchesAfterWarmUp);
	}

	@Test
	void theResponseDoesNotRevealWhyATokenWasRejected() {
		byte[] body = this.client.get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, bearer(TestInfra.KEYS.token().expired().build()))
			.exchange().expectStatus().isUnauthorized().expectBody().returnResult().getResponseBody();

		String text = new String(body).toLowerCase();
		assertThat(text).doesNotContain("expired", "signature", "issuer", "jwt", "exception");
	}

	private void assertUnauthorizedJson(WebTestClient.ResponseSpec response, String path) {
		response.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody(Map.class)
			.value((body) -> {
				assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ERROR_BODY_FIELDS);
				assertThat(body.get("status")).isEqualTo(401);
				assertThat(body.get("error")).isEqualTo("Unauthorized");
				assertThat(body.get("code")).isEqualTo("UNAUTHORIZED");
				assertThat((String) body.get("message")).isNotBlank();
				assertThat(body.get("path")).isEqualTo(path);
				assertThat(Instant.parse((String) body.get("timestamp"))).isBeforeOrEqualTo(Instant.now());
			});
	}

	private void assertNothingReachedABackend() {
		assertThat(TestInfra.AUTH.requests()).isEmpty();
		assertThat(TestInfra.USER.requests()).isEmpty();
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
		assertThat(TestInfra.DS.requests()).isEmpty();
	}
}
