package org.example.Request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InputNormalizerTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            '+91 98765 43210'   | +919876543210
            98765-43210         | +919876543210
            +919876543210       | +919876543210
            (022) 2345.6789     | +9102223456789
            '+1 (415) 555-2671' | +14155552671
            1234567             | +911234567
            123456789012345     | +91123456789012345
            """)
    void acceptedPhoneNumbersAreNormalised(String raw, String expected) {
        assertThat(InputNormalizer.otpPhoneNumber(raw, "91")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456",                   // shorter than 7
            "1234567890123456",         // 16 digits
            "+",
            "++919876543210",
            "91+9876543210",
            "98765x43210",
            "12ab",
            "98765\t43210",
            "98765 43210",
            "98765​43210",
            "+-",
            "()"})
    void rejectedPhoneNumbersReturnNull(String raw) {
        assertThat(InputNormalizer.otpPhoneNumber(raw, "91")).isNull();
    }

    @Test
    void nullPhoneReturnsNull() {
        assertThat(InputNormalizer.otpPhoneNumber(null, "91")).isNull();
    }

    @Test
    void otpVerifyRequestValidation() {
        OtpVerifyRequest valid = new OtpVerifyRequest("+919876543210", "123456");
        Set<String> violations = VALIDATOR.validate(valid).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(violations).isEmpty();

        OtpVerifyRequest invalidCode = new OtpVerifyRequest("+919876543210", "12345");
        Set<String> invalidViolations = VALIDATOR.validate(invalidCode).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(invalidViolations).contains("code");
    }

    @Test
    void otpVerifyRequestToStringHidesCode() {
        OtpVerifyRequest request = new OtpVerifyRequest("+919876543210", "112233");
        assertThat(request.toString()).doesNotContain("112233");
        assertThat(request.toString()).contains("+919876543210");
    }
}
