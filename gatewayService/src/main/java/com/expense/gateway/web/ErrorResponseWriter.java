package com.expense.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the pinned error body used by every service (docs/CONTRACTS.md):
 * {"timestamp","status","error","code","message","path"}. 401 also carries {@code WWW-Authenticate: Bearer}.
 * Messages are fixed, safe strings chosen by the caller - never exception text.
 */
@Component
public class ErrorResponseWriter {

	public static final String UNAUTHORIZED = "UNAUTHORIZED";
	public static final String FORBIDDEN = "FORBIDDEN";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String CONFLICT = "CONFLICT";
	public static final String BAD_REQUEST = "BAD_REQUEST";
	public static final String INTERNAL = "INTERNAL";

	private static final byte[] FALLBACK = ("{\"status\":500,\"error\":\"Internal Server Error\","
			+ "\"code\":\"INTERNAL\",\"message\":\"Internal server error\"}").getBytes(StandardCharsets.UTF_8);

	private final ObjectMapper objectMapper;

	public ErrorResponseWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** Returns the error body for {@code status}; the response is only written by {@link #write}. */
	public Map<String, Object> body(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
		body.put("status", status.value());
		body.put("error", reasonPhrase(status));
		body.put("code", code);
		body.put("message", message);
		body.put("path", exchange.getRequest().getPath().value());
		return body;
	}

	public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
		ServerHttpResponse response = exchange.getResponse();
		if (response.isCommitted()) {
			return Mono.empty();
		}
		byte[] bytes;
		try {
			bytes = objectMapper.writeValueAsBytes(body(exchange, status, code, message));
		}
		catch (JsonProcessingException ex) {
			bytes = FALLBACK;
		}
		response.setStatusCode(status);
		HttpHeaders headers = response.getHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setContentLength(bytes.length);
		if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
			headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Mono.just(buffer));
	}

	private static String reasonPhrase(HttpStatusCode status) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		return resolved != null ? resolved.getReasonPhrase() : "Error";
	}
}
