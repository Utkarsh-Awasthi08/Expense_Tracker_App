package com.expense.expenseService.Web;

import com.expense.expenseService.DTO.InvalidAmountException;
import com.expense.expenseService.Identity.UnauthenticatedException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders every failure that reaches Spring MVC in the pinned error body. Extends the standard handler so
 * every framework exception (405, 415, 404 for unknown paths, missing parameters, ...) is covered, not just ours.
 * Messages are fixed text: no stack trace, no SQL, no exception message and no rejected value ever leaves the service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleRequestValidation(RequestValidationException ex, WebRequest request) {
        return validationFailed(ex.getIssues(), request);
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<Object> handleUnauthenticated(UnauthenticatedException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing or invalid X-User-Id header",
                null, headers, request);
    }

    /** Last resort. The cause is logged with its stack trace; the client only sees a generic message. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception on {}", pathOf(request), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, ApiError.safeMessage(500),
                null, new HttpHeaders(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? snakeCase(fe.getField()) : error.getObjectName();
            issues.add(new FieldIssue(field, error.getDefaultMessage()));
        }
        return validationFailed(issues, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            MethodParameter parameter = result.getMethodParameter();
            String field = parameter.getParameterName() != null ? parameter.getParameterName() : "parameter";
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                issues.add(new FieldIssue(field, error.getDefaultMessage()));
            }
        });
        return validationFailed(issues, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidAmountException invalidAmount) {
                return validationFailed(List.of(new FieldIssue("amount", invalidAmount.getOriginalMessage())), request);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        // Jackson messages quote the offending input, so they are never passed on.
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Malformed or unreadable request body",
                null, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Parameter '" + name + "' has an invalid value",
                null, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing", null, headers, request);
    }

    /** Funnel for every other Spring MVC exception: status-derived code, fixed message. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        if (status >= 500) {
            log.error("Unhandled framework exception on {}", pathOf(request), ex);
        }
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            out.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return build(statusCode, ApiError.codeFor(status), ApiError.safeMessage(status), null, out, request);
    }

    private ResponseEntity<Object> validationFailed(List<FieldIssue> issues, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", issues,
                new HttpHeaders(), request);
    }

    private static ResponseEntity<Object> build(HttpStatusCode status, ErrorCode code, String message,
                                                List<FieldIssue> details, HttpHeaders headers, WebRequest request) {
        ApiError body = ApiError.of(status.value(), code, message, pathOf(request), details);
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        // Explicit, so the error is JSON even when the client's Accept header would not allow it.
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, out, status);
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : "";
    }

    private static String snakeCase(String property) {
        return new PropertyNamingStrategies.SnakeCaseStrategy().translate(property);
    }
}
