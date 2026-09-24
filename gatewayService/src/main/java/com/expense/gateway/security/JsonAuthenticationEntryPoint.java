package com.expense.gateway.security;

import com.expense.gateway.web.ErrorResponseWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 401 + {@code WWW-Authenticate: Bearer} + the pinned JSON body for a missing, malformed, expired or otherwise
 * invalid token. The message is fixed so that it never reveals why validation failed.
 *
 * <p>An authentication <em>service</em> failure (JWKS unreachable) is not an authentication failure of the caller: Spring
 * Security rethrows it and it is answered with 503 by {@link com.expense.gateway.web.GatewayErrorHandler}.
 */
@Component
public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

	private final ErrorResponseWriter writer;

	public JsonAuthenticationEntryPoint(ErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
		return this.writer.write(exchange, HttpStatus.UNAUTHORIZED, ErrorResponseWriter.UNAUTHORIZED,
				"Authentication is required to access this resource");
	}
}
