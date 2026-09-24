package com.expense.expenseService.Consumer;

import com.expense.expenseService.DTO.ExpenseDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;

public class ExpenseDeserializer implements Deserializer<ExpenseDTO> {

    // Thread-safe once configured, so one shared instance. The snake_case naming comes from @JsonNaming on the DTO,
    // never from a global naming strategy.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public ExpenseDTO deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(data, ExpenseDTO.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize ExpenseDTO", e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }
    @Override
    public void configure(java.util.Map<String, ?> configs, boolean isKey) {
        // No configuration needed
    }
}
