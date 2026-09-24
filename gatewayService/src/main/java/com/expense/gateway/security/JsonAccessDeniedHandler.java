package com.expense.gateway.security;

import com.expense.gateway.web.ErrorResponseWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 403 with the pinned JSON body for an authenticated caller that is not allowed to do something. */
@Component
public class JsonAccessDeniedHandler implements ServerAccessDeniedHandler {

	private final ErrorResponseWriter writer;

	public JsonAccessDeniedHandler(ErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
		return writer.write(exchange, HttpStatus.FORBIDDEN, ErrorResponseWriter.FORBIDDEN,
				"You do not have permission to access this resource");
	}
}
