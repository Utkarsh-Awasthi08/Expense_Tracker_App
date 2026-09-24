package com.expense.gateway.support;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.function.Supplier;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * JVM-wide test fixtures: one RSA key pair, four stub backends (auth, user, expense, ds) and a stub JWKS endpoint.
 * Started once, shared by every test class; tests call {@link #resetStubs()} before each test.
 * The property names below are the same environment names the gateway uses in production.
 */
public final class TestInfra {

	public static final String JWKS_PATH = "/auth/v1/.well-known/jwks.json";

	public static final TestKeys KEYS = TestKeys.generate();

	public static final RecordingServer AUTH = RecordingServer.start();

	public static final RecordingServer USER = RecordingServer.start();

	public static final RecordingServer EXPENSE = RecordingServer.start();

	public static final RecordingServer DS = RecordingServer.start();

	public static final RecordingServer JWKS = RecordingServer.start();

	static {
		serveJwks(JWKS, () -> KEYS.publicJwks());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			AUTH.close();
			USER.close();
			EXPENSE.close();
			DS.close();
			JWKS.close();
		}));
	}

	private TestInfra() {
	}

	public static void serveJwks(RecordingServer server, Supplier<JWKSet> keys) {
		server.handler((request) -> request.path().equals(JWKS_PATH)
				? RecordingServer.Reply.json(200, keys.get().toString())
				: RecordingServer.Reply.json(404, "{}"));
	}

	public static String jwksUri(RecordingServer server) {
		return server.baseUrl() + JWKS_PATH;
	}

	public static String jwksUri() {
		return jwksUri(JWKS);
	}

	/** Clears recorded requests and restores the default 200 reply on the four backends (not on the JWKS stub). */
	public static void resetStubs() {
		AUTH.reset();
		USER.reset();
		EXPENSE.reset();
		DS.reset();
	}

	public static void allStubsAsBackends(DynamicPropertyRegistry registry) {
		registry.add("AUTH_SERVICE_URL", AUTH::baseUrl);
		registry.add("USER_SERVICE_URL", USER::baseUrl);
		registry.add("EXPENSE_SERVICE_URL", EXPENSE::baseUrl);
		registry.add("DS_SERVICE_URL", DS::baseUrl);
		registry.add("JWT_JWKS_URI", TestInfra::jwksUri);
		registry.add("JWT_ISSUER", () -> TestKeys.ISSUER);
	}
}
