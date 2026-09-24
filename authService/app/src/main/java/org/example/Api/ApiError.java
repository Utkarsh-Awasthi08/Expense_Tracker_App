package org.example.Api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The error body used for every non-2xx JSON response (see docs/CONTRACTS.md). The message is always safe to show:
 * never a stack trace, SQL or a value taken from the request. {@code retryAfterSeconds} is only ever set for a 429
 * (RATE_LIMITED / COOLDOWN_ACTIVE on the OTP endpoints); it is omitted everywhere else.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<Detail> details,
        @JsonProperty("retry_after_seconds") Long retryAfterSeconds) {

    public record Detail(String field, String message) {
    }

    public static ApiError of(HttpStatus status, ErrorCode code, String message, String path, List<Detail> details) {
        return of(status, code, message, path, details, null);
    }

    public static ApiError of(HttpStatus status, ErrorCode code, String message, String path, List<Detail> details,
                              Long retryAfterSeconds) {
        return new ApiError(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS)),
                status.value(),
                status.getReasonPhrase(),
                code.name(),
                message,
                path,
                details,
                retryAfterSeconds);
    }

    /** Writes the error straight to the servlet response (used by the security filters, before MVC is reached). */
    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, ErrorCode code, String message) throws IOException {
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), of(status, code, message, request.getRequestURI(), null));
    }
}
