package com.expense.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A throw-away RSA key pair generated in memory (nothing is read from disk) and a token builder that can produce
 * valid tokens as well as every kind of broken one.
 */
public final class TestKeys {

	public static final String ISSUER = "expense-tracker-auth";

	private final RSAKey key;

	private TestKeys(RSAKey key) {
		this.key = key;
	}

	/** kid = base64url SHA-256 thumbprint, as in the pinned contract. */
	public static TestKeys generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();
			RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
				.keyIDFromThumbprint()
				.build();
			return new TestKeys(rsaKey);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public String kid() {
		return this.key.getKeyID();
	}

	public RSAKey rsaKey() {
		return this.key;
	}

	public JWKSet publicJwks() {
		return new JWKSet(this.key.toPublicJWK());
	}

	public static JWKSet publicJwks(TestKeys... keys) {
		List<com.nimbusds.jose.jwk.JWK> jwks = new ArrayList<>();
		for (TestKeys keyHolder : keys) {
			jwks.add(keyHolder.key.toPublicJWK());
		}
		return new JWKSet(jwks);
	}

	public TokenBuilder token() {
		return new TokenBuilder(this);
	}

	public enum Signing {

		RS256, HS256, NONE,
		/** Other RSA algorithms, signed with the SAME key as RS256 (the gateway must pin RS256, not "any RSA"). */
		RS384, RS512, PS256

	}

	public static final class TokenBuilder {

		private final TestKeys keys;

		private String subject = UUID.randomUUID().toString();

		private String issuer = ISSUER;

		private List<String> roles = List.of("ROLE_USER");

		private Object rolesClaim;

		private boolean rolesClaimOverridden;

		private Instant issuedAt = Instant.now();

		private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));

		private String kid;

		private RSAKey signingKey;

		private Signing signing = Signing.RS256;

		private TokenBuilder(TestKeys keys) {
			this.keys = keys;
			this.kid = keys.kid();
			this.signingKey = keys.key;
		}

		public TokenBuilder subject(String subject) {
			this.subject = subject;
			return this;
		}

		public TokenBuilder issuer(String issuer) {
			this.issuer = issuer;
			return this;
		}

		public TokenBuilder roles(String... roles) {
			this.roles = List.of(roles);
			return this;
		}

		/** Sets the roles claim to an arbitrary JSON value (or removes it with null). */
		public TokenBuilder rolesClaim(Object value) {
			this.rolesClaim = value;
			this.rolesClaimOverridden = true;
			return this;
		}

		public TokenBuilder issuedAt(Instant issuedAt) {
			this.issuedAt = issuedAt;
			return this;
		}

		public TokenBuilder expiresAt(Instant expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		public TokenBuilder expired() {
			this.issuedAt = Instant.now().minus(Duration.ofHours(2));
			this.expiresAt = Instant.now().minus(Duration.ofHours(1));
			return this;
		}

		public TokenBuilder kid(String kid) {
			this.kid = kid;
			return this;
		}

		/** Signs with another private key while still advertising this holder's kid (a forgery attempt). */
		public TokenBuilder signedWith(TestKeys other) {
			this.signingKey = other.key;
			return this;
		}

		public TokenBuilder signing(Signing signing) {
			this.signing = signing;
			return this;
		}

		private String rsaSigned(JWTClaimsSet claimsSet, JWSAlgorithm algorithm) throws JOSEException {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(algorithm).keyID(this.kid).type(JOSEObjectType.JWT).build(), claimsSet);
			jwt.sign(new RSASSASigner(this.signingKey.toPrivateKey()));
			return jwt.serialize();
		}

		public String build() {
			// authService now issues phone_number, not username; kept purely for fixture realism, the gateway
			// itself never reads this claim (only sub/roles).
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().jwtID(UUID.randomUUID().toString())
				.claim("phone_number", "+911234567890")
				.issueTime(Date.from(this.issuedAt));
			if (this.issuer != null) {
				claims.issuer(this.issuer);
			}
			if (this.subject != null) {
				claims.subject(this.subject);
			}
			if (this.expiresAt != null) {
				claims.expirationTime(Date.from(this.expiresAt));
			}
			if (this.rolesClaimOverridden) {
				if (this.rolesClaim != null) {
					claims.claim("roles", this.rolesClaim);
				}
			}
			else if (this.roles != null) {
				claims.claim("roles", this.roles);
			}
			JWTClaimsSet claimsSet = claims.build();
			try {
				return switch (this.signing) {
					case RS256 -> rsaSigned(claimsSet, JWSAlgorithm.RS256);
					case RS384 -> rsaSigned(claimsSet, JWSAlgorithm.RS384);
					case RS512 -> rsaSigned(claimsSet, JWSAlgorithm.RS512);
					case PS256 -> rsaSigned(claimsSet, JWSAlgorithm.PS256);
					case HS256 -> {
						// Algorithm confusion attempt: HMAC keyed with public, guessable material.
						byte[] secret = this.keys.key.toPublicJWK().toRSAPublicKey().getEncoded();
						SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(this.kid).build(),
								claimsSet);
						jwt.sign(new MACSigner(secret));
						yield jwt.serialize();
					}
					case NONE -> new PlainJWT(claimsSet).serialize();
				};
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}
}
