package com.expense.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.RawHttp;
import com.expense.gateway.support.RecordingServer.Received;
import com.expense.gateway.support.TestInfra;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The trust boundary: identity headers reach a backend only when they were derived from a verified token. */
class IdentityHeadersIntegrationTest extends GatewayIntegrationTest {

	private static final String FORGED_ID = "11111111-1111-1111-1111-111111111111";

	@Test
	void validTokenSetsUserIdAndRolesForTheBackend() {
		String token = TestInfra.KEYS.token().subject(USER_ID).roles("ROLE_USER", "ROLE_ADMIN").build();

		this.client.get()
			.uri("/expense/v1/expenses?page=0&size=20")
			.header(HttpHeaders.AUTHORIZATION, bearer(token))
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.EXPENSE.only();
		assertThat(received.method()).isEqualTo("GET");
		assertThat(received.uri()).isEqualTo("/expense/v1/expenses?page=0&size=20");
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER,ROLE_ADMIN");
		assertThat(received.header("Authorization")).containsExactly(bearer(token));
	}

	@Test
	void userServiceAlsoGetsTheIdentity() {
		this.client.get().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(validToken())).exchange()
			.expectStatus().isOk();

		Received received = TestInfra.USER.only();
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void forgedHeadersOnAnAuthenticatedRequestAreOverwritten() {
		this.client.get()
			.uri("/expense/v1/expenses")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.header("X-User-Id", FORGED_ID)
			.header("X-User-Roles", "ROLE_ADMIN")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.EXPENSE.only();
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void forgedHeadersInAnyCaseAndRepeatedAreAllOverwrittenBySingleValues() {
		List<String> headers = List.of("Authorization: " + bearer(validToken()),
				"X-User-Id: " + FORGED_ID,
				"x-user-id: 22222222-2222-2222-2222-222222222222",
				"X-USER-ID: 33333333-3333-3333-3333-333333333333",
				"x-user-roles: ROLE_ADMIN",
				"X-USER-ROLES: ROLE_ROOT",
				"X-User-Roles: ROLE_ADMIN,ROLE_USER");

		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses", headers, null);

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.EXPENSE.only();
		assertThat(received.header("X-User-Id")).as("exactly one X-User-Id, any spelling").containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).as("exactly one X-User-Roles, any spelling")
			.containsExactly("ROLE_USER");
	}

	@Test
	void duplicateForgedValuesWithNoTokenNeverReachTheBackend() {
		List<String> headers = List.of("X-User-Id: " + FORGED_ID, "X-User-Id: " + FORGED_ID, "x-user-roles: ROLE_ADMIN");

		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses", headers, null);

		assertThat(response.status()).isEqualTo(401);
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
	}

	@Test
	void forgedHeadersOnPublicRoutesAreStrippedBeforeReachingAuth() {
		for (String path : List.of("/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
				"/auth/v1/refreshToken", "/auth/v1/logout")) {
			TestInfra.AUTH.reset();

			this.client.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-User-Id", FORGED_ID)
				.header("x-user-roles", "ROLE_ADMIN")
				.header("X-Remote-User", "root")
				.bodyValue("{\"phone_number\":\"+911234567890\"}")
				.exchange()
				.expectStatus()
				.isOk();

			Received received = TestInfra.AUTH.only();
			assertThat(received.path()).isEqualTo(path);
			assertThat(received.header("X-User-Id")).as(path).isEmpty();
			assertThat(received.header("X-User-Roles")).as(path).isEmpty();
			assertThat(received.header("X-Remote-User")).as(path).isEmpty();
		}
	}

	@Test
	void forgedDuplicateAndMixedCaseHeadersOnAPublicRouteAreStripped() {
		List<String> headers = List.of("Content-Type: application/json", "X-User-Id: " + FORGED_ID,
				"x-user-id: " + FORGED_ID, "X-USER-ROLES: ROLE_ADMIN", "x-user-roles: ROLE_ADMIN");

		RawHttp.Response response = RawHttp.send(this.port, "POST", "/auth/v1/otp/request", headers, "{}");

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.AUTH.only();
		assertThat(received.header("X-User-Id")).isEmpty();
		assertThat(received.header("X-User-Roles")).isEmpty();
	}

