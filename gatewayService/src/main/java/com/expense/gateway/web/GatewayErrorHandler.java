package com.expense.gateway.web;

import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global error handler: every failure that escapes the filter chain (no matching route, upstream down or slow,
 * unexpected bug) becomes the pinned JSON error body. Upstream failures map to 502/504 and an unverifiable token (JWKS
 * unreachable) to 503, all with code INTERNAL.
 * The response never contains exception text, hosts, ports or stack traces; those go to the log only.
 */
@Component
@Order(-2) // ahead of Spring Boot's DefaultErrorWebExceptionHandler (-1) and WebFluxResponseStatusExceptionHandler (0)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

	private final ErrorResponseWriter writer;

	public GatewayErrorHandler(ErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (exchange.getResponse().isCommitted()) {
			// Too late to send a JSON body (typically a client abort mid-stream); let the framework close the connection.
			return Mono.error(ex);
		}
		HttpStatusCode status = statusOf(ex);
		String path = exchange.getRequest().getPath().value();
		if (status.is5xxServerError() && status.value() != HttpStatus.BAD_GATEWAY.value()
				&& status.value() != HttpStatus.GATEWAY_TIMEOUT.value()
				&& status.value() != HttpStatus.SERVICE_UNAVAILABLE.value()) {
			log.error("Unhandled error for {} {}", exchange.getRequest().getMethod(), path, ex);
		}
		else if (status.is5xxServerError()) {
			log.warn("Upstream failure {} for {} {}: {}", status.value(), exchange.getRequest().getMethod(), path,
					summarize(ex));
		}
		else {
			log.debug("Request failed {} for {} {}: {}", status.value(), exchange.getRequest().getMethod(), path,
					summarize(ex));
		}
		return writer.write(exchange, status, codeFor(status), messageFor(status));
	}

	static HttpStatusCode statusOf(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof ResponseStatusException rse) {
				return rse.getStatusCode();
			}
			if (t instanceof AuthenticationServiceException) {
				// The resource server could not verify a token at all (typically the JWKS endpoint of authService is
				// unreachable). The caller is not at fault, so this is 503 rather than 401: clients must not drop the session.
				return HttpStatus.SERVICE_UNAVAILABLE;
			}
			if (t instanceof TimeoutException || t instanceof ConnectTimeoutException
					|| t instanceof io.netty.handler.timeout.TimeoutException
					|| t instanceof java.net.SocketTimeoutException) {
				return HttpStatus.GATEWAY_TIMEOUT;
			}
		}
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			// ConnectException, UnknownHostException, ClosedChannelException, PrematureCloseException, resets ...
			if (t instanceof IOException) {
				return HttpStatus.BAD_GATEWAY;
			}
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	static String codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 401 -> ErrorResponseWriter.UNAUTHORIZED;
			case 403 -> ErrorResponseWriter.FORBIDDEN;
			case 404 -> ErrorResponseWriter.NOT_FOUND;
			case 409 -> ErrorResponseWriter.CONFLICT;
			default -> status.is5xxServerError() ? ErrorResponseWriter.INTERNAL : ErrorResponseWriter.BAD_REQUEST;
		};
	}

	static String messageFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 401 -> "Authentication is required";
			case 403 -> "Access is denied";
			case 404 -> "Resource not found";
			case 409 -> "Request conflicts with the current state";
			case 502 -> "The upstream service is unavailable";
			case 503 -> "The service is temporarily unavailable";
			case 504 -> "The upstream service did not respond in time";
			default -> status.is5xxServerError() ? "Internal server error" : "Bad request";
		};
	}

	private static String summarize(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getClass().getSimpleName();
	}
}
