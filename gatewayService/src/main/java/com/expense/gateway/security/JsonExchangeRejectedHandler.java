package com.expense.gateway.security;

import com.expense.gateway.web.ErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedException;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Spring Security's strict WebFlux firewall runs before every filter of the chain and rejects request targets that a
 * backend could interpret differently from the gateway: {@code //}, {@code ;}, backslash, NUL, {@code ..} and {@code .}
 * segments, and encoded forms of slash, period, backslash, semicolon or percent. Such a request is answered with a 400 in
 * the pinned JSON error shape (the default handler sends an empty body) and is never routed, authenticated or not.
 */
@Component
public class JsonExchangeRejectedHandler implements ServerExchangeRejectedHandler {

	private static final Logger log = LoggerFactory.getLogger(JsonExchangeRejectedHandler.class);

	private final ErrorResponseWriter writer;

	public JsonExchangeRejectedHandler(ErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, ServerExchangeRejectedException rejected) {
		log.debug("Rejected request target: {}", rejected.getMessage());
		return this.writer.write(exchange, HttpStatus.BAD_REQUEST, ErrorResponseWriter.BAD_REQUEST,
				"The request path is not allowed");
	}
}