	@Test
	void publicRoutesWithoutAnyForgeryCarryNoIdentityHeadersEither() {
		this.client.post().uri("/auth/v1/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isOk();

		Received received = TestInfra.AUTH.only();
		assertThat(received.hasHeader("X-User-Id")).isFalse();
		assertThat(received.hasHeader("X-User-Roles")).isFalse();
	}

	@Test
	void aValidTokenOnAPublicRouteIsNotAuthenticatedSoItGetsNoIdentityAndNoAuthorizationHeader() {
		// Changed behaviour: no bearer token is read on the exact public routes, so even a valid token yields no identity
		// and the Authorization header is not forwarded (authService would otherwise re-validate a possibly stale token).
		this.client.post()
			.uri("/auth/v1/logout")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.header("X-User-Id", FORGED_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"token\":\"x\"}")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.AUTH.only();
		assertThat(received.header("X-User-Id")).isEmpty();
		assertThat(received.header("X-User-Roles")).isEmpty();
		assertThat(received.header("Authorization")).isEmpty();
	}

	/** Identity look-alikes a CGI-style or proxy-aware backend might read, in the spellings a client can send. */
	private static final List<String> LOOK_ALIKES = List.of("X-User-Id", "X-User-Roles", "Remote-User", "X-User",
			"X-UserId", "X-Auth-User", "X-Remote-User", "X-Forwarded-User", "X-Authenticated-User",
			// underscore variants and case
			"X_User_Id", "x_user_id", "X-User_Id", "X_User-Id", "X_User_Roles", "x_user_roles", "REMOTE_USER",
			"remote_user", "X_UserId", "X_Auth_User", "X_Remote_User", "X_Forwarded_User", "X_Authenticated_User",
			"X_User", "x-userid", "X-AUTH-USER", "x-forwarded-user", "X-AUTHENTICATED_USER");

	private static List<String> lookAlikeHeaderLines() {
		List<String> lines = new ArrayList<>();
		for (String name : LOOK_ALIKES) {
			lines.add(name + ": " + FORGED_ID);
			lines.add(name + ": root");
		}
		return lines;
	}

	/** Only the gateway's own X-User-Id / X-User-Roles may be left, and never with a forged value. */
	private static void assertNoLookAlikeReached(Received received) {
		for (Map.Entry<String, String> header : received.headers()) {
			String normalised = header.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
			boolean identityName = LOOK_ALIKES.stream()
				.anyMatch((name) -> name.toLowerCase(java.util.Locale.ROOT).replace('_', '-').equals(normalised));
			if (!identityName) {
				continue;
			}
			boolean gatewaySet = header.getKey().equalsIgnoreCase("X-User-Id")
					|| header.getKey().equalsIgnoreCase("X-User-Roles");
			assertThat(gatewaySet).as("look-alike header reached the backend: " + header).isTrue();
			assertThat(header.getValue()).as(header.getKey()).isNotEqualTo(FORGED_ID).isNotEqualTo("root");
		}
	}

	@Test
	void identityLookAlikesInEveryCaseAndUnderscoreSpellingNeverReachABackendOnAnAuthenticatedRequest() {
		List<String> headers = new ArrayList<>(lookAlikeHeaderLines());
		headers.add("Authorization: " + bearer(validToken()));

		RawHttp.Response response = RawHttp.send(this.port, "GET", "/user/v1/me", headers, null);

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.USER.only();
		assertNoLookAlikeReached(received);
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER");
		for (String name : List.of("Remote-User", "REMOTE_USER", "X-User", "X_User", "X-UserId", "X_UserId",
				"X-Auth-User", "X_Auth_User", "X-Remote-User", "X-Forwarded-User", "X-Authenticated-User",
				"X_User_Id", "x_user_id", "X-User_Id", "X_User-Id", "X_User_Roles")) {
			assertThat(received.header(name)).as(name).isEmpty();
		}
	}

	@ParameterizedTest(name = "identity look-alikes are stripped on public POST {0}")
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void identityLookAlikesNeverReachAuthServiceOnPublicRoutes(String path) {
		List<String> headers = new ArrayList<>(lookAlikeHeaderLines());
		headers.add("Content-Type: application/json");

		RawHttp.Response response = RawHttp.send(this.port, "POST", path, headers, "{}");

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.AUTH.only();
		for (Map.Entry<String, String> header : received.headers()) {
			String normalised = header.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
			assertThat(List.of("x-user-id", "x-user-roles", "remote-user", "x-user", "x-userid", "x-auth-user",
					"x-remote-user", "x-forwarded-user", "x-authenticated-user"))
				.as("look-alike reached authService: " + header)
				.doesNotContain(normalised);
		}
	}

	@Test
	void identityLookAlikesWithoutATokenAreAnsweredWith401AndNeverForwarded() {
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses", lookAlikeHeaderLines(), null);

		assertThat(response.status()).isEqualTo(401);
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
	}

	@Test
	void repeatedAuthorizationHeadersAreCollapsedToTheAuthenticatedFirstOne() {
		String first = bearer(validToken());
		String other = bearer(TestInfra.KEYS.token().subject("22222222-2222-2222-2222-222222222222").build());
		List<String> headers = List.of("Authorization: " + first, "authorization: " + other, "AUTHORIZATION: Bearer garbage");

		RawHttp.Response response = RawHttp.send(this.port, "GET", "/user/v1/me", headers, null);

		assertThat(response.status()).isEqualTo(200);
		Received received = TestInfra.USER.only();
		assertThat(received.header("Authorization")).as("exactly one Authorization header").containsExactly(first);
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
	}

	@Test
	void aValidFirstAuthorizationWithAGarbageSecondOneForwardsOnlyTheFirst() {
		String first = bearer(validToken());

		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses",
				List.of("Authorization: " + first, "Authorization: Bearer not-a-jwt"), null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(TestInfra.EXPENSE.only().header("Authorization")).containsExactly(first);
	}

	@Test
	void anInvalidFirstAuthorizationIsRejectedEvenWhenASecondOneIsValid() {
		// The resource server authenticates the first header, so a valid second one must not rescue (or replace) it.
		RawHttp.Response response = RawHttp.send(this.port, "GET", "/expense/v1/expenses",
				List.of("Authorization: Bearer not-a-jwt", "Authorization: " + bearer(validToken())), null);

		assertThat(response.status()).isEqualTo(401);
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
	}

	@Test
	void aSingleAuthorizationHeaderIsForwardedAsIs() {
		String header = bearer(validToken());

		this.client.get().uri("/expense/v1/expenses").header(HttpHeaders.AUTHORIZATION, header).exchange()
			.expectStatus().isOk();

		assertThat(TestInfra.EXPENSE.only().header("Authorization")).containsExactly(header);
	}

	@Test
	void aTokenWithoutRolesGetsNoRolesHeaderAndAForgedOneIsStillRemoved() {
		String token = TestInfra.KEYS.token().subject(USER_ID).rolesClaim(null).build();

		this.client.get()
			.uri("/expense/v1/expenses")
			.header(HttpHeaders.AUTHORIZATION, bearer(token))
			.header("X-User-Roles", "ROLE_ADMIN")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.EXPENSE.only();
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).isEmpty();
	}

