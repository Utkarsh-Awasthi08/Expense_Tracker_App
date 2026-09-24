package org.example.Serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serializer;
import org.example.EventProducer.UserInfoEvent;
import org.example.Model.UserInfoDto;

import java.util.Map;

public class UserInfoSerializer implements Serializer<UserInfoEvent>
{
    private static final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public void configure(Map<String, ?> map, boolean b) {
    }

    @Override
    public byte[] serialize(String topic, UserInfoEvent data) {
        try {
            return data == null ? null : objectMapper.writeValueAsString(data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error serializing UserInfoDto", e);
        }
    }
    @Override public void close() {
    }
}
