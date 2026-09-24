package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import reactor.core.publisher.Mono;

/** The converter must never even look at the request on an exact public route, and must delegate everywhere else. */
class PublicRouteAwareBearerTokenConverterTest {

	private static final String STALE = "Bearer eyJhbGciOiJSUzI1NiJ9.stale.stale";

	private final AtomicInteger delegateCalls = new AtomicInteger();

	private final ServerAuthenticationConverter counting = (exchange) -> {
		this.delegateCalls.incrementAndGet();
		return Mono.just(new BearerTokenAuthenticationToken("delegated"));
	};

	private static MockServerWebExchange exchange(HttpMethod method, String rawUri, String... authorization) {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(method, URI.create(rawUri));
		for (String value : authorization) {
			request.header("Authorization", value);
		}
		return MockServerWebExchange.from(request);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void aPublicPostRouteIsNeverAuthenticatedWhateverTheHeaderSays(String path) {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter(this.counting);

		for (String header : new String[] { STALE, "Bearer not-a-jwt", "Bearer !!!", "Bearer ", "Basic dXNlcjpwYXNz",
				"garbage" }) {
			assertThat(converter.convert(exchange(HttpMethod.POST, "http://gateway" + path, header)).block())
				.as(path + " with [" + header + "]")
				.isNull();
		}
		assertThat(converter.convert(exchange(HttpMethod.POST, "http://gateway" + path)).block()).isNull();
		assertThat(this.delegateCalls).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
	void publicHealthGetIsNeverAuthenticated(String path) {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter(this.counting);

		assertThat(converter.convert(exchange(HttpMethod.GET, "http://gateway" + path, STALE)).block()).isNull();
		assertThat(this.delegateCalls).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/auth/v1/otp/request/", "/auth/v1/otp/request//", "//auth/v1/otp/request",
			"/auth/v1/otp/request;x=1", "/auth/v1/otp/%72equest", "/auth/v1/OTP/REQUEST", "/auth/v1/Otp/Request",
			"/auth/v1/refreshtoken", "/auth/v1/logout/", "/auth/v1/ping", "/auth/v1/.well-known/jwks.json",
			"/auth/v1/signup/", "//auth/v1/signup", "/auth/v1/signup;x=1", "/auth/v1/%73ignup", "/auth/v1/SIGNUP",
			"/auth/v1/login/", "//auth/v1/login", "/auth/v1/LOGIN",
			"/user/v1/me", "/expense/v1/expenses", "/sms/v1/ingest", "/actuator/health/", "/actuator/env",
			"/actuator/health/%2e%2e/env", "/" })
	void everythingThatIsNotAnExactPublicLiteralIsHandedToTheBearerParser(String path) {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter(this.counting);

		assertThat(converter.convert(exchange(HttpMethod.POST, "http://gateway" + path, STALE)).block()).isNotNull();
		assertThat(converter.convert(exchange(HttpMethod.GET, "http://gateway" + path, STALE)).block()).isNotNull();
		assertThat(this.delegateCalls).hasValue(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" })
	void aPublicAuthPathWithAnotherMethodIsStillAuthenticated(String method) {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter(this.counting);

		assertThat(
				converter.convert(exchange(HttpMethod.valueOf(method), "http://gateway/auth/v1/otp/request", STALE))
					.block())
			.isNotNull();
		assertThat(this.delegateCalls).hasValue(1);
	}

	@Test
	void theStockConverterIsUsedByDefaultOffThePublicRoutes() {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter();

		assertThat(converter.convert(exchange(HttpMethod.GET, "http://gateway/user/v1/me", "Bearer abc.def.ghi")).block())
			.isInstanceOfSatisfying(BearerTokenAuthenticationToken.class,
					(token) -> assertThat(token.getToken()).isEqualTo("abc.def.ghi"));
		assertThat(converter.convert(exchange(HttpMethod.GET, "http://gateway/user/v1/me")).block()).isNull();
		assertThatThrownBy(() -> converter.convert(exchange(HttpMethod.GET, "http://gateway/user/v1/me", "Bearer !!!"))
			.block()).isInstanceOf(OAuth2AuthenticationException.class);
	}

	@Test
	void theStockConverterOnlyLooksAtTheFirstAuthorizationHeader() {
		PublicRouteAwareBearerTokenConverter converter = new PublicRouteAwareBearerTokenConverter();

		assertThat(converter
			.convert(exchange(HttpMethod.GET, "http://gateway/user/v1/me", "Bearer first.token.one", "Bearer second.token.two"))
			.block()).isInstanceOfSatisfying(BearerTokenAuthenticationToken.class,
					(token) -> assertThat(token.getToken()).isEqualTo("first.token.one"));
	}
}
