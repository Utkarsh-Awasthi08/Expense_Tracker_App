package com.expense.gateway.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client over a plain socket. HTTP client libraries normalise or reject the request lines and header
 * spellings the path-trick and header-forgery tests need, so this sends the bytes exactly as given.
 */
public final class RawHttp {

	public record Response(int status, List<Map.Entry<String, String>> headers, String body) {

		public String header(String name) {
			for (Map.Entry<String, String> header : this.headers) {
				if (header.getKey().equalsIgnoreCase(name)) {
					return header.getValue();
				}
			}
			return null;
		}
	}

	private RawHttp() {
	}

	/**
	 * @param requestTarget the exact request-target, e.g. {@code /auth/v1/%6cogin}
	 * @param headerLines complete header lines without the CRLF, e.g. {@code "x-user-id: 1"}; duplicates allowed
	 */
	public static Response send(int port, String method, String requestTarget, List<String> headerLines, String body) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			socket.setSoTimeout(15000);
			StringBuilder request = new StringBuilder();
			request.append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
			request.append("Host: 127.0.0.1:").append(port).append("\r\n");
			request.append("Connection: close\r\n");
			for (String line : headerLines) {
				request.append(line).append("\r\n");
			}
			byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			if (body != null) {
				request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
			}
			request.append("\r\n");
			// One single write: when the gateway answers (401/400) straight after the headers and closes, a body that
			// arrives afterwards on the closed socket would make the OS reset the connection and drop the response.
			byte[] head = request.toString().getBytes(StandardCharsets.ISO_8859_1);
			byte[] message = new byte[head.length + bodyBytes.length];
			System.arraycopy(head, 0, message, 0, head.length);
			System.arraycopy(bodyBytes, 0, message, head.length, bodyBytes.length);
			socket.getOutputStream().write(message);
			socket.getOutputStream().flush();
			return parse(socket.getInputStream());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static Response send(int port, String method, String requestTarget, String... headerLines) {
		return send(port, method, requestTarget, List.of(headerLines), null);
	}

	private static Response parse(InputStream in) throws IOException {
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		in.transferTo(all);
		String raw = all.toString(StandardCharsets.UTF_8);
		int headerEnd = raw.indexOf("\r\n\r\n");
		if (headerEnd < 0) {
			throw new IllegalStateException("no complete HTTP response received: [" + raw + "]");
		}
		String[] lines = raw.substring(0, headerEnd).split("\r\n");
		int status = Integer.parseInt(lines[0].split(" ")[1]);
		List<Map.Entry<String, String>> headers = new ArrayList<>();
		boolean chunked = false;
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			String name = lines[i].substring(0, colon).trim();
			String value = lines[i].substring(colon + 1).trim();
			headers.add(Map.entry(name, value));
			if (name.equalsIgnoreCase("Transfer-Encoding") && value.equalsIgnoreCase("chunked")) {
				chunked = true;
			}
		}
		String body = raw.substring(headerEnd + 4);
		return new Response(status, headers, chunked ? dechunk(body) : body);
	}

	private static String dechunk(String body) {
		StringBuilder out = new StringBuilder();
		int position = 0;
		while (position < body.length()) {
			int lineEnd = body.indexOf("\r\n", position);
			if (lineEnd < 0) {
				break;
			}
			int size = Integer.parseInt(body.substring(position, lineEnd).trim(), 16);
			if (size == 0) {
				break;
			}
			out.append(body, lineEnd + 2, lineEnd + 2 + size);
			position = lineEnd + 2 + size + 2;
		}
		return out.toString();
	}
}
