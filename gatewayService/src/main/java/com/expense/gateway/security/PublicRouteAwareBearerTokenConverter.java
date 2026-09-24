package com.expense.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the bearer token like the stock resource-server converter, except on the public routes
 * ({@link PublicRoutes}), where it never looks at the request at all.
 *
 * <p>Why: a mobile client keeps sending its (possibly expired) access token to {@code /auth/v1/signup},
 * {@code /auth/v1/login}, {@code /auth/v1/otp/request}, {@code /auth/v1/otp/verify}, {@code /auth/v1/refreshToken}
 * and {@code /auth/v1/logout}. Without this, the resource
 * server would decode that stale token before the "permit all"
 * decision is made and answer 401, so the client could never refresh. A public route is by definition one that does not
 * need authentication, so no authentication is attempted for it: whatever {@code Authorization} header it carries is
 * neither validated nor trusted (and {@link com.expense.gateway.filter.IdentityHeadersFilter} removes it before the
 * request is forwarded, so authService's own resource server does not reject the stale token either).
 *
 * <p>Only the exact literals of {@link PublicRoutes#isPublic} are skipped; every other exchange, including near misses
 * such as a trailing slash, {@code //}, {@code ;}, percent-encoding or another case, keeps strict authentication.
 */
public final class PublicRouteAwareBearerTokenConverter implements ServerAuthenticationConverter {

	private final ServerAuthenticationConverter delegate;

	public PublicRouteAwareBearerTokenConverter() {
		this(new ServerBearerTokenAuthenticationConverter());
	}

	PublicRouteAwareBearerTokenConverter(ServerAuthenticationConverter delegate) {
		this.delegate = delegate;
	}

	@Override
	public Mono<Authentication> convert(ServerWebExchange exchange) {
		if (PublicRoutes.isPublic(exchange.getRequest().getMethod(), exchange.getRequest().getURI().getRawPath())) {
			return Mono.empty();
		}
		return this.delegate.convert(exchange);
	}
}
