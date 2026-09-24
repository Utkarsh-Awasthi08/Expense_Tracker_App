package com.expense.expenseService.Web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's default {@code /error} page so that failures which never reach Spring MVC (a servlet filter that
 * calls {@code sendError} or throws, for instance) also use the pinned error body. The identity filter does not run
 * for the container's {@code ERROR} dispatch (so this never reveals anything but the status the container already
 * decided on), while a direct request for {@code /error} is an ordinary request and needs a valid {@code X-User-Id}.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // A direct hit on /error (no container error behind it) is just an unknown resource.
        int status = attribute instanceof Integer code ? code : HttpStatus.NOT_FOUND.value();
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null || !(resolved.is4xxClientError() || resolved.is5xxServerError())) {
            status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalUri instanceof String uri ? uri : request.getRequestURI();

        ApiError body = ApiError.of(status, ApiError.codeFor(status), ApiError.safeMessage(status), path);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            response.header("WWW-Authenticate", "Bearer");
        }
        return response.body(body);
    }
}
