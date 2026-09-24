package com.expense.gateway.filter;

import com.expense.gateway.web.ErrorResponseWriter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Second line of defence behind Spring Security's strict WebFlux firewall (see JsonExchangeRejectedHandler): rejects (400)
 * any routed request whose raw path contains dot segments ({@code /.}, {@code /..}, also as {@code %2e}), encoded slashes
 * or backslashes, {@code ;} path parameters, NUL, or percent-encoded bytes that can never occur in valid UTF-8
 * ({@code %c0}, {@code %c1}: overlong lead bytes as in {@code %c0%af}, an "overlong" slash; {@code %f5} to {@code %ff}).
 * A decoder that is lenient about them (some replace, some accept) could read such a path as something else than the
 * router did.
 *
 * <p>The router matches {@code /user/v1/**} on the still-unnormalised path and forwards the raw path unchanged, so
 * {@code /user/v1/../../actuator/env} would be routed to userService, whose servlet container then normalises it to a
 * path outside the prefix the gateway meant to expose. The firewall already refuses these paths today; this filter keeps
 * that guarantee if the firewall is ever replaced or loosened. Unauthenticated callers never get this far: they are
 * answered with 401 by the security chain because {@link com.expense.gateway.security.PublicRoutes} only accepts exact
 * literals.
 */
@Component
public class RequestPathGuardFilter implements GlobalFilter, Ordered {

	/** Lead bytes UTF-8 forbids: C0 and C1 (overlong two-byte forms) and F5 to FF (beyond U+10FFFF); any case. */
	private static final Pattern INVALID_UTF8_LEAD_BYTE = Pattern.compile("%(?:c[01]|f[5-9a-f])");

	private final ErrorResponseWriter writer;

	public RequestPathGuardFilter(ErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		if (isSuspicious(exchange.getRequest().getURI().getRawPath())) {
			return this.writer.write(exchange, HttpStatus.BAD_REQUEST, ErrorResponseWriter.BAD_REQUEST,
					"The request path is not allowed");
		}
		return chain.filter(exchange);
	}

	public static boolean isSuspicious(String rawPath) {
		if (rawPath == null) {
			return true;
		}
		String path = rawPath.toLowerCase(Locale.ROOT);
		if (path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("%2f") || path.contains("%5c")
				|| path.contains("%00") || path.contains("%3b") || INVALID_UTF8_LEAD_BYTE.matcher(path).find()) {
			return true;
		}
		for (String segment : path.replace("%2e", ".").split("/", -1)) {
			if (segment.equals(".") || segment.equals("..")) {
				return true;
			}
		}
		return false;
	}
}
