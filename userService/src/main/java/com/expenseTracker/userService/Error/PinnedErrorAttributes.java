package com.expenseTracker.userService.Error;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes Boot's own /error endpoint (container-level failures such as a bad request line) answer with the
 * pinned error body instead of Boot's default attributes. Exception messages are never copied.
 */
public class PinnedErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());
        int status = defaults.get("status") instanceof Integer s ? s : 500;
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null) {
            // 999 = the error page was requested without an error, otherwise a non-standard status.
            resolved = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        body.put("status", resolved.value());
        body.put("error", resolved.getReasonPhrase());
        body.put("code", ErrorCode.forStatus(resolved.value()).name());
        body.put("message", ErrorBody.safeMessage(resolved.value()));
        body.put("path", defaults.get("path") instanceof String p ? p : "");
        return body;
    }
}
