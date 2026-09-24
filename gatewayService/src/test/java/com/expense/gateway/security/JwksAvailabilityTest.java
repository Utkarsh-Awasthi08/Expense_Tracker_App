package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.RecordingServer;
import com.expense.gateway.support.RecordingServer.Reply;
import com.expense.gateway.support.TestInfra;
import com.expense.gateway.support.TestKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * authService (the JWKS provider) is down when the gateway boots, comes up later, rotates its key, and goes away again.
 * The gateway must boot, keep serving what does not need a token, answer 503 JSON (never crash, never a misleading 401)
 * for tokens it cannot verify yet, and recover on its own. The tests share one gateway and run in order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwksAvailabilityTest {

	private static final int JWKS_PORT = RecordingServer.freePort();

	private static final RecordingServer BACKEND = RecordingServer.start();

	private static final TestKeys KEY_A = TestKeys.generate();

	private static final TestKeys KEY_B = TestKeys.generate();

	private static final TestKeys KEY_C = TestKeys.generate();

	private static volatile List<TestKeys> published = List.of(KEY_A);

	private static RecordingServer jwks;

	@LocalServerPort
	private int port;

	private WebTestClient client;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URL", BACKEND::baseUrl);
		registry.add("USER_SERVICE_URL", BACKEND::baseUrl);
		registry.add("EXPENSE_SERVICE_URL", BACKEND::baseUrl);
		registry.add("DS_SERVICE_URL", BACKEND::baseUrl);
		// nothing listens on this port yet: the JWKS endpoint is down while the gateway starts
		registry.add("JWT_JWKS_URI", () -> "http://127.0.0.1:" + JWKS_PORT + TestInfra.JWKS_PATH);
		registry.add("JWT_ISSUER", () -> TestKeys.ISSUER);
	}

	@BeforeAll
	static void clean() {
		BACKEND.reset();
	}

	@AfterAll
	static void stop() {
		BACKEND.close();
		if (jwks != null) {
			jwks.close();
		}
	}

	private WebTestClient client() {
		if (this.client == null) {
			this.client = WebTestClient.bindToServer()
				.baseUrl("http://127.0.0.1:" + this.port)
				.responseTimeout(Duration.ofSeconds(30))
				.build();
		}
		return this.client;
	}

	private static void startJwks() {
		jwks = RecordingServer.start(JWKS_PORT);
		TestInfra.serveJwks(jwks, () -> TestKeys.publicJwks(published.toArray(new TestKeys[0])));
	}

	@Test
	@Order(1)
	void theGatewayBootsAndAnswersWhileTheJwksEndpointIsDown() {
		// reaching this method at all means the application context started
		client().get().uri("/actuator/health").exchange().expectStatus().isOk();
		client().get().uri("/user/v1/me").exchange().expectStatus().isUnauthorized();
		client().post().uri("/auth/v1/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isOk();
		BACKEND.reset();
	}

	@Test
	@Order(2)
	void aTokenThatCannotBeVerifiedYetGivesA503JsonNotACrashOrAMisleading401() {
		client().get()
			.uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_A.token().build())
			.exchange()
			.expectStatus()
			.isEqualTo(503)
			.expectHeader()
			.doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody(Map.class)
			.value((body) -> {
				assertThat(body.get("status")).isEqualTo(503);
				assertThat(body.get("error")).isEqualTo("Service Unavailable");
				assertThat(body.get("code")).isEqualTo("INTERNAL");
				assertThat(body.get("path")).isEqualTo("/user/v1/me");
				assertThat(body.toString().toLowerCase()).doesNotContain("127.0.0.1", "refused", "exception");
			});
		assertThat(BACKEND.requests()).isEmpty();
	}

	@Test
	@Order(3)
	void onceTheJwksEndpointIsUpTheSameGatewayVerifiesTokens() {
		startJwks();

		client().get()
			.uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_A.token().subject("3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c").build())
			.exchange()
			.expectStatus()
			.isOk();

		assertThat(BACKEND.only().firstHeader("X-User-Id")).isEqualTo("3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c");
		assertThat(jwks.requests()).isNotEmpty();
	}

	@Test
	@Order(4)
	void aRotatedKeyIsPickedUpWhenATokenCarriesAnUnknownKid() {
		published = List.of(KEY_A, KEY_B);

		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_B.token().build()).exchange().expectStatus().isOk();
		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_A.token().build()).exchange().expectStatus().isOk();
		// a key that was never published is still rejected
		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_C.token().build()).exchange().expectStatus()
			.isUnauthorized();
	}

	@Test
	@Order(5)
	void keysAlreadyCachedKeepWorkingWhenTheJwksEndpointGoesAwayAgain() {
		jwks.close();
		jwks = null;

		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_A.token().build()).exchange().expectStatus().isOk();
		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_B.token().build()).exchange().expectStatus().isOk();
		// ... while a kid it has never seen cannot be checked right now
		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_C.token().build()).exchange().expectStatus()
			.isEqualTo(503);
	}

	@Test
	@Order(6)
	void aHangingJwksEndpointIsBoundedByATimeoutAndAnswers503() {
		startJwks();
		jwks.reply(Reply.slow(Duration.ofSeconds(30)));
		long start = System.nanoTime();

		client().get().uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY_C.token().build()).exchange().expectStatus()
			.isEqualTo(503);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
	}
}
