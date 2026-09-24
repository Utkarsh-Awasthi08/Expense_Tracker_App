package com.expense.gateway.filter;

import com.expense.gateway.security.PublicRoutes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The trust boundary of the platform. Backend services trust {@code X-User-Id} / {@code X-User-Roles} because only this
 * filter can put them on a request that reaches them (their ports are not published).
 *
 * <p>For EVERY routed request, public routes and unauthenticated requests included, it first removes whatever the
 * client sent: all values of each header in {@link #STRIPPED_HEADERS}, matched case-insensitively and with {@code _}
 * treated as {@code -} (so {@code x_user_id}, {@code X-User_Id} and {@code REMOTE_USER} are gone too: a backend that
 * maps underscores to hyphens, as CGI-style servers do, must not be able to read a look-alike as the identity). Only
 * then, when the request was authenticated with a JWT, it sets {@code X-User-Id} to the token {@code sub} and
 * {@code X-User-Roles} to the comma-joined {@code roles} claim (omitted when the token has none).
 *
 * <p>The {@code Authorization} header is handled as follows:
 * <ul>
 *   <li>authenticated request: exactly one {@code Authorization} header is forwarded, the first one, which is the one
 *       the resource server authenticated (a second header would let a backend that reads the last or all values see
 *       a different, unverified credential);</li>
 *   <li>public route ({@link PublicRoutes}) or any request without a verified JWT: the header is removed. No bearer
 *       token is verified on a public route (a stale token must not turn a signup, login, OTP request/verify, a
 *       refresh or a logout into a 401), so it is not forwarded either, or authService's own resource server would
 *       reject the stale token.</li>
 * </ul>
 *
 * <p>Also stripped, because they are the same kind of client-controlled "who is this / what is this" input that a
 * backend framework or proxy convention might honour:
 * <ul>
 *   <li>{@code X-User}, {@code X-UserId}, {@code X-Auth-User}, {@code Remote-User}, {@code X-Remote-User},
 *       {@code X-Forwarded-User}, {@code X-Authenticated-User}: common identity headers set by reverse proxies and read
 *       by servlet containers or frameworks;</li>
 *   <li>{@code X-HTTP-Method-Override}, {@code X-HTTP-Method}, {@code X-Method-Override}: let a client turn the verb it
 *       was authorised for (for example a public POST) into another one inside the backend.</li>
 * </ul>
 * {@code X-Forwarded-*} and {@code Forwarded} are left to Spring Cloud Gateway's own forwarded-header filters.
 *
 * <p>Runs first (before route filters such as RewritePath and before the Netty routing filter). Reactor's request
 * headers are read-only, so the headers are changed through {@code request.mutate().headers(...)}.
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

	public static final String USER_ID_HEADER = "X-User-Id";

	public static final String USER_ROLES_HEADER = "X-User-Roles";

	/**
	 * Removed from every request. Compared through {@link #normalise(String)}, so each entry stands for all its case
	 * and {@code _} / {@code -} spellings.
	 */
	static final List<String> STRIPPED_HEADERS = List.of(USER_ID_HEADER, USER_ROLES_HEADER, "Remote-User", "X-User",
			"X-UserId", "X-Auth-User", "X-Remote-User", "X-Forwarded-User", "X-Authenticated-User",
			"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override");

	private static final Set<String> STRIPPED_NAMES = STRIPPED_HEADERS.stream()
		.map(IdentityHeadersFilter::normalise)
		.collect(Collectors.toUnmodifiableSet());

	/** A role that is not a plain token cannot be a real role and must not be able to break the comma list or the header. */
	private static final Pattern SAFE_ROLE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return ReactiveSecurityContextHolder.getContext()
			.flatMap((context) -> Mono.justOrEmpty(context.getAuthentication()))
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.flatMap((authentication) -> chain.filter(withIdentity(exchange, authentication.orElse(null))));
	}

	private static ServerWebExchange withIdentity(ServerWebExchange exchange, Authentication authentication) {
		ServerHttpRequest original = exchange.getRequest();
		// A public route is never authenticated (no bearer token is read there), whatever the security context says.
		boolean publicRoute = PublicRoutes.isPublic(original.getMethod(), original.getURI().getRawPath());
		Jwt jwt = (!publicRoute && authentication instanceof JwtAuthenticationToken token && token.isAuthenticated())
				? token.getToken() : null;
		ServerHttpRequest request = original.mutate().headers((headers) -> {
			stripIdentityHeaders(headers);
			if (jwt == null) {
				// Nothing verified this credential (public route), so it is not forwarded.
				headers.remove(HttpHeaders.AUTHORIZATION);
			}
			else {
				keepOnlyTheFirstAuthorization(headers);
				applyIdentity(headers, jwt);
			}
		}).build();
		return exchange.mutate().request(request).build();
	}

	/** Lower case with {@code _} read as {@code -}: the spellings a backend could still map onto the same header. */
	static String normalise(String headerName) {
		return headerName.toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private static void stripIdentityHeaders(HttpHeaders headers) {
		// Copy the names first: removing while iterating the live key set is not safe on every HttpHeaders backing map.
		for (String name : new ArrayList<>(headers.keySet())) {
			if (STRIPPED_NAMES.contains(normalise(name))) {
				headers.remove(name);
			}
		}
	}

	/** The first value is the one the bearer converter authenticated; drop every other Authorization line/value. */
	private static void keepOnlyTheFirstAuthorization(HttpHeaders headers) {
		List<String> values = headers.get(HttpHeaders.AUTHORIZATION);
		if (values != null && values.size() > 1) {
			String first = values.get(0);
			headers.remove(HttpHeaders.AUTHORIZATION);
			headers.set(HttpHeaders.AUTHORIZATION, first);
		}
	}

	private static void applyIdentity(HttpHeaders headers, Jwt jwt) {
		headers.set(USER_ID_HEADER, jwt.getSubject());
		List<String> roles = rolesOf(jwt);
		if (!roles.isEmpty()) {
			headers.set(USER_ROLES_HEADER, String.join(",", roles));
		}
	}

	private static List<String> rolesOf(Jwt jwt) {
		Object claim = jwt.getClaim("roles");
		if (claim instanceof String single) {
			claim = List.of(single);
		}
		if (!(claim instanceof Iterable<?> values)) {
			return List.of();
		}
		List<String> roles = new ArrayList<>();
		for (Object value : values) {
			if (value instanceof String role && SAFE_ROLE.matcher(role).matches()) {
				roles.add(role);
			}
		}
		return roles;
	}
}
