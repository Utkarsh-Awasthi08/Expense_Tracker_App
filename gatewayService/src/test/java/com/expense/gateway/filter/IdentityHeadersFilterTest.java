package com.expense.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityHeadersFilterTest {

	private static final String SUB = "3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c";

	private final IdentityHeadersFilter filter = new IdentityHeadersFilter();

	private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

	private final GatewayFilterChain chain = (exchange) -> {
		this.forwarded.set(exchange);
		return Mono.empty();
	};

	private static Authentication jwt(Object roles) {
		Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(SUB);
		if (roles != null) {
			builder.claim("roles", roles);
		}
		return new JwtAuthenticationToken(builder.build(), AuthorityUtils.NO_AUTHORITIES);
	}

	private HttpHeaders run(MockServerHttpRequest.BaseBuilder<?> request, Authentication authentication) {
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		Mono<Void> result = this.filter.filter(exchange, this.chain);
		if (authentication != null) {
			result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
		}
		result.block();
		ServerHttpRequest out = this.forwarded.get().getRequest();
		return out.getHeaders();
	}

	@Test
	void runsBeforeEverythingElse() {
		assertThat(this.filter.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
	}

	@Test
	void setsIdentityFromTheJwt() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x").header("Authorization", "Bearer t"),
				jwt(List.of("ROLE_USER", "ROLE_ADMIN")));

		assertThat(headers.get("X-User-Id")).containsExactly(SUB);
		assertThat(headers.get("X-User-Roles")).containsExactly("ROLE_USER,ROLE_ADMIN");
		assertThat(headers.get("Authorization")).containsExactly("Bearer t");
	}

	@Test
	void overwritesForgedValuesInAnyCaseIncludingRepeatedOnes() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x")
			.header("X-User-Id", "forged-1", "forged-2")
			.header("x-user-id", "forged-3")
			.header("X-USER-ROLES", "ROLE_ROOT")
			.header("x-user-roles", "ROLE_ADMIN"), jwt(List.of("ROLE_USER")));

		assertThat(headers.get("X-User-Id")).containsExactly(SUB);
		assertThat(headers.get("x-user-id")).containsExactly(SUB);
		assertThat(headers.get("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void stripsEverythingWhenThereIsNoAuthentication() {
		HttpHeaders headers = run(MockServerHttpRequest.post("/auth/v1/otp/request")
			.header("X-User-Id", "forged")
			.header("x-user-roles", "ROLE_ADMIN")
			.header("X-Remote-User", "root")
			.header("X-Forwarded-User", "root")
			.header("X-Authenticated-User", "root")
			.header("X-HTTP-Method-Override", "DELETE")
			.header("X-HTTP-Method", "DELETE")
			.header("X-Method-Override", "DELETE")
			.header("Content-Type", "application/json"), null);

		assertThat(headers.keySet()).containsExactly("Content-Type");
	}

	@Test
	void doesNotTrustAnAuthenticationThatIsNotAJwt() {
		Authentication basic = new UsernamePasswordAuthenticationToken("alice", "pw",
				AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

		HttpHeaders headers = run(MockServerHttpRequest.get("/x").header("X-User-Id", "forged"), basic);

		assertThat(headers.containsKey("X-User-Id")).isFalse();
		assertThat(headers.containsKey("X-User-Roles")).isFalse();
	}

	@Test
	void doesNotTrustAJwtAuthenticationTokenThatIsNotMarkedAuthenticated() {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(SUB).claim("roles", List.of("ROLE_ADMIN"))
			.build();

		HttpHeaders headers = run(MockServerHttpRequest.get("/x").header("X-User-Id", "forged"),
				new JwtAuthenticationToken(jwt));

		assertThat(headers.containsKey("X-User-Id")).isFalse();
		assertThat(headers.containsKey("X-User-Roles")).isFalse();
	}

	@Test
	void omitsTheRolesHeaderWhenTheTokenHasNoRoles() {
		HttpHeaders none = run(MockServerHttpRequest.get("/x").header("X-User-Roles", "ROLE_ADMIN"), jwt(null));
		HttpHeaders empty = run(MockServerHttpRequest.get("/x").header("X-User-Roles", "ROLE_ADMIN"), jwt(List.of()));

		assertThat(none.containsKey("X-User-Roles")).isFalse();
		assertThat(empty.containsKey("X-User-Roles")).isFalse();
		assertThat(none.get("X-User-Id")).containsExactly(SUB);
	}

	@Test
	void acceptsASingleStringRolesClaimAndDropsUnsafeRoles() {
		assertThat(run(MockServerHttpRequest.get("/x"), jwt("ROLE_USER")).get("X-User-Roles"))
			.containsExactly("ROLE_USER");
		assertThat(run(MockServerHttpRequest.get("/x"),
				jwt(List.of("ROLE_USER", "ROLE_A,ROLE_B", "R\r\nX-Evil: 1", " ", "", 42, "ROLE_ADMIN")))
			.get("X-User-Roles")).containsExactly("ROLE_USER,ROLE_ADMIN");
	}

	/** Every spelling a client could use for the identity look-alikes: case and underscore variants. */
	static Stream<String> lookAlikeNames() {
		List<String> names = new ArrayList<>(List.of("X_User_Id", "x_user_id", "X-User_Id", "X_User-Id", "X_User_Roles",
				"x_user_roles", "X-User_Roles", "REMOTE_USER", "remote_user", "Remote_User", "X_UserId", "X_Remote_User",
				"X_Forwarded_User", "X_Authenticated_User", "X_Auth_User", "X_User", "X_HTTP_Method_Override",
				"x_http_method", "X_Method_Override", "X-HTTP-METHOD-OVERRIDE"));
		for (String stripped : IdentityHeadersFilter.STRIPPED_HEADERS) {
			names.add(stripped);
			names.add(stripped.toLowerCase(Locale.ROOT));
			names.add(stripped.toUpperCase(Locale.ROOT));
			names.add(stripped.replace('-', '_'));
			names.add(stripped.toLowerCase(Locale.ROOT).replace('-', '_'));
			names.add(stripped.toUpperCase(Locale.ROOT).replace('-', '_'));
		}
		return names.stream().distinct();
	}

	@Test
	void theStrippedListCoversTheIdentityLookAlikes() {
		assertThat(IdentityHeadersFilter.STRIPPED_HEADERS).contains("X-User-Id", "X-User-Roles", "Remote-User", "X-User",
				"X-UserId", "X-Auth-User", "X-Remote-User", "X-Forwarded-User", "X-Authenticated-User");
	}

	@ParameterizedTest
	@MethodSource("lookAlikeNames")
	void everySpellingOfAnIdentityLookAlikeIsStrippedWhenThereIsNoAuthentication(String name) {
		HttpHeaders headers = run(MockServerHttpRequest.post("/auth/v1/otp/request")
			.header(name, "forged", "forged-2")
			.header("Content-Type", "application/json"), null);

		assertThat(headers.keySet()).as("forwarded headers for " + name).containsExactly("Content-Type");
	}

	@ParameterizedTest
	@MethodSource("lookAlikeNames")
	void everySpellingOfAnIdentityLookAlikeIsStrippedOnAnAuthenticatedRequestToo(String name) {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x").header(name, "forged"), jwt(List.of("ROLE_USER")));

		for (String key : headers.keySet()) {
			boolean gatewaySet = key.equalsIgnoreCase("X-User-Id") || key.equalsIgnoreCase("X-User-Roles");
			assertThat(IdentityHeadersFilter.normalise(key).equals(IdentityHeadersFilter.normalise(name)) && !gatewaySet)
				.as("look-alike " + name + " forwarded as " + key)
				.isFalse();
		}
		assertThat(headers.get("X-User-Id")).containsExactly(SUB);
		assertThat(headers.get("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void underscoreVariantsOfTheIdentityHeadersCannotSurviveNextToTheRealOnes() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x")
			.header("X_User_Id", "forged-1")
			.header("x_user_id", "forged-2")
			.header("X-User_Id", "forged-3")
			.header("X_User-Id", "forged-4")
			.header("X_User_Roles", "ROLE_ADMIN")
			.header("REMOTE_USER", "root")
			.header("Remote-User", "root")
			.header("X-User", "root")
			.header("X-UserId", "root")
			.header("X-Auth-User", "root")
			.header("X-Remote-User", "root")
			.header("X-Forwarded-User", "root")
			.header("X-Authenticated-User", "root")
			.header("X-Request-Id", "abc"), jwt(List.of("ROLE_USER")));

		assertThat(headers.keySet()).containsExactlyInAnyOrder("X-User-Id", "X-User-Roles", "X-Request-Id");
		assertThat(headers.get("X-User-Id")).containsExactly(SUB);
		assertThat(headers.get("X-User-Roles")).containsExactly("ROLE_USER");
	}

	@Test
	void headersThatMerelyStartWithAStrippedNameAreLeftAlone() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x")
			.header("X-User-Agent-Hint", "a")
			.header("X-Userland", "b")
			.header("X-Forwarded-For", "10.0.0.1")
			.header("X-Forwarded-Host", "example.org")
			.header("X-Request-Id", "abc"), jwt(List.of("ROLE_USER")));

		assertThat(headers.keySet()).containsExactlyInAnyOrder("X-User-Agent-Hint", "X-Userland", "X-Forwarded-For",
				"X-Forwarded-Host", "X-Request-Id", "X-User-Id", "X-User-Roles");
	}

	@Test
	void collapsesRepeatedAuthorizationHeadersToTheFirstOne() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x")
			.header("Authorization", "Bearer first", "Bearer second")
			.header("authorization", "Bearer third")
			.header("AUTHORIZATION", "Bearer fourth"), jwt(List.of("ROLE_USER")));

		assertThat(headers.get("Authorization")).containsExactly("Bearer first");
		assertThat(headers.keySet().stream().filter((name) -> name.equalsIgnoreCase("Authorization"))).hasSize(1);
	}

	@Test
	void aSingleAuthorizationHeaderIsForwardedUntouchedOnAnAuthenticatedRequest() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/x").header("Authorization", "Bearer only"),
				jwt(List.of("ROLE_USER")));

		assertThat(headers.get("Authorization")).containsExactly("Bearer only");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void thePublicPostRoutesNeverForwardAnAuthorizationHeader(String path) {
		HttpHeaders headers = run(MockServerHttpRequest.post(path)
			.header("Authorization", "Bearer stale", "Bearer other")
			.header("authorization", "Bearer third")
			.header("Content-Type", "application/json"), null);

		assertThat(headers.keySet()).containsExactly("Content-Type");
	}

	@Test
	void aPublicRouteNeverForwardsAnAuthorizationHeaderEvenIfAnAuthenticationIsPresent() {
		HttpHeaders headers = run(MockServerHttpRequest.post("/auth/v1/logout")
			.header("Authorization", "Bearer valid")
			.header("X-User-Id", "forged"), jwt(List.of("ROLE_USER")));

		assertThat(headers.containsKey("Authorization")).isFalse();
		assertThat(headers.containsKey("X-User-Id")).isFalse();
		assertThat(headers.containsKey("X-User-Roles")).isFalse();
	}

	@Test
	void publicHealthNeverForwardsAnAuthorizationHeader() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/actuator/health").header("Authorization", "Bearer stale"),
				null);

		assertThat(headers.containsKey("Authorization")).isFalse();
	}

	@Test
	void aRequestThatIsNotAuthenticatedNeverForwardsAnAuthorizationHeader() {
		HttpHeaders headers = run(MockServerHttpRequest.get("/user/v1/me").header("Authorization", "Bearer x"), null);

		assertThat(headers.containsKey("Authorization")).isFalse();
	}

	@Test
	void leavesOtherHeadersAndTheRequestLineAlone() {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/expense/v1/expenses?page=1")
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.header("X-Request-Id", "abc");
		MockServerWebExchange exchange = MockServerWebExchange.from(request);

		this.filter.filter(exchange, this.chain)
			.contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwt(List.of("ROLE_USER"))))
			.block();

		ServerHttpRequest out = this.forwarded.get().getRequest();
		assertThat(out.getURI().toString()).isEqualTo("/expense/v1/expenses?page=1");
		assertThat(out.getMethod().name()).isEqualTo("POST");
		assertThat(out.getHeaders().get("X-Request-Id")).containsExactly("abc");
		assertThat(out.getHeaders().get("Accept")).containsExactly("application/json");
	}
}
