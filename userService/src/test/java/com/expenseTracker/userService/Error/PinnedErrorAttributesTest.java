package com.expenseTracker.userService.Error;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedErrorAttributesTest {

    private final PinnedErrorAttributes attributes = new PinnedErrorAttributes();

    private Map<String, Object> attributesFor(Integer status, Throwable error) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/error");
        if (status != null) {
            req.setAttribute("jakarta.servlet.error.status_code", status);
        }
        req.setAttribute("jakarta.servlet.error.request_uri", "/user/v1/broken");
        if (error != null) {
            req.setAttribute("jakarta.servlet.error.exception", error);
        }
        // Ask for everything: the pinned attributes must ignore the options and never copy exception details.
        return attributes.getErrorAttributes(new ServletWebRequest(req), ErrorAttributeOptions.of(
                ErrorAttributeOptions.Include.MESSAGE, ErrorAttributeOptions.Include.STACK_TRACE,
                ErrorAttributeOptions.Include.EXCEPTION, ErrorAttributeOptions.Include.BINDING_ERRORS));
    }

    @Test
    void clientErrorUsesPinnedShape() {
        Map<String, Object> body = attributesFor(400, null);
        assertThat(body.keySet()).containsExactly("timestamp", "status", "error", "code", "message", "path");
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Bad Request");
        assertThat(body.get("code")).isEqualTo("BAD_REQUEST");
        assertThat(body.get("path")).isEqualTo("/user/v1/broken");
        assertThat(Instant.parse((String) body.get("timestamp"))).isNotNull();
    }

    @Test
    void notFoundAndServerErrorMapToTheirCodesAndNeverLeakTheExceptionMessage() {
        assertThat(attributesFor(404, null).get("code")).isEqualTo("NOT_FOUND");
        Map<String, Object> body = attributesFor(500, new IllegalStateException("SELECT * FROM user_info secret"));
        assertThat(body.get("code")).isEqualTo("INTERNAL");
        assertThat(body.toString()).doesNotContain("SELECT").doesNotContain("secret").doesNotContain("IllegalState");
    }

    @Test
    void errorPageRequestedWithoutAnErrorFallsBackToInternal() {
        Map<String, Object> body = attributesFor(null, null);
        assertThat(body.get("status")).isEqualTo(500);
        assertThat(body.get("code")).isEqualTo("INTERNAL");
    }
}
