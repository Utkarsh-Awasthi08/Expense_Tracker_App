package com.expenseTracker.userService.Error;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Turns every MVC failure into the pinned error body. Framework exceptions (405, 415, unmapped path, ...) flow
 * through {@link #handleExceptionInternal}; nothing here ever echoes an exception message, request value or SQL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Validation reports Java property names; clients speak snake_case (first_name, not firstName).
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
            new PropertyNamingStrategies.SnakeCaseStrategy();

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErrorBody.FieldDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorBody.FieldDetail(
                        SNAKE_CASE.translate(fe.getField()),
                        fe.getDefaultMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request, headers, details);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request, headers, null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Malformed or unreadable request body",
                request, headers, null);
    }

    /** Every remaining framework exception (405, 415, 404 for unmapped paths, missing attribute, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status.is5xxServerError()) {
            log.error("Unhandled framework error ({})", ex.getClass().getSimpleName(), ex);
        }
        return body(status, ErrorCode.forStatus(status.value()), ErrorBody.safeMessage(status.value()),
                request, headers, null);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Object> handleUserNotFound(UserNotFoundException ex, WebRequest request) {
        return body(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "User profile not found", request,
                new HttpHeaders(), null);
    }

    /** Last resort: logged with its stack trace server-side, opaque to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Internal server error", request,
                new HttpHeaders(), null);
    }

    private static ResponseEntity<Object> body(HttpStatus status, ErrorCode code, String message,
                                               WebRequest request, HttpHeaders headers,
                                               List<ErrorBody.FieldDetail> details) {
        String path = request instanceof ServletWebRequest swr ? requestPath(swr.getRequest()) : "";
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.remove(HttpHeaders.CONTENT_TYPE);
        out.add(HttpHeaders.CONTENT_TYPE, "application/json");
        return new ResponseEntity<>(ErrorBody.of(status, code, message, path, details), out, status);
    }

    private static String requestPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
