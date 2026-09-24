package org.example.Api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;

/**
 * Turns every exception into the pinned error body. Messages are fixed strings: exception messages, stack traces
 * and rejected values (which may be an OTP code) are never sent to the client, and request bodies are never logged.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.Detail(toSnakeCase(e.getField()), e.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.Detail::field).thenComparing(ApiError.Detail::message))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                path(request), details, null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Malformed or missing JSON request body",
                path(request), null, null);
    }

    /** Fallback for the framework's own exceptions (404, 405, 415, missing parameters, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = statusCode.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("Framework exception ({}) on {}", ex.getClass().getSimpleName(), path(request), ex);
        }
        return respond(status, ErrorCode.forStatus(status.value()), safeMessage(status), path(request), null, headers);
    }

    @ExceptionHandler(InvalidPhoneNumberException.class)
    ResponseEntity<Object> handleInvalidPhoneNumber(InvalidPhoneNumberException ex, HttpServletRequest request) {
        List<ApiError.Detail> details = List.of(new ApiError.Detail("phone_number", ex.getMessage()));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request.getRequestURI(), details, null);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<Object> handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, ex.getMessage(), request.getRequestURI(),
                null, null);
    }

    @ExceptionHandler(InvalidOtpException.class)
    ResponseEntity<Object> handleInvalidOtp(InvalidOtpException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_OTP, ex.getMessage(), request.getRequestURI(),
                null, null);
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Object> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        return respondRetry(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, ex.getMessage(),
                request.getRequestURI(), ex.retryAfterSeconds());
    }

    @ExceptionHandler(CooldownActiveException.class)
    ResponseEntity<Object> handleCooldownActive(CooldownActiveException ex, HttpServletRequest request) {
        return respondRetry(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.COOLDOWN_ACTIVE, ex.getMessage(),
                request.getRequestURI(), ex.retryAfterSeconds());
    }

    /** The cause was already logged where it happened; the client only learns that the service is unavailable. */
    @ExceptionHandler(AuthenticationUnavailableException.class)
    ResponseEntity<Object> handleUnavailable(AuthenticationUnavailableException ex, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL, ex.getMessage(),
                request.getRequestURI(), null, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required",
                request.getRequestURI(), null, null);
    }

    /** Without this the catch-all below would turn a method-security 403 into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access is denied", request.getRequestURI(),
                null, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Internal server error",
                request.getRequestURI(), null, null);
    }

    private static ResponseEntity<Object> respond(HttpStatus status, ErrorCode code, String message, String path,
                                                  List<ApiError.Detail> details, HttpHeaders inherited) {
        HttpHeaders headers = new HttpHeaders();
        if (inherited != null) {
            // e.g. Allow on a 405
            headers.putAll(inherited);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return ResponseEntity.status(status).headers(headers)
                .body(ApiError.of(status, code, message, path, details));
    }

    /** 429 responses: same shape as {@link #respond}, plus a Retry-After header and a retry_after_seconds field. */
    private static ResponseEntity<Object> respondRetry(HttpStatus status, ErrorCode code, String message,
                                                        String path, long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return ResponseEntity.status(status).headers(headers)
                .body(ApiError.of(status, code, message, path, null, retryAfterSeconds));
    }

    private static String safeMessage(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Resource not found";
            case METHOD_NOT_ALLOWED -> "Method not allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
            case NOT_ACCEPTABLE -> "Not acceptable";
            default -> status.is5xxServerError() ? "Internal server error" : "Bad request";
        };
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    /** DTO property names are camelCase in Java but snake_case on the wire; report the wire name. */
    static String toSnakeCase(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
