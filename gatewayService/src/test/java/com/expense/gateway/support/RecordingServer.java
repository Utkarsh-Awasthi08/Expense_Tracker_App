package com.expense.gateway.support;

import io.netty.handler.codec.http.HttpHeaders;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A tiny stub HTTP server (reactor-netty, already on the classpath) that records exactly what it receives:
 * the raw request target and every header line as sent, duplicates and original spelling included.
 */
public final class RecordingServer implements AutoCloseable {

	public record Received(String method, String uri, List<Map.Entry<String, String>> headers, String body) {

		/** Path part of the raw request target, still percent-encoded. */
		public String path() {
			int q = this.uri.indexOf('?');
			return q < 0 ? this.uri : this.uri.substring(0, q);
		}

		public String query() {
			int q = this.uri.indexOf('?');
			return q < 0 ? null : this.uri.substring(q + 1);
		}

		/** All values of the header, matched case-insensitively. */
		public List<String> header(String name) {
			List<String> values = new ArrayList<>();
			for (Map.Entry<String, String> header : this.headers) {
				if (header.getKey().equalsIgnoreCase(name)) {
					values.add(header.getValue());
				}
			}
			return values;
		}

		public String firstHeader(String name) {
			List<String> values = header(name);
			return values.isEmpty() ? null : values.get(0);
		}

		public boolean hasHeader(String name) {
			return !header(name).isEmpty();
		}

		/** Names of the header lines as they were spelled on the wire. */
		public List<String> headerNames(String name) {
			List<String> names = new ArrayList<>();
			for (Map.Entry<String, String> header : this.headers) {
				if (header.getKey().equalsIgnoreCase(name)) {
					names.add(header.getKey());
				}
			}
			return names;
		}
	}

	public record Reply(int status, String contentType, String body, Duration delay, boolean dropConnection) {

		public static Reply ok() {
			return json(200, "{\"ok\":true}");
		}

		public static Reply json(int status, String body) {
			return new Reply(status, "application/json", body, Duration.ZERO, false);
		}

		public static Reply noContent() {
			return new Reply(204, null, "", Duration.ZERO, false);
		}

		public static Reply slow(Duration delay) {
			return new Reply(200, "application/json", "{\"ok\":true}", delay, false);
		}

		/** Closes the TCP connection without answering. */
		public static Reply drop() {
			return new Reply(200, null, "", Duration.ZERO, true);
		}
	}

	private static final Reply DEFAULT_REPLY = Reply.ok();

	private final List<Received> requests = new CopyOnWriteArrayList<>();

	private volatile Function<Received, Reply> handler = (request) -> DEFAULT_REPLY;

	private final DisposableServer server;

	private RecordingServer(int port) {
		this.server = HttpServer.create()
			.host("127.0.0.1")
			.port(port)
			.handle((request, response) -> request.receive()
				.aggregate()
				.asString(StandardCharsets.UTF_8)
				.defaultIfEmpty("")
				.flatMap((body) -> {
					Received received = new Received(request.method().name(), request.uri(),
							copyOf(request.requestHeaders()), body);
					this.requests.add(received);
					Reply reply = this.handler.apply(received);
					if (reply.dropConnection()) {
						return Mono.<Void>fromRunnable(() -> request.withConnection((connection) -> connection.channel().close()));
					}
					response.status(reply.status());
					if (reply.contentType() != null) {
						response.header("Content-Type", reply.contentType());
					}
					Mono<Void> send = reply.body().isEmpty() ? response.send()
							: response.sendString(Mono.just(reply.body()), StandardCharsets.UTF_8).then();
					return reply.delay().isZero() ? send : Mono.delay(reply.delay()).then(send);
				}))
			.bindNow();
	}

	private static List<Map.Entry<String, String>> copyOf(HttpHeaders headers) {
		List<Map.Entry<String, String>> copy = new ArrayList<>();
		headers.forEach((entry) -> copy.add(Map.entry(entry.getKey(), entry.getValue())));
		return copy;
	}

	public static RecordingServer start() {
		return new RecordingServer(0);
	}

	public static RecordingServer start(int port) {
		return new RecordingServer(port);
	}

	/** A port that is free right now; nothing is listening on it, so connections are refused until a server binds it. */
	public static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public int port() {
		return this.server.port();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + port();
	}

	public List<Received> requests() {
		return List.copyOf(this.requests);
	}

	public Received only() {
		if (this.requests.size() != 1) {
			throw new AssertionError("expected exactly one request but the stub received " + this.requests.size() + ": "
					+ this.requests);
		}
		return this.requests.get(0);
	}

	public void handler(Function<Received, Reply> handler) {
		this.handler = handler;
	}

	public void reply(Reply reply) {
		this.handler = (request) -> reply;
	}

	public void reset() {
		this.requests.clear();
		this.handler = (request) -> DEFAULT_REPLY;
	}

	@Override
	public void close() {
		this.server.disposeNow();
	}

	@Override
	public String toString() {
		return "RecordingServer[" + baseUrl().toLowerCase(Locale.ROOT) + "]";
	}
}
