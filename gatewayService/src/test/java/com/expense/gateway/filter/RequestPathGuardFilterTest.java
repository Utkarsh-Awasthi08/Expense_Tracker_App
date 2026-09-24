package com.expense.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.web.ErrorResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The guard sits behind Spring Security's firewall, which answers first in the integration tests, so it is verified here
 * on its own: a suspicious path is answered with a JSON 400 and never handed to the rest of the chain.
 */
class RequestPathGuardFilterTest {

	private final RequestPathGuardFilter filter = new RequestPathGuardFilter(new ErrorResponseWriter(new ObjectMapper()));

	private final AtomicBoolean chained = new AtomicBoolean();

	private final GatewayFilterChain chain = (exchange) -> {
		this.chained.set(true);
		return Mono.empty();
	};

	private MockServerWebExchange exchange(String rawUri) {
		return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawUri)));
	}

	@Test
	void aTraversalPathIsAnsweredWithAJson400AndNeverRouted() {
		MockServerWebExchange exchange = exchange("http://gateway/user/v1/../../actuator/env");

		this.filter.filter(exchange, this.chain).block();

		assertThat(this.chained).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"BAD_REQUEST\"")
			.contains("\"status\":400");
	}

	@Test
	void anOrdinaryPathIsHandedOn() {
		MockServerWebExchange exchange = exchange("http://gateway/expense/v1/expenses?from=2026-01-01");

		this.filter.filter(exchange, this.chain).block();

		assertThat(this.chained).isTrue();
		assertThat(exchange.getResponse().getStatusCode()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// overlong two-byte lead bytes: %c0%af is an "overlong" slash
			"/user/v1/me%c0%af", "/user/v1/..%c0%af..%c0%afetc", "/user/v1/%c0%ae%c0%ae/x", "/user/v1/%c0", "/user/v1/%c1",
			"/user/v1/x%c1%9cy", "/user/v1/%C0%AF", "/user/v1/%C1%9C", "/user/v1/%c0%AF", "/user/v1/%C0%af",
			// F5 to FF can never occur in UTF-8
			"/user/v1/%f5", "/user/v1/%f6%80%80%80", "/user/v1/%f7%bf%bf%bf", "/user/v1/%f8%88%80%80%80",
			"/user/v1/%f9", "/user/v1/%fa", "/user/v1/%fb", "/user/v1/%fc", "/user/v1/%fd", "/user/v1/%fe", "/user/v1/%ff",
			"/user/v1/%F5", "/user/v1/%F8", "/user/v1/%FF", "/user/v1/%Fe", "/user/v1/%fF",
			// anywhere in the path, not only after a slash
			"/expense/v1/expenses/abc%c0", "/expense/v1/a%ffb/c", "/sms/v1/ingest%f5", "/%c0/user/v1/me" })
	void percentEncodedInvalidUtf8LeadBytesAreRejectedWithAJson400AndNeverRouted(String path) {
		MockServerWebExchange exchange = exchange("http://gateway" + path);

		this.filter.filter(exchange, this.chain).block();

		assertThat(this.chained).as(path).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"BAD_REQUEST\"")
			.contains("\"status\":400")
			.contains("\"path\":");
		assertThat(RequestPathGuardFilter.isSuspicious(URI.create("http://gateway" + path).getRawPath())).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// valid UTF-8: rupee sign (e2 82 b9), e-acute (c3 a9), no-break space (c2 a0), emoji (f0 9f 98 80),
			// the highest valid lead byte f4 (f4 8f bf bf), the smallest two-byte lead c2 and continuation bytes
			"/expense/v1/expenses/%e2%82%b9", "/expense/v1/expenses/caf%c3%a9", "/expense/v1/expenses/a%c2%a0b",
			"/expense/v1/expenses/%f0%9f%98%80", "/expense/v1/expenses/%f4%8f%bf%bf", "/expense/v1/expenses/%C3%A9",
			"/expense/v1/expenses/%df%bf", "/expense/v1/expenses/%ef%bf%bd", "/expense/v1/expenses/%80%bf",
			// look similar but are not a c0/c1/f5-ff lead byte
			"/expense/v1/expenses/%25c0", "/expense/v1/expenses/%3f5", "/expense/v1/expenses/c0", "/expense/v1/expenses/f5" })
	void validPercentEncodedUtf8IsHandedOn(String path) {
		MockServerWebExchange exchange = exchange("http://gateway" + path);

		this.filter.filter(exchange, this.chain).block();

		assertThat(this.chained).as(path).isTrue();
		assertThat(exchange.getResponse().getStatusCode()).isNull();
	}

	@Test
	void runsRightAfterTheIdentityFilter() {
		assertThat(this.filter.getOrder()).isGreaterThan(new IdentityHeadersFilter().getOrder());
	}
}
