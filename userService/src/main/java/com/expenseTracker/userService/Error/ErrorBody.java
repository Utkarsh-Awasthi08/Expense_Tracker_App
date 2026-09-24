package com.expenseTracker.userService.Error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The pinned error body for every non-2xx JSON response:
 * {"timestamp","status","error","code","message","path"} plus "details" for VALIDATION_FAILED.
 * The message is always a fixed, safe sentence: never an exception message, SQL or stack trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(
        String timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldDetail> details) {

    public record FieldDetail(String field, String message) {
    }

    public static ErrorBody of(HttpStatus status, ErrorCode code, String message, String path) {
        return of(status, code, message, path, null);
    }

    public static ErrorBody of(HttpStatus status, ErrorCode code, String message, String path,
                               List<FieldDetail> details) {
        return new ErrorBody(
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                status.value(),
                status.getReasonPhrase(),
                code.name(),
                message,
                path,
                details);
    }

    /** Generic, non-leaking message for framework-generated errors. */
    public static String safeMessage(int status) {
        return switch (status) {
            case 400 -> "The request is invalid";
            case 401 -> "Authentication is required";
            case 403 -> "Access is denied";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 409 -> "Conflict";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            default -> status >= 500 ? "Internal server error" : "The request could not be processed";
        };
    }
}
