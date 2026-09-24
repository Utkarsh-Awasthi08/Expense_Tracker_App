package com.expense.gateway.security;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Builds the one and only token verifier. It is a static factory so the tests exercise exactly the production
 * algorithm pin and validators.
 *
 * <ul>
 *   <li>Signature: RS256 only, keys from the JWKS endpoint. {@code alg=none} and HS256 (algorithm confusion) are rejected.</li>
 *   <li>Validators: {@code exp}/{@code nbf} (default 60 s skew), {@code iss} equals the configured issuer, plus
 *       {@code exp} must be present and {@code sub} must be a UUID (the contract; it becomes {@code X-User-Id}).</li>
 *   <li>No {@code issuer-uri}: that would trigger OIDC discovery at startup and prevent booting while authService is down.
 *       The JWKS is fetched lazily on the first token and refreshed once when a token carries an unknown {@code kid}.</li>
 * </ul>
 */
public final class JwtDecoderFactory {

	private static final Pattern UUID_PATTERN = Pattern
		.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	private static final Duration JWKS_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

	private JwtDecoderFactory() {
	}

	public static ReactiveJwtDecoder create(String jwksUri, String issuer) {
		// Bounded JWKS calls: a hung authService must not hang every request that carries a token.
		HttpClient httpClient = HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) JWKS_CONNECT_TIMEOUT.toMillis())
			.responseTimeout(JWKS_RESPONSE_TIMEOUT);
		WebClient webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();

		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri)
			.jwsAlgorithm(SignatureAlgorithm.RS256)
			.webClient(webClient)
			.build();
		decoder.setJwtValidator(validator(issuer));
		// NimbusReactiveJwtDecoder lets an IllegalStateException ("Could not obtain the keys") escape when the JWKS
		// endpoint cannot be reached. Turn every non-JWT failure into a plain JwtException (not a BadJwtException) so
		// the resource server reports an authentication *service* failure, which the entry point answers with 503
		// instead of a misleading 401 or a generic upstream 502/504.
		return (token) -> Mono.defer(() -> decoder.decode(token))
			.onErrorMap((ex) -> !(ex instanceof JwtException),
					(ex) -> new JwtException("The token could not be verified", ex));
	}

	static OAuth2TokenValidator<Jwt> validator(String issuer) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				JwtDecoderFactory::requireExpiryAndUuidSubject);
	}

	private static OAuth2TokenValidatorResult requireExpiryAndUuidSubject(Jwt jwt) {
		if (jwt.getExpiresAt() == null) {
			return failure("The token has no expiry");
		}
		String subject = jwt.getSubject();
		if (subject == null || !UUID_PATTERN.matcher(subject).matches()) {
			return failure("The token subject is not a user id");
		}
		return OAuth2TokenValidatorResult.success();
	}

	private static OAuth2TokenValidatorResult failure(String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
	}
}
