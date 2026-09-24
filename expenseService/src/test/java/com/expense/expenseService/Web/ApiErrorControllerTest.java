package com.expense.expenseService.Web;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorControllerTest {

    private final ApiErrorController controller = new ApiErrorController();

    private static MockHttpServletRequest errorDispatch(Integer status, String originalUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (status != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        }
        if (originalUri != null) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        }
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "internal detail that must not be echoed");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret"));
        return request;
    }

    @Test
    void containerErrorsUseThePinnedBodyWithTheOriginalPath() {
        ResponseEntity<ApiError> response = controller.error(errorDispatch(400, "/expense/v1/expenses"));

        assertEquals(400, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertAll(
                () -> assertEquals(400, body.status()),
                () -> assertEquals("Bad Request", body.error()),
                () -> assertEquals("BAD_REQUEST", body.code()),
                () -> assertEquals("/expense/v1/expenses", body.path()),
                () -> assertFalse(body.message().contains("internal detail")),
                () -> assertNull(body.details()),
                () -> assertTrue(body.timestamp().endsWith("Z"))
        );
        assertEquals("application/json", response.getHeaders().getContentType().toString());
    }

    @Test
    void serverErrorsAreInternalAndNeverEchoTheCause() {
        ApiError body = controller.error(errorDispatch(500, "/x")).getBody();

        assertEquals("INTERNAL", body.code());
        assertEquals("An unexpected error occurred", body.message());
    }

    @Test
    void aDirectHitOnErrorIsJustNotFound() {
        ResponseEntity<ApiError> response = controller.error(errorDispatch(null, null));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertEquals("/error", response.getBody().path());
    }

    @Test
    void a401FromTheContainerAlsoAdvertisesBearer() {
        ResponseEntity<ApiError> response = controller.error(errorDispatch(401, "/x"));

        assertEquals("Bearer", response.getHeaders().getFirst("WWW-Authenticate"));
        assertEquals("UNAUTHORIZED", response.getBody().code());
    }

    @Test
    void anImpossibleStatusIsReportedAs500() {
        assertEquals(500, controller.error(errorDispatch(200, "/x")).getStatusCode().value());
        assertEquals(500, controller.error(errorDispatch(999, "/x")).getStatusCode().value());
    }
}
