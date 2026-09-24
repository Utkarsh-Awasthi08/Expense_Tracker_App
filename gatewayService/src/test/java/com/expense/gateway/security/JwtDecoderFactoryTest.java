package com.expense.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense.gateway.support.RecordingServer;
import com.expense.gateway.support.TestInfra;
import com.expense.gateway.support.TestKeys;
import com.expense.gateway.support.TestKeys.Signing;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

/** The production decoder (same static factory the SecurityConfig uses), exercised without any HTTP gateway around it. */
class JwtDecoderFactoryTest {

	private static final String SUB = "3f2b8c1e-6a4d-4b7e-9c55-0d1e2f3a4b5c";

	private final TestKeys keys = TestInfra.KEYS;

	private final ReactiveJwtDecoder decoder = JwtDecoderFactory.create(TestInfra.jwksUri(), TestKeys.ISSUER);

	@Test
	void decodesAValidToken() {
		String token = this.keys.token().subject(SUB).roles("ROLE_USER", "ROLE_ADMIN").build();

		StepVerifier.create(this.decoder.decode(token)).assertNext((jwt) -> {
			assertThat(jwt.getSubject()).isEqualTo(SUB);
			assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestKeys.ISSUER);
			assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("ROLE_USER", "ROLE_ADMIN");
			assertThat(jwt.getHeaders().get("kid")).isEqualTo(this.keys.kid());
			assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
		}).verifyComplete();
	}

	@Test
	void theKidIsABase64UrlSha256Thumbprint() {
		assertThat(Base64.getUrlDecoder().decode(this.keys.kid())).hasSize(32);
	}

	@Test
	void rejectsAnExpiredToken() {
		verifyBadToken(this.keys.token().subject(SUB).expired().build());
	}

	@Test
	void rejectsAWrongIssuer() {
		verifyBadToken(this.keys.token().subject(SUB).issuer("expense-tracker-auth-2").build());
	}

	@Test
	void rejectsAMissingIssuer() {
		verifyBadToken(this.keys.token().subject(SUB).issuer(null).build());
	}

	@Test
	void rejectsATamperedPayload() {
		String[] parts = this.keys.token().subject(SUB).build().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("ROLE_USER", "ROLE_ADMIN");
		String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "."
				+ parts[2];

		verifyBadToken(forged);
	}

	@Test
	void rejectsATokenSignedByAnotherKeyUnderTheSameKid() {
		verifyBadToken(this.keys.token().subject(SUB).signedWith(TestKeys.generate()).build());
	}

	@Test
	void rejectsAnUnknownKid() {
		verifyBadToken(TestKeys.generate().token().subject(SUB).build());
	}

	@Test
	void rejectsAlgNone() {
		verifyBadToken(this.keys.token().subject(SUB).signing(Signing.NONE).build());
	}

	@Test
	void pinsRs256AndRejectsOtherRsaAlgorithmsSignedByTheSameKey() {
		// Same key, same kid, valid claims, correct signature for the algorithm in the header: only the alg differs.
		verifyBadToken(this.keys.token().subject(SUB).signing(Signing.RS384).build());
		verifyBadToken(this.keys.token().subject(SUB).signing(Signing.RS512).build());
		verifyBadToken(this.keys.token().subject(SUB).signing(Signing.PS256).build());
	}

	@Test
	void theSameClaimsSignedWithRs256AreAccepted() {
		// Control for the test above: nothing but the algorithm makes the other tokens fail.
		StepVerifier.create(this.decoder.decode(this.keys.token().subject(SUB).signing(Signing.RS256).build()))
			.expectNextCount(1)
			.verifyComplete();
	}

	@Test
	void rejectsHs256AlgorithmConfusion() {
		verifyBadToken(this.keys.token().subject(SUB).signing(Signing.HS256).build());
	}

	@Test
	void rejectsATokenWithoutSubjectOrWithANonUuidSubject() {
		verifyBadToken(this.keys.token().subject(null).build());
		verifyBadToken(this.keys.token().subject("42").build());
		verifyBadToken(this.keys.token().subject("admin").build());
		verifyBadToken(this.keys.token().subject(SUB + "\n").build());
	}

	@Test
	void rejectsATokenWithoutExpiry() {
		verifyBadToken(this.keys.token().subject(SUB).expiresAt(null).build());
	}

	@Test
	void acceptsATokenThatExpiredWithinTheClockSkewAllowance() {
		String token = this.keys.token()
			.subject(SUB)
			.issuedAt(Instant.now().minusSeconds(600))
			.expiresAt(Instant.now().minusSeconds(20))
			.build();

		StepVerifier.create(this.decoder.decode(token)).expectNextCount(1).verifyComplete();
	}

	@Test
	void anUnreachableJwksIsAnInfrastructureErrorNotABadToken() {
		int deadPort = RecordingServer.freePort();
		ReactiveJwtDecoder offline = JwtDecoderFactory.create("http://127.0.0.1:" + deadPort + "/jwks.json",
				TestKeys.ISSUER);

		StepVerifier.create(offline.decode(this.keys.token().subject(SUB).build()))
			.expectErrorSatisfies((error) -> assertThat(error).isInstanceOf(JwtException.class)
				.isNotInstanceOf(BadJwtException.class))
			.verify();
	}

	private void verifyBadToken(String token) {
		StepVerifier.create(this.decoder.decode(token))
			.expectErrorSatisfies((error) -> assertThat(error).isInstanceOf(BadJwtException.class))
			.verify();
	}
}
