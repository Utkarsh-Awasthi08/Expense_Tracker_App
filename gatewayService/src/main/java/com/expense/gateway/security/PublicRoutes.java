package com.expense.gateway.security;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The complete list of unauthenticated endpoints, matched against the RAW (still percent-encoded) request path
 * with exact string equality. It is deliberately not a PathPattern/Ant matcher:
 *
 * <ul>
 *   <li>a trailing slash, {@code //}, {@code ;x=1} matrix parameters, {@code /./}, {@code /../}, percent-encoding
 *       ({@code %6cogin}, {@code ..%2f}) or a different case can never be public, because none of them is
 *       byte-for-byte one of the literals below;</li>
 *   <li>the route predicates decode and normalise more liberally than this matcher, so any request the router
 *       accepts but this matcher does not fails safe with 401;</li>
 *   <li>the HTTP method is checked as well ({@code X-HTTP-Method-Override} is stripped by IdentityHeadersFilter).</li>
 * </ul>
 */
public final class PublicRoutes {

	/**
	 * Both generations of the unauthenticated auth entry points are public together: {@code signup}/{@code login}
	 * (the currently pinned contract in {@code docs/CONTRACTS.md}, and the only ones authService actually exposes
	 * today — it has no {@code @PostMapping} for {@code otp/request} or {@code otp/verify} yet, and its OTP
	 * migration would also drop the {@code users.username}/{@code users.password} columns signup/login still use)
	 * plus {@code otp/request}/{@code otp/verify} (already wired here so no further gateway change is needed once
	 * authService ships its half). Removing signup/login before authService cuts over would leave no working
	 * unauthenticated login path at all — login itself would then require a bearer token nobody could ever obtain.
	 * Keep both sets public until authService ships the OTP controllers and a compatible migration, and the two
	 * changes are coordinated; then this set shrinks back down to the OTP paths (+ refreshToken/logout) in a
	 * follow-up change alongside authService's own CONTRACTS.md update.
	 */
	static final Set<String> AUTH_POST_PATHS = Set.of("/auth/v1/signup", "/auth/v1/login", "/auth/v1/otp/request",
			"/auth/v1/otp/verify", "/auth/v1/refreshToken", "/auth/v1/logout");

	/** /actuator/health and health groups such as /actuator/health/liveness: plain segments only, no encoding. */
	private static final Pattern HEALTH_PATH = Pattern.compile("^/actuator/health(/[A-Za-z0-9_-]+)*$");

	private PublicRoutes() {
	}

	public static ServerWebExchangeMatcher matcher() {
		return PublicRoutes::match;
	}

	private static Mono<MatchResult> match(ServerWebExchange exchange) {
		HttpMethod method = exchange.getRequest().getMethod();
		String rawPath = exchange.getRequest().getURI().getRawPath();
		return isPublic(method, rawPath) ? MatchResult.match() : MatchResult.notMatch();
	}

	public static boolean isPublic(HttpMethod method, String rawPath) {
		if (rawPath == null) {
			return false;
		}
		if (HttpMethod.POST.equals(method)) {
			return AUTH_POST_PATHS.contains(rawPath);
		}
		if (HttpMethod.GET.equals(method)) {
			return HEALTH_PATH.matcher(rawPath).matches();
		}
		return false;
	}
}
