package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.filter.RequestPathGuardFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

class PublicRoutesTest {

	@ParameterizedTest
	@ValueSource(strings = { "/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request", "/auth/v1/otp/verify",
			"/auth/v1/refreshToken", "/auth/v1/logout" })
	void exactlyTheSixAuthEndpointsArePublicForPost(String path) {
		assertThat(PublicRoutes.isPublic(HttpMethod.POST, path)).isTrue();
		for (HttpMethod other : new HttpMethod[] { HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH,
				HttpMethod.HEAD, HttpMethod.OPTIONS }) {
			assertThat(PublicRoutes.isPublic(other, path)).as(other + " " + path).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
	void healthIsPublicForGetOnly(String path) {
		assertThat(PublicRoutes.isPublic(HttpMethod.GET, path)).isTrue();
		assertThat(PublicRoutes.isPublic(HttpMethod.POST, path)).isFalse();
		assertThat(PublicRoutes.isPublic(HttpMethod.DELETE, path)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/auth/v1/ping", "/auth/v1/.well-known/jwks.json", "/auth/v1/health", "/auth/v1/",
			"/auth/v1", "/auth/v1/otp/request/", "/auth/v1/otp/request/x", "/auth/v1/otp/requestx", "/auth/v1/otp/Request",
			"/auth/v1/otp/verify/", "/auth/v1/otp/verifyx", "/auth/v1/refreshtoken",
			"//auth/v1/otp/request", "/auth/v1/otp/request;a=b", "/auth/v1/otp/%72equest", "/auth/./v1/otp/request",
			"/auth/v1/signup/", "/auth/v1/signupx", "/auth/v1/Signup", "/auth/v1/SIGNUP", "/auth/v1/sign-up",
			"/auth/v1/login/", "/auth/v1/loginx", "/auth/v1/Login", "/auth/v1/LOGIN", "//auth/v1/signup",
			"/auth/v1/signup;a=b", "/auth/v1/%73ignup", "/auth/./v1/login",
			"/user/v1/me", "/expense/v1/expenses", "/sms/v1/ingest", "/actuator", "/actuator/", "/actuator/env",
			"/actuator/health/", "/actuator/health//x", "/actuator/health/%2e%2e", "/actuator/health/../env",
			"/actuator/healthz", "/", "" })
	void everythingElseIsNotPublic(String path) {
		for (HttpMethod method : new HttpMethod[] { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE }) {
			assertThat(PublicRoutes.isPublic(method, path)).as(method + " [" + path + "]").isFalse();
		}
	}

	@Test
	void aNullPathOrMethodIsNeverPublic() {
		assertThat(PublicRoutes.isPublic(HttpMethod.POST, null)).isFalse();
		assertThat(PublicRoutes.isPublic(null, "/auth/v1/otp/request")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/user/v1/me", "/expense/v1/expenses", "/expense/v1/expenses/", "/expense/v1/expenses/3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c",
			"/sms/v1/ingest/batch", "/auth/v1/.well-known/jwks.json", "/expense/v1/a.b", "/expense/v1/..hidden",
			"/expense/v1/x..y", "/user/v1/me.json" })
	void theGuardLetsOrdinaryPathsThrough(String path) {
		assertThat(RequestPathGuardFilter.isSuspicious(path)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/user/v1/../x", "/user/v1/./x", "/user/v1/..", "/user/v1/.", "/user/v1/%2e%2e/x",
			"/user/v1/%2E%2e/x", "/user/v1/.%2e/x", "/user/v1/..%2fx", "/user/v1/..%2Fx", "/user/v1/x%5cy",
			"/user/v1/x\\y", "/user/v1/x;y=z", "/user/v1/x%3By", "/user/v1/x%00" })
	void theGuardBlocksTraversalAndAmbiguousEncodings(String path) {
		assertThat(RequestPathGuardFilter.isSuspicious(path)).as(path).isTrue();
	}
}
