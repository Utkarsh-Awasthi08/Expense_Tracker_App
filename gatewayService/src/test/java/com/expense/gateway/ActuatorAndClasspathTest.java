package com.expense.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.expense.gateway.support.GatewayIntegrationTest;
import com.expense.gateway.support.TestInfra;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;

class ActuatorAndClasspathTest extends GatewayIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void healthIsPublicAndShowsNoDetails() {
		this.client.get()
			.uri("/actuator/health")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.json("{\"status\":\"UP\"}", true);
	}

	@Test
	void healthIsAnsweredByTheGatewayItselfAndNeverTouchesABackend() {
		this.client.get().uri("/actuator/health").exchange().expectStatus().isOk();

		assertThat(TestInfra.AUTH.requests()).isEmpty();
		assertThat(TestInfra.USER.requests()).isEmpty();
		assertThat(TestInfra.EXPENSE.requests()).isEmpty();
		assertThat(TestInfra.DS.requests()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "env", "beans", "gateway/routes", "gateway", "mappings", "metrics", "threaddump", "heapdump",
			"info", "loggers", "configprops", "conditions", "refresh", "shutdown", "prometheus" })
	void nothingButHealthIsExposedNotEvenWithAToken(String endpoint) {
		this.client.get().uri("/actuator/" + endpoint).exchange().expectStatus().isUnauthorized();

		this.client.get()
			.uri("/actuator/" + endpoint)
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.exchange()
			.expectStatus()
			.isNotFound();
		this.client.post()
			.uri("/actuator/" + endpoint)
			.header(HttpHeaders.AUTHORIZATION, bearer(validToken()))
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isIn(404, 405));
	}

	@Test
	void theServletStackIsNotOnTheClasspathAndTheContextIsReactive() {
		assertThat(this.context).isInstanceOf(ReactiveWebApplicationContext.class);
		assertThatThrownBy(() -> Class.forName("org.springframework.web.servlet.DispatcherServlet"))
			.isInstanceOf(ClassNotFoundException.class);
		assertThatThrownBy(() -> Class.forName("org.apache.catalina.startup.Tomcat"))
			.isInstanceOf(ClassNotFoundException.class);
		assertThatThrownBy(() -> Class.forName("jakarta.servlet.http.HttpServlet"))
			.isInstanceOf(ClassNotFoundException.class);
	}
}
