package com.expense.gateway.support;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Boots the real gateway on a random port, wired to the stub backends and the stub JWKS endpoint. Every subclass
 * shares one cached Spring context because the configuration is identical.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayIntegrationTest {

	protected static final String USER_ID = "3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c";

	@LocalServerPort
	protected int port;

	protected WebTestClient client;

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		TestInfra.allStubsAsBackends(registry);
	}

	@BeforeEach
	void resetStubsAndClient() {
		TestInfra.resetStubs();
		this.client = WebTestClient.bindToServer()
			.baseUrl("http://127.0.0.1:" + this.port)
			.responseTimeout(Duration.ofSeconds(20))
			.build();
	}

	protected String validToken() {
		return TestInfra.KEYS.token().subject(USER_ID).build();
	}

	protected static String bearer(String token) {
		return "Bearer " + token;
	}
}
