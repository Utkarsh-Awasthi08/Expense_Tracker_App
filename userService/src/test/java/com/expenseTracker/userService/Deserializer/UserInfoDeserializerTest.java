package com.expenseTracker.userService.Deserializer;

import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Entities.UserInfoDTO;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The legacy consumer path (rewritten in Stage 4) must keep reading old-format events into the new DTO. */
class UserInfoDeserializerTest {

    private final UserInfoDeserializer deserializer = new UserInfoDeserializer();

    private UserInfoDTO read(String json) {
        return deserializer.deserialize("testingselfservice_json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oldFormatEventWithNumericPhoneNumberDeserializesIntoStringField() {
        UserInfoDTO dto = read("""
                {"user_id":"3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91","first_name":"Priya","last_name":"Sharma",
                 "phone_number":9876500012,"email":"priya.sharma@example.com","profile_picture":null,
                 "username":"priya.sharma","password":"ignored-legacy-field"}
                """);
        assertThat(dto.getUserId()).isEqualTo("3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91");
        assertThat(dto.getFirstName()).isEqualTo("Priya");
        assertThat(dto.getLastName()).isEqualTo("Sharma");
        assertThat(dto.getPhoneNumber()).isEqualTo("9876500012");
        assertThat(dto.getEmail()).isEqualTo("priya.sharma@example.com");
        assertThat(dto.getDefaultCurrency()).isNull();
        assertThat(dto.getTimezone()).isNull();
    }

    @Test
    void newFormatContractFixtureDeserializesToo() throws Exception {
        String json = Files.readString(Path.of("..", "contracts", "fixtures", "user.created.v1.json"));
        UserInfoDTO dto = read(json);
        assertThat(dto.getUserId()).isEqualTo("3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91");
        assertThat(dto.getPhoneNumber()).isEqualTo("9876500012");
        assertThat(dto.getFirstName()).isEqualTo("Priya");
        assertThat(dto.getEmail()).isEqualTo("priya.sharma@example.com");
    }

    @Test
    void eventDtoBecomesEntityWithColumnDefaultsWhenCurrencyAndTimezoneAreAbsent() {
        UserInfo entity = read("{\"user_id\":\"u-1\",\"phone_number\":9876500012}").transformToUserInfo();
        assertThat(entity.getUserId()).isEqualTo("u-1");
        assertThat(entity.getPhoneNumber()).isEqualTo("9876500012");
        assertThat(entity.getDefaultCurrency()).isEqualTo("INR");
        assertThat(entity.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(entity.getId()).isNull();
    }

    @Test
    void nullAndEmptyPayloadsYieldNull() {
        assertThat(deserializer.deserialize("t", null)).isNull();
        assertThat(deserializer.deserialize("t", new byte[0])).isNull();
    }

    @Test
    void garbageThrowsSerializationExceptionWithoutLeakingPayload() {
        assertThatThrownBy(() -> read("this is not json, secret@example.com"))
                .isInstanceOf(SerializationException.class)
                .hasMessageNotContaining("secret@example.com");
    }

    @Test
    void dtoToStringDoesNotExposePii() {
        UserInfoDTO dto = read("""
                {"user_id":"u-1","first_name":"Priya","phone_number":9876500012,"email":"priya@example.com"}""");
        assertThat(dto.toString()).contains("u-1").doesNotContain("Priya").doesNotContain("9876500012")
                .doesNotContain("priya@example.com");
    }
}
