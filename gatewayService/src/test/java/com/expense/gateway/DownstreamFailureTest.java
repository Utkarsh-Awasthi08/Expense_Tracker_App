package com.expense.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.RecordingServer;
import com.expense.gateway.support.RecordingServer.Reply;
import com.expense.gateway.support.TestInfra;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Backend failures become the pinned JSON error body (502/504, code INTERNAL) and never leak internals. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DownstreamFailureTest {

	private static final int DEAD_PORT = RecordingServer.freePort();

	private static final RecordingServer SLOW = RecordingServer.start();

	private static final RecordingServer RESETTING = RecordingServer.start();

	@LocalServerPort
	private int port;

	private WebTestClient client;

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + DEAD_PORT);
		registry.add("USER_SERVICE_URL", () -> "http://127.0.0.1:" + DEAD_PORT);
		registry.add("EXPENSE_SERVICE_URL", SLOW::baseUrl);
		registry.add("DS_SERVICE_URL", RESETTING::baseUrl);
		registry.add("JWT_JWKS_URI", TestInfra::jwksUri);
		registry.add("JWT_ISSUER", () -> "expense-tracker-auth");
		// routes without their own metadata (everything except sms) use this global response timeout
		registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "700ms");
	}

	@AfterAll
	static void stopStubs() {
		SLOW.close();
		RESETTING.close();
	}

	@BeforeEach
	void setUp() {
		SLOW.reset();
		RESETTING.reset();
		this.client = WebTestClient.bindToServer()
			.baseUrl("http://127.0.0.1:" + this.port)
			.responseTimeout(Duration.ofSeconds(20))
			.build();
	}

	private String token() {
		return TestInfra.KEYS.token().subject("3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c").build();
	}

	@Test
	void aBackendThatIsDownIs502() {
		assertErrorBody(this.client.get()
			.uri("/user/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
			.exchange(), 502, "Bad Gateway", "/user/v1/me");
	}

	@Test
	void aPublicRouteWhoseBackendIsDownIsAlso502() {
		assertErrorBody(this.client.post()
			.uri("/auth/v1/otp/request")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"phone_number\":\"+911234567890\"}")
			.exchange(), 502, "Bad Gateway", "/auth/v1/otp/request");
	}

	@Test
	void aBackendThatDoesNotAnswerInTimeIs504() {
		SLOW.reply(Reply.slow(Duration.ofSeconds(4)));

		assertErrorBody(this.client.get()
			.uri("/expense/v1/expenses")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
			.exchange(), 504, "Gateway Timeout", "/expense/v1/expenses");
	}

	@Test
	void aBackendThatClosesTheConnectionWithoutAnsweringIs502() {
		RESETTING.reply(Reply.drop());

		assertErrorBody(this.client.post()
			.uri("/sms/v1/ingest")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{}")
			.exchange(), 502, "Bad Gateway", "/sms/v1/ingest");
		assertThat(RESETTING.requests()).hasSize(1);
	}

	@Test
	void unauthenticatedRequestsAreStill401EvenThoughEveryBackendIsDown() {
		this.client.get().uri("/user/v1/me").exchange().expectStatus().isUnauthorized();
	}

	private void assertErrorBody(WebTestClient.ResponseSpec response, int status, String reason, String path) {
		response.expectStatus()
			.isEqualTo(status)
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody(Map.class)
			.value((body) -> {
				assertThat(body.keySet()).isEqualTo(Set.of("timestamp", "status", "error", "code", "message", "path"));
				assertThat(body.get("status")).isEqualTo(status);
				assertThat(body.get("error")).isEqualTo(reason);
				assertThat(body.get("code")).isEqualTo("INTERNAL");
				assertThat(body.get("path")).isEqualTo(path);
				String message = (String) body.get("message");
				assertThat(message).isNotBlank();
				String everything = body.toString().toLowerCase();
				assertThat(everything).doesNotContain("127.0.0.1", "localhost", String.valueOf(DEAD_PORT), "refused",
						"exception", "netty", "reactor", "springframework", "\tat ", "stack");
			});
	}
}
