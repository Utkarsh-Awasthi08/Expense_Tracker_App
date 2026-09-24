package com.expenseTracker.userService.Deserializer;

import com.expenseTracker.userService.Entities.UserInfoDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy JSON deserializer for the old consumer path; replaced by ErrorHandlingDeserializer + a dead-letter
 * topic in Stage 4. Old-format events carry phone_number as a JSON number; Jackson coerces it into the String
 * field. This is a private mapper on purpose (it is not a Spring bean and does not touch Boot's mapper).
 * The payload is never logged: it holds names, phone numbers and emails.
 */
public class UserInfoDeserializer implements Deserializer<UserInfoDTO> {

    private static final Logger log = LoggerFactory.getLogger(UserInfoDeserializer.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(new PropertyNamingStrategies.SnakeCaseStrategy());

    @Override
    public void configure(java.util.Map<String, ?> configs, boolean isKey) { }

    @Override
    public UserInfoDTO deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            log.debug("Received null or empty data for topic {}", topic);
            return null;
        }
        try {
            return objectMapper.readValue(data, UserInfoDTO.class);
        } catch (Exception e) {
            log.error("Failed to deserialize UserInfoDTO for topic {} ({} bytes)", topic, data.length, e);
            throw new SerializationException("Failed to deserialize UserInfoDTO for topic " + topic, e);
        }
    }

    @Override
    public void close() { }
}
