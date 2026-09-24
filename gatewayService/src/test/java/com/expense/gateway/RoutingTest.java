package com.expense.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.RecordingServer.Received;
import com.expense.gateway.support.RecordingServer.Reply;
import com.expense.gateway.support.TestInfra;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class RoutingTest extends GatewayIntegrationTest {

	private static final String BATCH = "{\"messages\":[{\"sender\":\"HDFCBK\",\"body\":\"Rs 250.00 spent on FOOD at SWIGGY\","
			+ "\"received_at\":\"2026-09-12T20:41:07+05:30\"}]}";

	@Autowired
	private RouteLocator routeLocator;

	@Test
	void smsBatchIsRewrittenToTheDsPathWithBodyAndIdentityIntact() {
		this.client.post()
			.uri("/sms/v1/ingest/batch")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(BATCH)
			.exchange()
			.expectStatus()
			.isOk();

		Received received = TestInfra.DS.only();
		assertThat(received.method()).isEqualTo("POST");
		assertThat(received.uri()).isEqualTo("/v1/ds/ingest/batch");
		assertThat(received.body()).isEqualTo(BATCH);
		assertThat(received.firstHeader("Content-Type")).startsWith("application/json");
		assertThat(received.header("X-User-Id")).containsExactly(USER_ID);
		assertThat(received.header("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void smsSingleIngestIsRewrittenAndTheQueryStringSurvives() {
		this.client.post()
			.uri("/sms/v1/ingest?source=mobile")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"sender\":\"HDFCBK\"}")
			.exchange()
			.expectStatus()
			.isOk();

		assertThat(TestInfra.DS.only().uri()).isEqualTo("/v1/ds/ingest?source=mobile");
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
	}

	@Test
	void everyServiceIsRoutedToItsOwnBackendWithThePathUnchanged() {
		String auth = bearer(validToken());

		this.client.get().uri("/auth/v1/ping").header(HttpHeaders.AUTHORIZATION, auth).exchange().expectStatus().isOk();
		this.client.get()
			.uri("/auth/v1/.well-known/jwks.json")
			.header(HttpHeaders.AUTHORIZATION, auth)
			.exchange()
			.expectStatus()
			.isOk();
		this.client.put().uri("/user/v1/me").header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"first_name\":\"A\"}").exchange().expectStatus().isOk();
		this.client.get().uri("/expense/v1/getExpense").header(HttpHeaders.AUTHORIZATION, auth).exchange()
			.expectStatus().isOk();

		assertThat(TestInfra.AUTH.requests()).extracting(Received::uri)
			.containsExactly("/auth/v1/ping", "/auth/v1/.well-known/jwks.json");
		assertThat(TestInfra.USER.requests()).extracting(Received::method, Received::uri)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("PUT", "/user/v1/me"));
		assertThat(TestInfra.EXPENSE.requests()).extracting(Received::uri).containsExactly("/expense/v1/getExpense");
		assertThat(TestInfra.DS.requests()).isEmpty();
	}

	@Test
	void backendStatusAndBodyAreForwardedUntouched() {
		TestInfra.AUTH.reply(Reply.json(409, "{\"code\":\"CONFLICT\",\"message\":\"otp already requested\"}"));

		this.client.post()
			.uri("/auth/v1/otp/request")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"phone_number\":\"+911234567890\"}")
			.exchange()
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.json("{\"code\":\"CONFLICT\",\"message\":\"otp already requested\"}");

		TestInfra.AUTH.reply(Reply.noContent());
		this.client.post().uri("/auth/v1/logout").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"token\":\"t\"}")
			.exchange().expectStatus().isNoContent();
	}

	@Test
	void publicAuthRoutesWorkWithoutAToken() {
		for (String path : List.of("/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
				"/auth/v1/refreshToken", "/auth/v1/logout")) {
			TestInfra.AUTH.reset();
			this.client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"a\":1}").exchange()
				.expectStatus().isOk();
			Received received = TestInfra.AUTH.only();
			assertThat(received.path()).isEqualTo(path);
			assertThat(received.body()).isEqualTo("{\"a\":1}");
			assertThat(received.hasHeader("Authorization")).isFalse();
		}
	}

	@Test
	void unknownPathWithAValidTokenIsAJson404() {
		this.client.get()
			.uri("/nothing/here")
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.status").isEqualTo(404)
			.jsonPath("$.error").isEqualTo("Not Found")
			.jsonPath("$.code").isEqualTo("NOT_FOUND")
			.jsonPath("$.path").isEqualTo("/nothing/here");
	}

	@Test
	void theRoutesAreConfiguredAsPinned() {
		List<Route> routes = this.routeLocator.getRoutes().collectList().block();

		assertThat(routes).extracting(Route::getId).containsExactlyInAnyOrder("auth-public", "auth", "user", "expense",
				"sms");
		Route sms = routes.stream().filter((route) -> route.getId().equals("sms")).findFirst().orElseThrow();
		Map<String, Object> metadata = sms.getMetadata();
		assertThat(String.valueOf(metadata.get("response-timeout"))).isEqualTo("120000");
		assertThat(String.valueOf(metadata.get("connect-timeout"))).isEqualTo("5000");
		assertThat(sms.getUri().toString()).isEqualTo(TestInfra.DS.baseUrl());
		assertThat(routes.stream().filter((route) -> route.getId().equals("user")).findFirst().orElseThrow().getUri()
			.toString()).isEqualTo(TestInfra.USER.baseUrl());
	}
}
