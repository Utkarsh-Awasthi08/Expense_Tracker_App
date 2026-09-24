package com.expense.expenseService.Web;

import com.expense.expenseService.Identity.UnauthenticatedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The advice on its own, against a probe controller, so every branch (not only the ones the expense endpoints
 * happen to trigger) is proven to answer in the pinned shape without leaking.
 */
class GlobalExceptionHandlerTest {

    record Payload(@NotBlank String merchantName, @Min(1) int itemCount) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("could not execute statement [select * from users] password=hunter2");
        }

        @GetMapping("/probe/unauthenticated")
        String unauthenticated() {
            throw new UnauthenticatedException();
        }

        @GetMapping("/probe/teapot")
        String status() {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "internal detail xyz");
        }

        @GetMapping("/probe/number")
        String number(@RequestParam("n") int n) {
            return "" + n;
        }

        @GetMapping("/probe/validation")
        String validation() {
            throw new RequestValidationException(java.util.List.of(new FieldIssue("a", "is wrong"), new FieldIssue("b", "too")));
        }

        @PostMapping("/probe/bean")
        String bean(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/probe/min")
        String min(@RequestParam("size") @Min(1) int size) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void unexpectedExceptionsAre500InternalWithAFixedMessage() throws Exception {
        String body = mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/probe/boom"))
                .andExpect(jsonPath("$.timestamp", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("hunter2") || body.contains("select") || body.contains("IllegalState"), body);
    }

    @Test
    void unauthenticatedIs401WithWwwAuthenticate() throws Exception {
        mvc.perform(get("/probe/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void responseStatusExceptionsUseTheStatusDerivedCodeAndHideTheReason() throws Exception {
        mvc.perform(get("/probe/teapot"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message", not(containsString("xyz"))));
    }

    @Test
    void requestValidationExceptionListsEveryIssue() throws Exception {
        mvc.perform(get("/probe/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath("$.details[0].field").value("a"))
                .andExpect(jsonPath("$.details[0].message").value("is wrong"))
                .andExpect(jsonPath("$.details[1].field").value("b"));
    }

    @Test
    void typeMismatchIs400BadRequestNamingOnlyTheParameter() throws Exception {
        mvc.perform(get("/probe/number?n=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Parameter 'n' has an invalid value"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void missingRequiredParameterIs400BadRequest() throws Exception {
        mvc.perform(get("/probe/number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Required parameter 'n' is missing"));
    }

    @Test
    void malformedJsonIs400BadRequestWithoutJacksonText() throws Exception {
        mvc.perform(post("/probe/bean").contentType(MediaType.APPLICATION_JSON).content("{\"merchant_name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    void beanValidationFailuresBecomeValidationFailedWithSnakeCaseFieldNames() throws Exception {
        mvc.perform(post("/probe/bean").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\": \"\", \"itemCount\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("merchant_name", "item_count")));
    }

    @Test
    void methodParameterValidationFailuresBecomeValidationFailed() throws Exception {
        mvc.perform(get("/probe/min?size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("size"));
    }

    @Test
    void unsupportedMethodIs405WithAllowHeaderAndUnsupportedMediaTypeIs415() throws Exception {
        mvc.perform(post("/probe/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(post("/probe/bean").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"));
    }

    @Test
    void errorsAreJsonEvenWhenTheClientOnlyAcceptsHtml() throws Exception {
        mvc.perform(get("/probe/boom").accept(MediaType.TEXT_HTML))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL"));
    }

    @Test
    void statusToCodeMapping() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.UNAUTHORIZED, ApiError.codeFor(401)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.FORBIDDEN, ApiError.codeFor(403)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.NOT_FOUND, ApiError.codeFor(404)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.CONFLICT, ApiError.codeFor(409)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.BAD_REQUEST, ApiError.codeFor(400)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.BAD_REQUEST, ApiError.codeFor(415)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.INTERNAL, ApiError.codeFor(500)),
                () -> org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.INTERNAL, ApiError.codeFor(503))
        );
    }
}