	@Test
	void rolesThatCouldBreakTheCommaListAreDropped() {
		String token = TestInfra.KEYS.token()
			.subject(USER_ID)
			.rolesClaim(List.of("ROLE_USER", "ROLE_A,ROLE_ADMIN", "ROLE_B\r\nX-Evil: 1", ""))
			.build();

		this.client.get().uri("/expense/v1/expenses").header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
			.expectStatus().isOk();

		Received received = TestInfra.EXPENSE.only();
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER");
		assertThat(received.hasHeader("X-Evil")).isFalse();
	}

	@Test
	void methodOverrideAndOtherTrustHeadersAreStrippedFromAuthenticatedRequests() {
		this.client.post()
			.uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.header("X-HTTP-Method-Override", "DELETE")
			.header("X-HTTP-Method", "DELETE")
			.header("x-method-override", "DELETE")
			.header("X-Forwarded-User", "root")
			.header("X-Authenticated-User", "root")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{}")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.USER.only();
		assertThat(received.method()).isEqualTo("POST");
		for (String name : List.of("X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override", "X-Forwarded-User",
				"X-Authenticated-User", "X-Remote-User")) {
			assertThat(received.header(name)).as(name).isEmpty();
		}
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
	}

	@Test
	void methodOverrideHeadersAreStrippedOnPublicRoutesToo() {
		this.client.post()
			.uri("/auth/v1/otp/request")
			.header("X-HTTP-Method-Override", "GET")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{}")
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.AUTH.only();
		assertThat(received.method()).isEqualTo("POST");
		assertThat(received.hasHeader("X-HTTP-Method-Override")).isFalse();
	}
}
