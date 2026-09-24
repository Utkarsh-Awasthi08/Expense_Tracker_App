package com.expense.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.security.JsonAccessDeniedHandler;
import com.expense.gateway.security.JsonAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.netty.http.client.PrematureCloseException;

class ErrorHandlingTest {

	private static final Set<String> FIELDS = Set.of("timestamp", "status", "error", "code", "message", "path");

	private final ObjectMapper mapper = new ObjectMapper();

	private final ErrorResponseWriter writer = new ErrorResponseWriter(this.mapper);

	private final GatewayErrorHandler errorHandler = new GatewayErrorHandler(this.writer);

	private MockServerWebExchange exchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/expense/v1/expenses"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(MockServerWebExchange exchange) throws Exception {
		return this.mapper.readValue(exchange.getResponse().getBodyAsString().block(), Map.class);
	}

	private void assertShape(MockServerWebExchange exchange, HttpStatus status, String code) throws Exception {
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
		assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		Map<String, Object> body = body(exchange);
		assertThat(body.keySet()).isEqualTo(FIELDS);
		assertThat(body.get("status")).isEqualTo(status.value());
		assertThat(body.get("error")).isEqualTo(status.getReasonPhrase());
		assertThat(body.get("code")).isEqualTo(code);
		assertThat(body.get("path")).isEqualTo("/expense/v1/expenses");
		assertThat((String) body.get("message")).isNotBlank();
		assertThat(Instant.parse((String) body.get("timestamp"))).isNotNull();
		assertThat((String) body.get("timestamp")).endsWith("Z");
	}

	@Test
	void authenticationEntryPointWrites401WithWwwAuthenticate() throws Exception {
		MockServerWebExchange exchange = exchange();

		new JsonAuthenticationEntryPoint(this.writer).commence(exchange, new InvalidBearerTokenException("expired"))
			.block();

		assertShape(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
		assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(body(exchange).get("message").toString()).doesNotContain("expired");
	}

	@Test
	void authenticationEntryPointNeverEchoesTheExceptionMessage() throws Exception {
		MockServerWebExchange exchange = exchange();

		new JsonAuthenticationEntryPoint(this.writer).commence(exchange, new BadCredentialsException("select * from users"))
			.block();

		assertShape(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("select");
	}

	@Test
	void anAuthenticationInfrastructureFailureIs503WithoutWwwAuthenticate() throws Exception {
		MockServerWebExchange exchange = exchange();

		// what Spring Security rethrows when the JWKS endpoint cannot be reached: the cause chain ends in an IOException,
		// which on its own would look like a 502 from an upstream service
		this.errorHandler
			.handle(exchange, new AuthenticationServiceException("The token could not be verified",
					new JwtException("Could not obtain the keys", new ConnectException("refused"))))
			.block();

		assertShape(exchange, HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL");
		assertThat(exchange.getResponse().getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("refused", "keys", "verified");
	}

	@Test
	void accessDeniedHandlerWrites403() throws Exception {
		MockServerWebExchange exchange = exchange();

		new JsonAccessDeniedHandler(this.writer).handle(exchange, new AccessDeniedException("nope")).block();

		assertShape(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN");
		assertThat(exchange.getResponse().getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
	}

	@Test
	void upstreamConnectionFailuresAre502() throws Exception {
		for (Throwable failure : new Throwable[] { new ConnectException("Connection refused: /10.0.0.5:9898"),
				new UnknownHostException("auth-service"),
				new RuntimeException("wrapped", new ConnectException("Connection refused")),
				PrematureCloseException.TEST_EXCEPTION, new java.nio.channels.ClosedChannelException() }) {
			MockServerWebExchange exchange = exchange();

			this.errorHandler.handle(exchange, failure).block();

			assertShape(exchange, HttpStatus.BAD_GATEWAY, "INTERNAL");
			assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("10.0.0.5", "auth-service",
					"refused");
		}
	}

	@Test
	void upstreamTimeoutsAre504() throws Exception {
		for (Throwable failure : new Throwable[] { ReadTimeoutException.INSTANCE, new ConnectTimeoutException("x"),
				new TimeoutException("Response took longer than timeout: PT2M"),
				new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout"),
				new RuntimeException("wrapped", ReadTimeoutException.INSTANCE) }) {
			MockServerWebExchange exchange = exchange();

			this.errorHandler.handle(exchange, failure).block();

			assertShape(exchange, HttpStatus.GATEWAY_TIMEOUT, "INTERNAL");
			assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("PT2M", "longer");
		}
	}

	@Test
	void noMatchingRouteIsAJson404() throws Exception {
		MockServerWebExchange exchange = exchange();

		this.errorHandler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND, "No matching handler"))
			.block();

		assertShape(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND");
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("handler");
	}

	@Test
	void badInputIs400() throws Exception {
		MockServerWebExchange exchange = exchange();

		this.errorHandler.handle(exchange, new ServerWebInputException("Failed to read HTTP message")).block();

		assertShape(exchange, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
	}

	@Test
	void anUnexpectedBugIs500WithASafeMessage() throws Exception {
		MockServerWebExchange exchange = exchange();

		this.errorHandler.handle(exchange, new IllegalStateException("SELECT * FROM users WHERE password = 'hunter2'"))
			.block();

		assertShape(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("SELECT", "hunter2",
				"IllegalState");
	}

	@Test
	void theMessageOfAResponseStatusExceptionIsNeverEchoed() throws Exception {
		MockServerWebExchange exchange = exchange();

		this.errorHandler.handle(exchange, new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream 10.1.2.3 exploded"))
			.block();

		assertShape(exchange, HttpStatus.BAD_GATEWAY, "INTERNAL");
		assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("10.1.2.3", "exploded");
	}

	@Test
	void theBodyIsValidJsonEvenWhenThePathNeedsEscaping() throws Exception {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/x/\"quoted\"/%5C/%3Cscript%3E"));

		this.errorHandler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

		Map<String, Object> body = this.mapper.readValue(exchange.getResponse().getBodyAsString().block(), Map.class);
		assertThat(body.get("path").toString()).contains("quoted");
	}
}
