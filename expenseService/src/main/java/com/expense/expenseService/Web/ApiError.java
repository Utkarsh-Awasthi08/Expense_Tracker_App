package com.expense.expenseService.Web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The pinned error body used by every non-2xx JSON response of every service:
 * {@code {"timestamp","status","error","code","message","path"}} plus {@code details[]} for VALIDATION_FAILED.
 * The timestamp is an ISO-8601 UTC instant kept as text so the body renders identically whether it is written by
 * the MVC message converters or directly by a servlet filter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String timestamp, int status, String error, String code, String message, String path,
                       List<FieldIssue> details) {

    public static ApiError of(int status, ErrorCode code, String message, String path, List<FieldIssue> details) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return new ApiError(
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                status,
                resolved != null ? resolved.getReasonPhrase() : "Error",
                code.name(),
                message,
                path,
                details == null || details.isEmpty() ? null : List.copyOf(details));
    }

    public static ApiError of(int status, ErrorCode code, String message, String path) {
        return of(status, code, message, path, null);
    }

    /** The pinned code for a status that has no more specific classification. */
    public static ErrorCode codeFor(int status) {
        return switch (status) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            default -> status >= 500 ? ErrorCode.INTERNAL : ErrorCode.BAD_REQUEST;
        };
    }

    /** A message that is safe to show: fixed text per status, never derived from the exception. */
    public static String safeMessage(int status) {
        return switch (status) {
            case 400 -> "The request is invalid";
            case 401 -> "Authentication is required";
            case 403 -> "Access is denied";
            case 404 -> "Resource not found";
            case 405 -> "HTTP method not supported for this resource";
            case 406 -> "The requested media type is not supported";
            case 409 -> "The request conflicts with the current state";
            case 415 -> "The request content type is not supported";
            default -> status >= 500 ? "An unexpected error occurred" : "The request could not be processed";
        };
    }
}
